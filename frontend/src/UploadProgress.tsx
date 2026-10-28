import {useSyncExternalStore} from 'react';
import {Check,Upload,X} from 'lucide-react';
import {uploadFile} from './http';
import type {UploadItem} from './dropFiles';
import './uploads.css';
type Job={id:number;label:string;file:string;total:number;done:number;failed:number;percent:number;state:'uploading'|'complete';message?:string;error?:boolean};
let jobs:Job[]=[],next=0;const listeners=new Set<()=>void>();
const publish=()=>listeners.forEach(fn=>fn());
export function UploadProgressBar(){
 const rows=useSyncExternalStore(fn=>{listeners.add(fn);return()=>{listeners.delete(fn);};},()=>jobs);
 return <div className="upload-progress-stack" aria-label="파일 전송 진행 상황">{rows.map(job=><div className={'upload-progress'+(job.error?' failed':'')} key={job.id} role="status"><div><strong>{job.error?<X size={15}/>:job.state==='complete'?<Check size={15}/>:<Upload size={15}/>} {job.label}</strong>{job.state==='complete'&&<button aria-label="업로드 알림 닫기" onClick={()=>{jobs=jobs.filter(j=>j.id!==job.id);publish();}}><X size={14}/></button>}</div><small>{job.message||(job.state==='complete'?`완료 ${job.done}/${job.total}${job.failed?` · 실패 ${job.failed}`:''}`:`${job.done+job.failed+1}/${job.total} · ${job.file}`)}</small><progress max={100} value={job.percent}/>{job.state==='uploading'&&!job.message&&<small>{job.percent===100?'서버에서 파일 반영 중…':`${job.percent}% 전송`}</small>}</div>)}</div>;
}
/** Global progress survives switching tabs; failures do not cancel the remaining files. */
export async function uploadBatch(label:string,items:UploadItem[],url:(item:UploadItem)=>string){
 const id=++next;const row:Job={id,label,file:'',total:items.length,done:0,failed:0,percent:0,state:'uploading'};
 jobs=[...jobs.filter(j=>j.state!=='complete').slice(-4),row];publish();
 const update=(patch:Partial<Job>)=>{Object.assign(row,patch);jobs=jobs.map(j=>j.id===id?{...row}:j);publish();};
 const errors:string[]=[];
 for(const item of items){update({file:item.relative,percent:0});try{const data=new FormData();data.append('file',item.file);await uploadFile(url(item),data,percent=>update({percent}));update({done:row.done+1});}catch(e){errors.push(`${item.relative}: ${(e as Error).message}`);update({failed:row.failed+1});}}
 update({state:'complete',percent:100,error:errors.length>0});return {done:row.done,errors};
}

export function beginFileProgress(label:string){
 const id=++next;let row:Job={id,label,file:'',total:0,done:0,failed:0,percent:0,state:'uploading',message:'준비 중…'};jobs=[...jobs,row];publish();
 return (message:string,percent:number,complete=false,error=false)=>{row={...row,message,percent,error,state:complete?'complete':'uploading'};jobs=jobs.map(j=>j.id===id?row:j);publish();};
}
