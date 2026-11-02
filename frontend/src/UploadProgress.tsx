import {useSyncExternalStore} from 'react';
import {Check,Upload,X} from 'lucide-react';
import {uploadFile} from './http';
import {ManagedUploadProgress,uploadManagedFile,folderUploadStopped,folderUploadRunning,type UploadFolderGroup} from './FileUploadJobs';
import type {UploadItem} from './dropFiles';
import './uploads.css';
type Job={id:number;label:string;file:string;total:number;done:number;failed:number;percent:number;state:'uploading'|'complete';message?:string;error?:boolean;href?:string;hidden?:boolean};
let jobs:Job[]=[],next=0;const listeners=new Set<()=>void>();
const publish=()=>listeners.forEach(fn=>fn());
export function UploadProgressBar({instanceIds=[],owner}:{instanceIds?:string[];owner?:string}){
 const rows=useSyncExternalStore(fn=>{listeners.add(fn);return()=>{listeners.delete(fn);};},()=>jobs);
 return <div className="upload-progress-stack" aria-label="파일 전송 진행 상황"><ManagedUploadProgress instanceIds={instanceIds} owner={owner}/>{rows.filter(job=>!job.hidden).map(job=><div className={'upload-progress'+(job.error?' failed':'')} key={job.id} role="status"><div><strong>{job.error?<X size={15}/>:job.state==='complete'?<Check size={15}/>:<Upload size={15}/>} {job.label}</strong>{job.state==='complete'&&<button aria-label="업로드 알림 닫기" onClick={()=>{jobs=jobs.filter(j=>j.id!==job.id);publish();}}><X size={14}/></button>}</div><small>{job.message||(job.state==='complete'?`완료 ${job.done}/${job.total}${job.failed?` · 실패 ${job.failed}`:''}`:`${job.done+job.failed+1}/${job.total} · ${job.file}`)}</small><progress max={100} value={job.percent}/>{job.state==='uploading'&&!job.message&&<small>{job.percent===100?'서버에서 파일 반영 중…':`${job.percent}% 전송`}</small>}{job.href&&<a href={job.href} download>ZIP 다운로드</a>}</div>)}</div>;
}
/** Global progress survives switching tabs; failures do not cancel the remaining files. */
export async function uploadBatch(label:string,items:UploadItem[],url:(item:UploadItem)=>string){
 const folders=new Map<string,UploadFolderGroup>();
 for(const item of items){const name=item.relative.includes('/')?item.relative.split('/')[0]:'';if(name){let g=folders.get(name);if(!g){g={id:crypto.randomUUID(),name,files:0,bytes:0};folders.set(name,g);}g.files++;g.bytes+=item.file.size;}}
 const managedFolderOnly=items.length>0&&items.every(item=>item.relative.includes('/')&&/^\/instances\/[^/]+\/upload(?:\?|$)/.test(url(item)));
 const id=++next;const row:Job={id,label,file:'',total:items.length,done:0,failed:0,percent:0,state:'uploading',hidden:managedFolderOnly};
 folders.forEach(g=>folderUploadRunning(g.id,true));
 jobs=[...jobs.filter(j=>j.state!=='complete').slice(-4),row];publish();
 const update=(patch:Partial<Job>)=>{Object.assign(row,patch);jobs=jobs.map(j=>j.id===id?{...row}:j);publish();};
 const errors:string[]=[];
 const cancelled=new Set<string>();
 try {for(const item of items){
  const group=item.relative.includes('/')?folders.get(item.relative.split('/')[0]):undefined;
  if(group&&folderUploadStopped(group.id)){if(!cancelled.has(group.id)){errors.push(`${group.name}: 남은 파일 전송이 취소되었습니다.`);cancelled.add(group.id);}update({failed:row.failed+1});continue;}
  update({file:group?.name||item.relative,percent:0});
  try{const target=url(item),managed=/^\/instances\/([^/]+)\/upload(?:\?|$)/.exec(target);
   if(managed){const query=new URLSearchParams(target.split('?')[1]||'');await uploadManagedFile(decodeURIComponent(managed[1]),query.get('path')||'',item,group);}
   else{const data=new FormData();data.append('file',item.file);await uploadFile(target,data,percent=>update({percent}));}
   update({done:row.done+1});
  }catch(e){errors.push(`${item.relative}: ${(e as Error).message}`);update({failed:row.failed+1});}
 }}finally{folders.forEach(g=>folderUploadRunning(g.id,false));}
 update({state:'complete',percent:100,error:errors.length>0});return {done:row.done,errors};
}

export function beginFileProgress(label:string){
 const id=++next;let row:Job={id,label,file:'',total:0,done:0,failed:0,percent:0,state:'uploading',message:'준비 중…'};jobs=[...jobs,row];publish();
 return (message:string,percent:number,complete=false,error=false,href?:string)=>{row={...row,message,percent,error,href:href??row.href,state:complete?'complete':'uploading'};jobs=jobs.map(j=>j.id===id?row:j);publish();};
}
