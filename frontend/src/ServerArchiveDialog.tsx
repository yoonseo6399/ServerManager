import {useEffect,useRef,useState} from 'react';
import {Archive,Upload} from 'lucide-react';
import {Dialog} from './Dialog';
import {api} from './http';

type Plan={name:string;root:string;files:number;bytes:number;loader?:string|null;minecraft?:string|null;loaderVersion?:string|null;entryJar?:string|null;requiredJava?:number|null;jars:string[];warnings:string[]};
type Preview={token:string;plan:Plan};
type UploadState={token:string;size:number;received:number;chunkSize:number};
export type ArchiveProgress={phase:string;files:number;bytes:number;totalFiles:number;totalBytes:number;message:string};
const size=(n:number)=>n>=1024**3?(n/1024**3).toFixed(2)+' GiB':(n/1024**2).toFixed(1)+' MiB';
export function ArchiveImportStatus({value}:{value:ArchiveProgress}){
 const [hidden,setHidden]=useState(false);useEffect(()=>setHidden(false),[value.phase]);if(hidden)return null;
 return <section className="transfer-card" aria-label="서버 ZIP 가져오기 상태" aria-live="polite" aria-busy={value.phase==='EXTRACTING'}><strong>{value.phase==='EXTRACTING'?'서버 ZIP 검증·압축 해제 중':value.phase==='COMPLETE'?'서버 ZIP 가져오기 완료':'서버 ZIP 가져오기 실패'}</strong><p>{value.message}</p><p>{value.files.toLocaleString()} / {value.totalFiles.toLocaleString()}개 · {size(value.bytes)} / {size(value.totalBytes)}</p>{value.phase==='EXTRACTING'&&value.totalBytes>0&&<progress aria-label="ZIP 압축 해제 진행률" max={value.totalBytes} value={value.bytes}/>} {value.phase!=='EXTRACTING'&&<button onClick={()=>setHidden(true)}>상태 닫기</button>}</section>;
}
export function ServerArchiveDialog({javaPath,port,maxMemory,onClose,onCreated}:{javaPath:string;port:number;maxMemory:number;onClose:()=>void;onCreated:(id:string)=>Promise<void>}){
 const [preview,setPreview]=useState<Preview|null>(null),[file,setFile]=useState<File|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[drag,setDrag]=useState(false),[uploading,setUploading]=useState(false),[uploaded,setUploaded]=useState(0),[phase,setPhase]=useState('');
 const token=useRef<string|null>(null),cancelled=useRef(false),controller=useRef<AbortController|null>(null),alive=useRef(true);
 const [name,setName]=useState(''),[loader,setLoader]=useState(''),[minecraft,setMinecraft]=useState(''),[version,setVersion]=useState(''),[jar,setJar]=useState(''),[requiredJava,setRequiredJava]=useState(21),[gamePort,setGamePort]=useState(port),[heap,setHeap]=useState(Math.min(2048,maxMemory)),[java,setJava]=useState(javaPath);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;controller.current?.abort();};},[]);
 const cleanup=async()=>{if(token.current){await api(`/server-archives/uploads/${token.current}`,'DELETE');token.current=null;}};
 async function upload(selected:File){
  if(busy)return;if(!selected.name.toLowerCase().endsWith('.zip')||selected.size>20*1024**3||selected.size===0){setError('서버 폴더를 압축한 ZIP 파일을 선택하세요. 최대 20 GiB입니다.');return;}
  setBusy(true);setUploading(true);setError('');setPreview(null);cancelled.current=false;controller.current=new AbortController();
  try{
   if(file!==selected){await cleanup();setUploaded(0);setFile(selected);}
   setPhase('업로드 준비 중…');
   let status=token.current?await api<UploadState>(`/server-archives/uploads/${token.current}`):await api<UploadState>('/server-archives/uploads','POST',{filename:selected.name,size:selected.size});
   token.current=status.token;setUploaded(status.received);
   while(status.received<selected.size){
    if(cancelled.current)throw new DOMException('취소됨','AbortError');
    setPhase('서버 ZIP 업로드 중…');
    status=await api<UploadState>(`/server-archives/uploads/${status.token}?offset=${status.received}`,'PUT',selected.slice(status.received,Math.min(selected.size,status.received+status.chunkSize)),controller.current.signal);
    setUploaded(status.received);
   }
   if(cancelled.current)throw new DOMException('취소됨','AbortError');
   setPhase('압축파일 구조·서버 정보 확인 중…');
   const result=await api<Preview>(`/server-archives/uploads/${status.token}/preview`,'POST');
   if(cancelled.current)throw new DOMException('취소됨','AbortError');
   if(!alive.current)return;setPreview(result);setName(result.plan.name);setLoader(result.plan.loader||'');setMinecraft(result.plan.minecraft||'');setVersion(result.plan.loaderVersion||'');setJar(result.plan.entryJar||'');setRequiredJava(result.plan.requiredJava||21);setPhase('미리보기 완료');
  }catch(e){
   if(cancelled.current){try{await cleanup();setUploaded(0);setPhase('업로드를 취소했습니다.');}catch(cleanError){if(alive.current)setError('업로드 정리를 완료하지 못했습니다. '+(cleanError as Error).message);}}
   else if(alive.current)setError((e as Error).message);
  }finally{if(alive.current){setBusy(false);setUploading(false);}controller.current=null;}
 }
 async function close(){setError('');try{await cleanup();onClose();}catch(e){setError((e as Error).message);}}
 async function submit(){if(!preview||busy)return;setBusy(true);setError('');try{
  const row=await api<{config:{id:string}}>('/server-archives/import','POST',{token:preview.token,name,loader,minecraft,loaderVersion:loader==='Vanilla'?minecraft:version,entryJar:loader==='Forge'||loader==='NeoForge'?null:jar||null,requiredJava,port:gamePort,memoryMb:heap,javaPath:java});token.current=null;await onCreated(row.config.id);
 }catch(e){if(alive.current)setError((e as Error).message);}finally{if(alive.current)setBusy(false);}}
 return <Dialog label="서버 ZIP 가져오기" busy={busy} onClose={()=>void close()} className="pack-modal"><div className="eyebrow">EXISTING SERVER ARCHIVE</div><h2>서버 ZIP 가져오기</h2><p>서버 폴더를 ZIP으로 압축해서 올리세요. 월드·모드·설정을 새 서버로 가져오며 기존 서버를 덮어쓰지 않습니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}
 <fieldset disabled={busy} className="dialog-fields"><label className={'pack-drop '+(drag?'drag-active':'')} onDragOver={e=>{e.preventDefault();setDrag(true);}} onDragLeave={()=>setDrag(false)} onDrop={e=>{e.preventDefault();setDrag(false);const f=e.dataTransfer.files[0];if(f)void upload(f);}}><Archive size={28}/><strong>서버 ZIP 파일을 놓거나 선택하세요</strong><small>ZIP 최대 20 GiB · 압축 해제 최대 100 GiB · 8 MiB씩 분할 업로드</small><input aria-label="서버 ZIP 파일" type="file" accept=".zip,application/zip" disabled={busy} onChange={e=>{const f=e.target.files?.[0];if(f)void upload(f);}}/></label>
 {preview&&<><div className="pack-summary"><strong>{preview.plan.name}</strong><p>서버 폴더: {preview.plan.root||'ZIP 최상위'}<br/>{preview.plan.files.toLocaleString()}개 파일 · 압축 해제 {size(preview.plan.bytes)}</p></div><div className="eula-note">{preview.plan.warnings.map(w=><p key={w}>{w}</p>)}</div>
 <div className="form-grid"><label className="wide">서버 이름<input value={name} maxLength={80} onChange={e=>setName(e.target.value)}/></label><label>서버 종류<select aria-label="서버 종류" value={loader} onChange={e=>{setLoader(e.target.value);setVersion(e.target.value==='Vanilla'?minecraft:'');}}><option value="">선택하세요</option>{['Vanilla','Paper','Fabric','Forge','NeoForge'].map(l=><option key={l}>{l}</option>)}</select></label><label>Minecraft 버전<input placeholder="예: 1.21.1" value={minecraft} onChange={e=>{setMinecraft(e.target.value);if(loader==='Vanilla')setVersion(e.target.value);}}/></label>
 {loader!=='Vanilla'&&<label>로더 버전 / Paper 빌드<input placeholder="예: 21.1.264" value={version} onChange={e=>setVersion(e.target.value)}/></label>}
 {loader&&loader!=='Forge'&&loader!=='NeoForge'&&<label>실행할 서버 JAR<select aria-label="실행할 서버 JAR" value={jar} onChange={e=>setJar(e.target.value)}><option value="">기본 파일명 / 가져온 뒤 런처 설치</option>{preview.plan.jars.map(j=><option key={j}>{j}</option>)}</select></label>}
 <label>필요 Java 버전<select aria-label="필요 Java 버전" value={requiredJava} onChange={e=>setRequiredJava(+e.target.value)}>{[17,21,25].map(n=><option key={n} value={n}>Java {n} 이상</option>)}</select></label><label>내부 게임 포트<input type="number" min={1024} max={65535} value={gamePort} onChange={e=>setGamePort(+e.target.value)}/></label><label>최대 메모리 (MiB)<input type="number" min={512} max={maxMemory} value={heap} onChange={e=>setHeap(+e.target.value)}/></label><label className="wide">Java 실행 파일<input value={java} onChange={e=>setJava(e.target.value)}/></label></div>
 <p className="muted">바깥 폴더는 자동 인식합니다. 포트·로컬 바인딩·Query 설정은 선택한 인스턴스에 맞춥니다. 기존 EULA 파일은 유지하고, 없으면 일반 서버 생성과 같은 동의 설정을 사용합니다. 실행 파일이 없으면 가져온 뒤 런처를 설치하세요.</p></>}
 <div className="modal-actions"><button disabled={busy} onClick={()=>void close()}>닫기</button><button className="primary" disabled={busy||!preview||!name.trim()||!loader||!minecraft||loader!=='Vanilla'&&!version||!java} onClick={()=>void submit()}><Upload size={16}/> 새 서버로 가져오기</button></div></fieldset>
 {file&&<div role="status" aria-live="polite"><p>{phase} · {size(uploaded)} / {size(file.size)}</p>{uploading&&<progress aria-label="ZIP 업로드 진행률" max={file.size} value={uploaded}/>}</div>}
 {uploading&&<button onClick={()=>{cancelled.current=true;controller.current?.abort();}}>업로드 취소</button>}
 {!busy&&!preview&&file&&<button onClick={()=>void upload(file)}>업로드 다시 시도 / 이어서 올리기</button>}
 </Dialog>;
}
