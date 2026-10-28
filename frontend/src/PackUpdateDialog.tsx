import {useEffect,useRef,useState} from 'react';
import {Dialog} from './Dialog';
import {api} from './http';
import {uploadBatch} from './UploadProgress';
type Plan={pack:{name:string;version:string;minecraft:string;loader:string;loaderVersion:string};currentMods:string[];incomingMods:string[];configFiles:string[];excludedClientMods:string[];ignoredFiles:string[];warnings:string[]};
type Status={token:string;phase:string;message:string;plan?:Plan;completed:number;total:number};
export function PackUpdateDialog({id,onClose,onChanged}:{id:string;onClose:()=>void;onChanged:()=>Promise<unknown>}){
 const [status,setStatus]=useState<Status|null>(null),[error,setError]=useState(''),[uploading,setUploading]=useState(false);
 const [mode,setMode]=useState('merge'),[config,setConfig]=useState(false),[exclude,setExclude]=useState(true),[file,setFile]=useState<File|null>(null);
 const alive=useRef(true),uploadLock=useRef(false);const endpoint=`/instances/${id}/pack-update`;
 const running=status?.phase==='PREPARING'||status?.phase==='APPLYING',busy=uploading||running;
 useEffect(()=>{let timer:ReturnType<typeof setTimeout>;alive.current=true;let stopped=false;
  const poll=async()=>{try{const next=await api<Status|null>(endpoint);if(alive.current&&!uploadLock.current)setStatus(next);}catch(e){if(alive.current)setError((e as Error).message);}finally{if(!stopped)timer=setTimeout(poll,1000);}};
  void poll();return()=>{stopped=true;alive.current=false;clearTimeout(timer);};
 },[id]);
 async function inspect(selected:File){
  if(busy||uploadLock.current)return;if(!selected.name.toLowerCase().endsWith('.mrpack')){setError('.mrpack 파일을 선택하세요.');return;}
  uploadLock.current=true;setUploading(true);setStatus(null);setError('');setFile(selected);
  try{const result=await uploadBatch('mrpack 업데이트 준비',[{file:selected,relative:selected.name}],()=>endpoint+'/preview');if(result.errors.length)throw Error(result.errors[0]);const next=await api<Status>(endpoint);if(alive.current)setStatus(next);}catch(e){if(alive.current)setError((e as Error).message);}finally{uploadLock.current=false;if(alive.current)setUploading(false);}
 }
 const plan=status?.plan,incoming=(plan?.incomingMods||[]).filter(path=>!exclude||!plan?.excludedClientMods.includes(path));
 const configs=(plan?.configFiles||[]).filter(path=>!exclude||!plan?.excludedClientMods.includes(path));
 const added=incoming.filter(path=>!plan?.currentMods.includes(path)),replaced=incoming.filter(path=>plan?.currentMods.includes(path));
 const removed=mode==='replace'?(plan?.currentMods||[]).filter(path=>!incoming.includes(path)):[];
 async function apply(){if(!status||busy)return;setError('');setUploading(true);try{const next=await api<Status>(endpoint,'POST',{token:status.token,mode,overwriteConfig:config,excludeClientOnly:exclude});setStatus(next);await onChanged();}catch(e){setError((e as Error).message);}finally{setUploading(false);}}
 return <Dialog label="기존 서버 mrpack 업데이트" busy={uploading} onClose={onClose} className="pack-modal"><h2>기존 서버 mrpack 업데이트</h2><p>월드·플레이어 데이터·server.properties는 보존합니다. mods와 선택한 config만 반영합니다. Minecraft와 로더 자체는 교체하지 않습니다.</p><p className="notice">적용하려면 서버와 파일 전송을 종료하세요. 변경 전 원본을 백업하며 실패하면 복구합니다.</p>
 {error&&<div role="alert" className="alert">{error}</div>}
 <label className="pack-drop" onDragOver={e=>e.preventDefault()} onDrop={e=>{e.preventDefault();const f=e.dataTransfer.files[0];if(f)void inspect(f);}}><strong>{uploading?'팩 업로드 중…':file?.name||'업데이트할 mrpack을 놓거나 선택하세요'}</strong><input aria-label="업데이트 mrpack 파일" type="file" accept=".mrpack" disabled={busy} onChange={e=>{if(e.target.files?.[0])void inspect(e.target.files[0]);e.target.value='';}}/></label>
 {status&&<p role="status">{status.message}{status.total>0&&` · ${status.completed}/${status.total}`}</p>}
 {plan&&<><div className="pack-summary"><strong>{plan.pack.name} · {plan.pack.version}</strong><p>Minecraft {plan.pack.minecraft} / {plan.pack.loader} {plan.pack.loaderVersion}</p></div>
 <fieldset disabled={busy||status?.phase!=='READY'} className="pack-update-options"><label>모드 업데이트 방식<select aria-label="모드 업데이트 방식" value={mode} onChange={e=>setMode(e.target.value)}><option value="merge">기존 모드 유지 · 팩 파일 추가/같은 이름 덮어쓰기</option><option value="replace">팩 모드 목록으로 교체 · 팩에 없는 모드 삭제</option></select></label>
 <label className="check-label"><input type="checkbox" checked={exclude} onChange={e=>setExclude(e.target.checked)}/> 클라이언트 전용 모드 제외 ({plan.excludedClientMods.length}개)</label>
 <label className="check-label"><input type="checkbox" checked={config} onChange={e=>setConfig(e.target.checked)}/> config 파일 추가·덮어쓰기 ({configs.length}개) · 체크하지 않으면 모두 보존</label></fieldset>
 {mode==='merge'&&<p className="muted">파일명이 바뀐 이전 버전은 남습니다. 중복 버전을 피하려면 교체 방식을 선택하세요.</p>}
 <div className="pack-update-changes" aria-label="팩 업데이트 변경 내역">{[['추가 모드',added],['덮어쓸 모드',replaced],['삭제 모드',removed],['제외할 클라이언트 모드',exclude?plan.excludedClientMods:[]],['config '+(config?'추가·덮어쓰기':'보존'),configs],['보존을 위해 반영하지 않는 팩 파일',plan.ignoredFiles]].map(([label,paths])=><div key={label as string}><h4>{label as string} · {(paths as string[]).length}개</h4>{(paths as string[]).length>0&&<ul>{(paths as string[]).map(path=><li key={path}>{path}</li>)}</ul>}</div>)}</div>
 {!!plan.warnings.length&&<div className="eula-note">{plan.warnings.map((warning,i)=><p key={i}>{warning}</p>)}</div>}</>}
 <div className="modal-actions"><button disabled={uploading} onClick={onClose}>{running?'백그라운드 진행 · 닫기':'닫기'}</button><button className="primary" disabled={busy||status?.phase!=='READY'||!(incoming.length||removed.length||config&&configs.length)} onClick={()=>void apply()}>이 방법으로 업데이트</button></div></Dialog>;
}
