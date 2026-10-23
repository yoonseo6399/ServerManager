import {useEffect,useRef,useState,type ReactNode,type DragEvent} from 'react';
import {ArrowUp,Download,FileText,Folder,Plus,RefreshCw,Trash2,Pencil,Upload} from 'lucide-react';
import {api} from './http';
import {FileEditor,type EditableFile} from './FileEditor';
import {SaveBar,navigateWithUnsaved} from './SaveBar';
import {droppedFiles,type UploadItem} from './dropFiles';

type Entry={name:string;path:string;directory:boolean;size:number};
type Props={id:string;name:string;folder:string;editorPath:string|null;canManage:boolean;blocked:boolean;directory:string;remote:boolean;onFolder:(path:string)=>void;onEditor:(path:string|null)=>void;children?:ReactNode};
/** All file responses and mutations belong to this server. Remount when the server changes. */
export function FileBrowser({id,name,folder,editorPath,canManage,blocked,directory,remote,onFolder,onEditor,children}:Props){
 const [listing,setListing]=useState<{folder:string;rows:Entry[]}|null>(null),[loading,setLoading]=useState(true),[listError,setListError]=useState('');
 const [file,setFile]=useState<EditableFile|null>(null),[reading,setReading]=useState(false),[readError,setReadError]=useState('');
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[progress,setProgress]=useState(''),[dragging,setDragging]=useState(false),[page,setPage]=useState(0);
 const lock=useRef(false),alive=useRef(true),listSequence=useRef(0),readSequence=useRef(0),location=useRef({folder,editorPath});location.current={folder,editorPath};
 const input=useRef<HTMLInputElement>(null);
 const endpoint=(suffix:string)=>`/instances/${id}/${suffix}`;
 useEffect(()=>()=>{alive.current=false;listSequence.current++;readSequence.current++;},[]);
 async function load(){
  const target=folder,sequence=++listSequence.current;setLoading(true);setListError('');
  try{const rows=await api<Entry[]>(endpoint('files')+'?path='+encodeURIComponent(target));if(alive.current&&sequence===listSequence.current&&location.current.folder===target)setListing({folder:target,rows});}
  catch(e){if(alive.current&&sequence===listSequence.current&&location.current.folder===target)setListError((e as Error).message);}
  finally{if(alive.current&&sequence===listSequence.current)setLoading(false);}
 }
 useEffect(()=>{setPage(0);void load();return()=>{listSequence.current++;};},[folder]);
 async function read(){
  const path=editorPath,sequence=++readSequence.current;setFile(null);setReadError('');if(path===null){setReading(false);return;}
  setReading(true);
  try{const data=await api<{content:string;revision:string}>(endpoint('text')+'?path='+encodeURIComponent(path));if(alive.current&&sequence===readSequence.current&&location.current.editorPath===path)setFile({path,...data});}
  catch(e){if(alive.current&&sequence===readSequence.current)setReadError((e as Error).message);}
  finally{if(alive.current&&sequence===readSequence.current)setReading(false);}
 }
 // New files are staged locally, and must not issue a read before they have been saved.
 const newPath=useRef<string|null>(null);
 useEffect(()=>{if(editorPath!==null&&editorPath===newPath.current){newPath.current=null;return;}void read();return()=>{readSequence.current++;};},[editorPath]);
 async function mutate(fn:()=>Promise<void>,message:string){
  if(lock.current||!canManage||blocked)return;
  lock.current=true;setBusy(true);setError('');setNotice('');
  try{await fn();if(alive.current){setNotice(message);await load();}}
  catch(e){if(alive.current)setError((e as Error).message);}
  finally{lock.current=false;if(alive.current){setBusy(false);setProgress('');}}
 }
 const go=(path:string)=>navigateWithUnsaved(()=>{onEditor(null);onFolder(path);});
 const open=(entry:Entry)=>navigateWithUnsaved(()=>{setError('');if(entry.directory){onEditor(null);onFolder(entry.path);}else onEditor(entry.path);});
 async function save(value:EditableFile){
  // PUT success is authoritative. Do not require a second GET that can fail or capture someone else's edit.
  await mutate(async()=>{await api(endpoint('text'),'PUT',value);if(alive.current&&location.current.editorPath===value.path){
   // Fetch only the new revision; preserve draft on an uncertain read so the user can recover.
   try{const fresh=await api<{content:string;revision:string}>(endpoint('text')+'?path='+encodeURIComponent(value.path));
    if(fresh.content!==value.content)throw Error('저장 직후 파일이 다시 변경되었습니다. 입력 내용은 유지됩니다. 다운로드로 현재 원본을 확인하세요.');
    setFile({...value,...fresh});
   }catch(e){throw Error('저장은 전송됐지만 결과를 확인하지 못했습니다. 입력 내용은 유지됩니다. '+(e as Error).message);}
  }},'파일을 저장했습니다.');
 }
 const rows=listing?.folder===folder&&!listError?listing.rows:[];
 const ready=!loading&&!listError&&listing?.folder===folder;
 const lastPage=Math.max(0,Math.ceil(rows.length/100)-1),shownPage=Math.min(page,lastPage);
 const disabled=busy||blocked||!canManage||!ready;
 async function upload(items:UploadItem[],target=folder){
  let done=0;await mutate(async()=>{for(const item of items){setProgress(`${done+1}/${items.length} · ${item.relative}`);const data=new FormData();data.append('file',item.file);
   try{await api(endpoint('upload')+'?path='+encodeURIComponent([target,...item.relative.split('/').slice(0,-1)].filter(Boolean).join('/')),'POST',data);done++;}
   catch(e){throw Error(`${done}/${items.length}개 업로드 완료. ${(e as Error).message}`);}
  }},`${items.length}개 파일을 업로드했습니다.`);
 }
 async function drop(e:DragEvent,target=folder){
  e.preventDefault();e.stopPropagation();setDragging(false);if(disabled)return;
  const raw=e.dataTransfer.getData('application/x-servermanager-path');
  if(raw){let source:{id:string;path:string};try{source=JSON.parse(raw);}catch{return;}
   if(source.id!==id){setError('다른 서버의 항목은 이동할 수 없습니다.');return;}
   const to=[target,source.path.split('/').pop()].filter(Boolean).join('/');
   if(to!==source.path)navigateWithUnsaved(()=>void mutate(async()=>{await api(endpoint('move'),'POST',{from:source.path,to});onEditor(null);},'파일을 이동했습니다.'));return;
  }
  try{const items=await droppedFiles(e.dataTransfer);navigateWithUnsaved(()=>void upload(items,target));}catch(e){setError((e as Error).message);}
 }
 const newFile=()=>navigateWithUnsaved(()=>{const name=prompt('새 텍스트 파일 이름');if(!name?.trim())return;const path=[folder,name.trim()].filter(Boolean).join('/');newPath.current=path;setFile({path,content:''});setReadError('');setReading(false);onEditor(path);});
 return <section className={'files-panel '+(dragging?'drag-active':'')} aria-label={name+' 파일 관리자'} onDragOver={e=>{e.preventDefault();if(!disabled)setDragging(true);}} onDragLeave={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node))setDragging(false);}} onDrop={e=>void drop(e)}>
  <SaveBar label="파일 작업" dirty={false} busy={busy} onSave={()=>{}} onReset={()=>{}}/>
  <div className="toolbar"><div className="actions"><button title="상위 폴더" aria-label="상위 폴더" disabled={!folder||busy} onClick={()=>go(folder.split('/').slice(0,-1).join('/'))} onDragOver={e=>e.preventDefault()} onDrop={e=>void drop(e,folder.split('/').slice(0,-1).join('/'))}><ArrowUp size={16}/></button><span className="breadcrumb file-toolbar-path">{name} / server / {folder}</span></div>
  <div className="actions"><button aria-label="파일 목록 새로고침" disabled={loading||busy} onClick={()=>void load()}><RefreshCw size={15}/></button><button disabled={disabled} onClick={()=>{const value=prompt('새 폴더 이름');if(value?.trim())void mutate(async()=>{await api(endpoint('folders'),'POST',{path:[folder,value.trim()].filter(Boolean).join('/')});},'폴더를 만들었습니다.');}}><Plus size={15}/> 폴더</button><button disabled={disabled} onClick={newFile}><FileText size={15}/> 파일</button><button disabled={disabled} onClick={()=>navigateWithUnsaved(()=>input.current?.click())}><Upload size={15}/> 업로드</button><input ref={input} aria-label="업로드할 파일" type="file" multiple hidden onChange={e=>{void upload(Array.from(e.target.files||[]).map(file=>({file,relative:file.name})));e.target.value='';}}/></div></div>
  {children}
  {error&&<div role="alert" className="alert error-banner">{error}<button onClick={()=>setError('')}>닫기</button></div>}{notice&&<p role="status" className="notice">{notice}</p>}
  <div className="drop-hint" role="status">{progress||(!canManage?'조회 전용입니다. 파일을 열거나 다운로드할 수 있습니다.':blocked?'실행기 연결 또는 파일 복구 상태를 확인하세요.':dragging?'여기에 놓아 업로드하거나 이동하세요':'파일·폴더를 끌어 놓아 업로드 · 목록의 항목을 폴더에 놓아 이동')}</div>
  {remote&&<p className="notice">실행기 원본에 직접 반영합니다. 실행 중인 프로그램이 파일을 다시 저장할 수 있으며, 모드 변경은 다음 서버 시작부터 적용됩니다.</p>}
  <div className="file-layout"><div className="file-list" aria-busy={loading}>
   <div className="file-head"><span>이름</span><span>크기</span><span>작업</span></div>
   {loading?<p className="file-feedback" role="status">파일 목록을 불러오는 중…</p>:listError?<div className="file-feedback" role="alert">{listError}<button onClick={()=>void load()}>파일 목록 다시 시도</button></div>:ready&&rows.length===0?<p className="no-files">비어 있는 폴더입니다.</p>:null}
   {ready&&rows.slice(shownPage*100,shownPage*100+100).map(f=><div key={f.path} className="file-row" draggable={!disabled} onDragStart={e=>e.dataTransfer.setData('application/x-servermanager-path',JSON.stringify({id,path:f.path}))} onDragOver={e=>{if(f.directory)e.preventDefault();}} onDrop={f.directory?e=>void drop(e,f.path):undefined}>
    <button className="file-name" disabled={busy} onClick={()=>open(f)}>{f.directory?<Folder size={16}/>:<FileText size={16}/>} {f.name}</button><span>{f.directory?'—':f.size<1024?f.size+' B':(f.size/1024).toFixed(1)+' KB'}</span><div className="file-actions">
    {!f.directory&&<a aria-label={f.name+' 다운로드'} title="다운로드" href={'/api'+endpoint('download')+'?path='+encodeURIComponent(f.path)}><Download size={14}/></a>}
    <button aria-label={f.name+' 이동 또는 이름 변경'} title="이동 또는 이름 변경" disabled={disabled} onClick={()=>navigateWithUnsaved(()=>{const to=prompt('server/ 기준 새 경로',f.path);if(to&&to!==f.path)void mutate(async()=>{await api(endpoint('move'),'POST',{from:f.path,to});onEditor(null);},'파일을 이동했습니다.');})}><Pencil size={14}/></button>
    <button aria-label={f.name+' 삭제'} title="삭제" disabled={disabled} onClick={()=>navigateWithUnsaved(()=>{if(confirm(`${name}의 ${f.path}을 휴지통으로 이동할까요?`))void mutate(async()=>{await api(endpoint('files')+'?path='+encodeURIComponent(f.path),'DELETE');onEditor(null);},'휴지통으로 이동했습니다.');})}><Trash2 size={14}/></button></div></div>)}
   {ready&&rows.length>100&&<div className="log-history"><button disabled={shownPage===0} onClick={()=>setPage(shownPage-1)}>이전 파일</button><span>{shownPage+1} / {lastPage+1} 페이지 · {rows.length}개</span><button disabled={shownPage===lastPage} onClick={()=>setPage(shownPage+1)}>다음 파일</button></div>}
  </div>
  {editorPath!==null&&(reading?<p className="file-feedback" role="status">파일을 읽는 중…</p>:readError?<div className="file-feedback" role="alert">{readError}<button onClick={()=>void read()}>파일 다시 읽기</button><a href={'/api'+endpoint('download')+'?path='+encodeURIComponent(editorPath)}>원본 다운로드</a><button onClick={()=>onEditor(null)}>닫기</button></div>:file&&file.path===editorPath?<FileEditor key={file.path+':'+file.revision} file={file} disabled={blocked||!canManage} busy={busy} onClose={()=>navigateWithUnsaved(()=>onEditor(null))} onSave={value=>void save(value)}/>:null)}
  </div><div className="panel-foot">{directory}</div>
 </section>;
}
