import {Dialog} from './Dialog';
import {useState} from 'react';
import {Upload,Package} from 'lucide-react';
import {api} from './http';

type Preview={token:string;plan:{name:string;version:string;minecraft:string;loaderVersion:string;loader?:string;files:{path:string;fileSize:number;server:string}[];overrides:string[];warnings:string[]}};
export function MrpackDialog({javaPath,port,maxMemory,onClose,onCreated}:{javaPath:string;port:number;maxMemory:number;onClose:()=>void;onCreated:(id:string)=>Promise<void>}){
 const [preview,setPreview]=useState<Preview|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[drag,setDrag]=useState(false);
 const [name,setName]=useState(''),[gamePort,setGamePort]=useState(port),[heap,setHeap]=useState(Math.min(2048,maxMemory)),[java,setJava]=useState(javaPath),[exclude,setExclude]=useState(true);
 async function inspect(file?:File){if(!file||busy)return;setBusy(true);setError('');setPreview(null);try{const form=new FormData();form.append('file',file);const p=await api<Preview>('/mrpacks/preview','POST',form);setPreview(p);setName(p.plan.name);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 async function submit(){if(!preview||busy)return;setBusy(true);setError('');try{const row=await api<{config:{id:string}}>('/mrpacks/import','POST',{token:preview.token,name,port:gamePort,memoryMb:heap,javaPath:java,excludeClientOnly:exclude});await onCreated(row.config.id);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <Dialog label="mrpack 가져오기" busy={busy} onClose={onClose} className="pack-modal"><div className="eyebrow">MODRINTH MODPACK</div><h2>mrpack 가져오기</h2><p>새 인스턴스로 파일을 검증하고 NeoForge까지 설치합니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}
 <fieldset disabled={busy} className="dialog-fields"><label className={'pack-drop '+(drag?'drag-active':'')} onDragOver={e=>{e.preventDefault();setDrag(true);}} onDragLeave={()=>setDrag(false)} onDrop={e=>{e.preventDefault();setDrag(false);inspect(e.dataTransfer.files[0]);}}><Package size={28}/><strong>{busy?'팩 처리 중…':'mrpack 파일을 놓거나 선택하세요'}</strong><small>NeoForge · Forge · Fabric · 최대 256 MiB</small><input aria-label="mrpack 파일" type="file" accept=".mrpack" disabled={busy} onChange={e=>inspect(e.target.files?.[0])}/></label>
 {preview&&<><div className="pack-summary"><strong>{preview.plan.name} · {preview.plan.version}</strong><p>Minecraft {preview.plan.minecraft} / {preview.plan.loader||'NeoForge'} {preview.plan.loaderVersion}<br/>{preview.plan.files.length}개 다운로드 · {preview.plan.overrides.length}개 overrides · {(preview.plan.files.reduce((n,f)=>n+f.fileSize,0)/1024/1024).toFixed(1)} MiB</p></div>
 <details className="pack-files"><summary>설치 파일과 제외 후보 보기</summary>{preview.plan.files.map(f=><div key={f.path}>{f.path} <span>{f.server==='unsupported'?'클라이언트 전용 후보':f.server==='optional'?'서버 선택 파일':'서버 파일'}</span></div>)}{preview.plan.overrides.map(p=><div key={p}>{p} <span>팩 내부 파일</span></div>)}</details>
 <label className="check-label"><input type="checkbox" checked={exclude} onChange={e=>setExclude(e.target.checked)}/> 의존성 문제가 없는 클라이언트 전용 모드 제외</label>
 <div className="eula-note">{preview.plan.warnings.map(w=><div key={w}>{w}</div>)}{!exclude&&<div>제외를 끄면 클라이언트 전용 모드도 포함돼 서버 시작이 실패할 수 있습니다.</div>}</div>
 <div className="form-grid"><label className="wide">서버 이름<input value={name} onChange={e=>setName(e.target.value)}/></label><label>게임 포트<input type="number" min="1024" max="65535" value={gamePort} onChange={e=>setGamePort(+e.target.value)}/></label><label>최대 메모리 (MiB)<input type="number" min="512" max={maxMemory} value={heap} onChange={e=>setHeap(+e.target.value)}/></label><label className="wide">Java 실행 파일<input value={java} onChange={e=>setJava(e.target.value)}/></label></div></>}
 <div className="modal-actions"><button disabled={busy} onClick={onClose}>닫기</button><button className="primary" disabled={busy||!preview||!name} onClick={submit}><Upload size={16}/> 가져오기 및 설치</button></div></fieldset></Dialog>;
}
