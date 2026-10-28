import {useState} from 'react';
import {Download} from 'lucide-react';
import {api} from './http';
import {beginFileProgress} from './UploadProgress';
type Status={token:string;phase:string;message:string;files:number;completed:number;bytes:number;totalBytes:number;source?:string;copiedAt?:string|null};
export function FolderDownloadButton({id,path,name,snapshot=false}:{id:string;path:string;name:string;snapshot?:boolean}){
 const [busy,setBusy]=useState(false);
 async function download(){
  if(busy)return;setBusy(true);const progress=beginFileProgress(`${name} · ${snapshot?"최근 복사본":"원본"} ZIP`);
  try{const endpoint=`/instances/${id}/folder-downloads`;let status=await api<Status>(endpoint,'POST',{path,source:snapshot?'snapshot':'current'});
   while(status.phase==='WORKING'){progress(`${status.completed}/${status.files}개 · ${(status.bytes/1048576).toFixed(1)}/${(status.totalBytes/1048576).toFixed(1)} MiB 압축 중`,status.totalBytes?Math.min(99,Math.round(status.bytes/status.totalBytes*100)):0);await new Promise(resolve=>setTimeout(resolve,700));status=await api<Status>(`${endpoint}/${status.token}`);}
   if(status.phase!=='READY')throw Error(status.message);
   const link=document.createElement('a');link.href='/api'+endpoint+'/'+status.token+'/download';link.download=name+'.zip';document.body.appendChild(link);link.click();link.remove();
   progress(`${snapshot?'중앙 복사본 · '+(status.copiedAt?new Date(status.copiedAt).toLocaleString():'복사 시각 미기록 · 최신 실행기 상태와 다를 수 있음')+' · ':''}ZIP 준비 완료. 다운로드 진행은 브라우저에서 확인하세요.`,100,true);
  }catch(e){progress((e as Error).message,0,true,true);}finally{setBusy(false);}
 }
 return <button aria-label={`${name} ${snapshot?"최근 복사본 ":""}ZIP 다운로드`} title={snapshot?"가장 최근 중앙 복사본 ZIP · 실행기가 오프라인이어도 사용 가능":"폴더 ZIP 다운로드 · 실행 중인 월드는 정지 후 내려받기를 권장합니다"} disabled={busy} onClick={()=>void download()}><Download size={14}/>{snapshot&&<small>복사본</small>}</button>;
}
