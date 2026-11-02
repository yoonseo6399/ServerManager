import {useEffect,useState} from 'react';
import {api} from './http';
import {Upload,Check,X} from 'lucide-react';
import type {UploadItem} from './dropFiles';
export type UploadFolderGroup={id:string;name:string;files:number;bytes:number};
export type UploadStatus={token:string;instanceId:string;filename:string;path:string;size:number;clientId:string;documentId?:string;phase:string;received:number;transferred:number;message:string;chunkSize:number;cancellable:boolean;revision:number;folderGroup?:UploadFolderGroup|null};
const stoppedFolders=new Set<string>(),runningFolders=new Set<string>();
export const folderUploadStopped=(id:string)=>stoppedFolders.has(id);
export function folderUploadRunning(id:string,running:boolean){if(running)runningFolders.add(id);else runningFolders.delete(id);listeners.forEach(f=>f());}
const documentId=crypto.randomUUID();
const terminal=(s:UploadStatus)=>['COMPLETE','CANCELLED','FAILED'].includes(s.phase);
let active:UploadStatus[]=[],client='',account='',generation=0;
const listeners=new Set<()=>void>(),sending=new Map<string,AbortController>();
function publish(status:UploadStatus,scope=generation){if(scope!==generation)return;if((active.find(s=>s.token===status.token)?.revision??-1)>status.revision)return;active=[...active.filter(s=>s.token!==status.token),status];listeners.forEach(f=>f());}
function clientId(){
 if(!client){try{client=sessionStorage.getItem('sm.upload-tab')||'';}catch{/* Storage can be disabled. */}if(!client){client=crypto.randomUUID();try{sessionStorage.setItem('sm.upload-tab',client);}catch{/* In-memory identity still works this visit. */}}}
 return client;
}
const endpoint=(id:string,token?:string)=>`/instances/${encodeURIComponent(id)}/file-uploads${token?'/'+encodeURIComponent(token):''}`;
const pause=()=>new Promise<void>(resolve=>setTimeout(resolve,700));
async function cancel(s:UploadStatus){
 const scope=generation;
 // Confirm server cancellation before aborting a browser request; never claim cancellation after commit began.
 const status=await api<UploadStatus>(endpoint(s.instanceId,s.token)+'/cancel','POST');publish(status,scope);
 if(status.phase==='CANCELLED')sending.get(s.token)?.abort();
 return status;
}
/** File contents use bounded HTTP requests. No browser request waits for the runner's entire transfer. */
export async function uploadManagedFile(id:string,path:string,item:UploadItem,folderGroup?:UploadFolderGroup){
 const scope=generation;const current=()=>{if(scope!==generation)throw Error('로그인 계정이 변경되어 브라우저 전송을 중단했습니다.');};
 let s=await api<UploadStatus>(endpoint(id),'POST',{filename:item.file.name,size:item.file.size,path,clientId:clientId(),documentId,folderGroup});current();publish(s,scope);
 const controller=new AbortController();sending.set(s.token,controller);
 try{
  while(s.received<s.size){
   current();
   if(active.find(v=>v.token===s.token)?.phase==='CANCELLED')throw Error('업로드가 취소되었습니다.');
   const chunk=item.file.slice(s.received,Math.min(s.size,s.received+s.chunkSize));
   s=await api<UploadStatus>(endpoint(id,s.token)+`/content?offset=${s.received}`,'PUT',chunk,controller.signal);
   // A cancel may have completed while this response was arriving.
   if(active.find(v=>v.token===s.token)?.phase==='CANCELLED')throw Error('업로드가 취소되었습니다.');
   current();publish(s,scope);
  }
  s=await api<UploadStatus>(endpoint(id,s.token)+'/complete','POST');current();publish(s,scope);
  while(!terminal(s)){
   await pause();current();s=await api<UploadStatus>(endpoint(id,s.token));current();publish(s,scope);
  }
  if(s.phase!=='COMPLETE')throw Error(s.message);
 }catch(e){
  if(scope===generation&&s.phase==='RECEIVING')await cancel(s).catch(()=>{});
  throw e;
 }finally{sending.delete(s.token);}
}
export function ManagedUploadProgress({instanceIds,owner}:{instanceIds:string[];owner?:string}){
 if(account!==owner){active=[];stoppedFolders.clear();runningFolders.clear();account=owner||'';generation++;}
 const [rows,setRows]=useState<UploadStatus[]>([]),[error,setError]=useState('');
 const key=instanceIds.slice().sort().join(',');
 useEffect(()=>{const fn=()=>setRows([...active]);listeners.add(fn);return()=>{listeners.delete(fn);};},[]);
 useEffect(()=>{
  let closed=false,timer:ReturnType<typeof setTimeout>;const scope=generation;
  const ids=key?key.split(','):[];const tabId=clientId();const reloaded=(performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming|undefined)?.type==='reload';const restored=new Set<string>();
  const load=async()=>{
   try{
    const fetched=(await Promise.all(ids.map(id=>api<UploadStatus[]>(endpoint(id))))).flat();
    for(let s of fetched){
     if(closed)return;
     // A fresh page cannot reuse File objects from the previous document. Only cancel this tab's abandoned receiver.
     if(s.phase==='RECEIVING'&&reloaded&&s.clientId===tabId&&s.documentId!==documentId&&!sending.has(s.token)&&!restored.has(s.token)){
      restored.add(s.token);s=await cancel(s);
     }
     if(!closed)publish(s,scope);
    }
    if(!closed)setError('');
   }catch(e){if(!closed)setError((e as Error).message);}
   finally{if(!closed)timer=setTimeout(load,1500);}
  };
  // Reset when login/permissions change so another user's previous results cannot remain on screen.
  active=active.filter(s=>ids.includes(s.instanceId));setRows(active);void load();
  return()=>{closed=true;clearTimeout(timer);};
 },[key]);
 async function act(s:UploadStatus,close=false){
  try{setError('');if(close){await api(endpoint(s.instanceId,s.token),'DELETE');active=active.filter(v=>v.token!==s.token);listeners.forEach(f=>f());}else await cancel(s);}
  catch(e){setError((e as Error).message);}
 }
 const groups=new Map<string,UploadStatus[]>();
 for(const row of rows)if(row.folderGroup){const key=row.instanceId+':'+row.folderGroup.id;groups.set(key,[...(groups.get(key)||[]),row]);}
 async function groupAction(items:UploadStatus[],close=false){
  const group=items[0].folderGroup!;
  if(!close){stoppedFolders.add(group.id);listeners.forEach(f=>f());}
  // Cancelling remaining files never pretends an APPLYING file can be undone.
  for(const item of items)if(close?terminal(item):item.cancellable)await act(item,close);
 }
 return <>{[...groups].map(([key,items])=>{
  const g=items[0].folderGroup!,busy=items.some(s=>!terminal(s))||runningFolders.has(g.id),failed=items.filter(s=>s.phase==='FAILED').length;
  const completed=items.filter(s=>s.phase==='COMPLETE').length,stopped=stoppedFolders.has(g.id)||items.some(s=>s.phase==='CANCELLED');
  const amount=items.reduce((sum,s)=>sum+(s.phase==='COMPLETE'?2*s.size:s.phase==='RECEIVING'?s.received:s.size+s.transferred),0);
  const percent=g.bytes?Math.min(100,Math.floor(100*amount/(2*g.bytes))):Math.floor(100*completed/g.files);
  const phase=items.some(s=>s.phase==='APPLYING')?'파일 반영 중':items.some(s=>s.phase==='TRANSFERRING')?'실행기에 전송 중':items.some(s=>s.phase==='QUEUED')?'실행기 반영 대기 중':'관리웹으로 전송 중';
  return <div key={key} className={'upload-progress managed-upload folder-upload'+(failed?' failed':'')} role="status"><div><strong>{completed===g.files?<Check size={15}/>:busy?<Upload size={15}/>:<X size={15}/>} 폴더 업로드 · {g.name}</strong>{!busy?<button aria-label="업로드 알림 닫기" onClick={()=>void groupAction(items,true)}><X size={14}/></button>:!stopped&&<button onClick={()=>void groupAction(items)}>취소</button>}</div><small>{stopped?'남은 파일 전송 중단 · 반영 중인 파일은 완료될 수 있습니다':busy?phase:completed===g.files?'폴더 업로드 완료':'폴더 업로드 부분 완료 · 미전송 파일은 다시 선택하세요'}</small><progress max={100} value={percent}/><small>{completed}/{g.files}개 완료 · {percent}%{failed?` · 실패 ${failed}개`:''}</small></div>;
 })}{rows.filter(s=>!s.folderGroup).map(s=>{
  const done=terminal(s),failed=s.phase==='FAILED',percent=s.size?Math.floor(100*(s.phase==='RECEIVING'?s.received:s.transferred)/s.size):done?100:0;
  return <div key={s.token} className={'upload-progress managed-upload'+(failed?' failed':'')} role="status"><div><strong>{s.phase==='COMPLETE'?<Check size={15}/>:done?<X size={15}/>:<Upload size={15}/>} 파일 업로드</strong>{done?<button aria-label="업로드 알림 닫기" onClick={()=>void act(s,true)}><X size={14}/></button>:s.cancellable&&<button onClick={()=>void act(s)}>취소</button>}</div><small>{s.filename}</small><small>{s.message}</small><progress max={100} value={s.phase==='COMPLETE'?100:percent}/><small>{s.phase==='RECEIVING'?'관리웹으로 전송':'실행기에 전송'} · {percent}% · {(s.phase==='RECEIVING'?s.received:s.transferred).toLocaleString()} / {s.size.toLocaleString()} B</small></div>;
 })}{error&&rows.length>0&&<div className="upload-progress failed" role="alert">{error}</div>}</>;
}
