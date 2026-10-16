import {memo,useEffect,useState} from 'react';
import {CheckCircle2,AlertTriangle,Loader2,FolderCheck} from 'lucide-react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

export type FolderProgress={phase:string;files:number;bytes:number;totalFiles:number;totalBytes:number;message:string;direction?:string;bytesPerSecond?:number|null;remainingSeconds?:number|null;elapsedSeconds?:number|null;step?:string;stepNo?:number;stepTotal?:number;reused?:number;hashed?:number};
export type FolderSource={state:string;path:string};
const size=(bytes:number)=>bytes>=1024**3?(bytes/1024**3).toFixed(2)+' GiB':(bytes/1024**2).toFixed(1)+' MiB';
const rate=(bytes:number)=>bytes<1024**2?(bytes/1024).toFixed(1)+' KiB/s':size(bytes)+'/s';
const stages=['SCANNING','UPLOADING','VERIFYING'];
const labels:Record<string,string>={SCANNING:'전송 준비 중',UPLOADING:'변경 파일 전송 중',VERIFYING:'검증·반영 중',COMPLETE:'전송 완료',FAILED:'전송 실패',RETRYING:'연결 복구 후 이어받기 대기'};
export const TransferStatus=memo(function TransferStatus({value}:{value:FolderProgress}){
 const [dismissed,setDismissed]=useState(false);
 // Polling returns a new object every two seconds. Only phase changes may reset this timer.
 useEffect(()=>{setDismissed(false);if(value.phase!=='COMPLETE')return;const timer=setTimeout(()=>setDismissed(true),3000);return()=>clearTimeout(timer);},[value.phase]);
 if(value.phase==='COMPLETE'&&dismissed)return null;
 const working=stages.includes(value.phase)||value.phase==='RETRYING',complete=value.phase==='COMPLETE',failed=value.phase==='FAILED';
 const Icon=complete?CheckCircle2:failed?AlertTriangle:Loader2;
 return <div className={'transfer-card '+(complete?'is-complete':failed?'is-failed':'is-working')} role="status" aria-live="polite" aria-label="서버 파일 전송 상태" aria-busy={working}>
  <div className="transfer-heading"><span className="transfer-icon"><Icon size={20} className={working?'transfer-spinner':''}/></span><div><strong>{labels[value.phase]||'전송 상태 확인 중'}</strong><div className="transfer-direction">{value.direction==='TO_RUNNER'?'관리 서버':'실행기'} <span aria-hidden="true">→</span> {value.direction==='TO_RUNNER'?'실행기':'관리 서버'}</div></div>{value.phase==='UPLOADING'&&value.totalBytes>0&&<span className="transfer-percent">{Math.min(100,value.bytes/value.totalBytes*100).toFixed(1)}%</span>}</div>
  {working&&<>
   {value.phase==='SCANNING'&&!!value.stepTotal&&<p className="transfer-prep" aria-label="전송 준비 단계"><strong>준비 {value.stepNo}/{value.stepTotal}단계</strong> · {value.step}</p>}
   <p className="transfer-counts">{value.phase==='SCANNING'?`${value.files.toLocaleString()}개 확인 · ${size(value.bytes)}`+((value.reused||value.hashed)?` · 이전 결과 재사용 ${(value.reused||0).toLocaleString()}개 · 새로 읽음 ${(value.hashed||0).toLocaleString()}개`:''):`${value.files.toLocaleString()} / ${value.totalFiles.toLocaleString()}개 · ${size(value.bytes)} / ${size(value.totalBytes)}`}</p>
   {value.totalBytes>0&&value.phase==='UPLOADING'&&<progress aria-label="서버 파일 전송 진행률" max={value.totalBytes} value={value.bytes}/>}
   <ol className="transfer-stages" aria-label="파일 전송 단계">{['파일 목록 준비','변경 파일 전송','검증·반영'].map((label,index)=><li key={label} className={index<=stages.indexOf(value.phase)?'reached':''} aria-current={index===stages.indexOf(value.phase)?'step':undefined}><span/>{label}</li>)}</ol>
  </>}
  {working&&<div className="transfer-estimates" aria-label="전송 속도와 예상 시간"><span>전송 속도 <strong>{value.phase==='RETRYING'?'연결 대기':value.phase==='VERIFYING'?'전송 완료':value.phase==='SCANNING'?(value.step||'전송 준비 중'):value.bytesPerSecond==null?'계산 중':rate(value.bytesPerSecond)}</strong></span><span>예상 남은 시간 <strong>{value.phase==='RETRYING'?'연결 대기':value.phase==='VERIFYING'?'검증·반영 중':value.phase==='SCANNING'?'파일 검사 중':value.remainingSeconds==null?'계산 중':value.remainingSeconds>=3600?`${Math.floor(value.remainingSeconds/3600)}시간 ${Math.floor(value.remainingSeconds%3600/60)}분`:value.remainingSeconds>=60?`${Math.floor(value.remainingSeconds/60)}분 ${value.remainingSeconds%60}초`:`${value.remainingSeconds}초`}</strong></span><small>전송 기준 · 검증·반영 시간은 별도입니다.</small></div>}
  {value.message&&<p className="transfer-message">{value.message}</p>}
 </div>;
});
export const FolderImportPanel=memo(function FolderImportPanel({id,source,progress,canManage,disabled,onRequested}:{id:string;source?:FolderSource|null;progress?:FolderProgress|null;canManage:boolean;disabled:boolean;onRequested:()=>Promise<void>}){
 const auth=useAuth();const[busy,setBusy]=useState(false),[error,setError]=useState('');
 const working=!!progress&&(stages.includes(progress.phase)||progress.phase==='RETRYING'),complete=progress?.phase==='COMPLETE'||source?.state==='SYNCED';
 const start=async()=>{
  if(!confirm('실행기에 등록한 기존 폴더를 가져올까요?\n서버를 실행하지 않고 파일을 검증·전송합니다.\n관리 웹에 있던 파일은 백업한 뒤 기존 폴더의 파일로 교체합니다.\n20GB 같은 대용량 폴더는 시간이 걸릴 수 있습니다.'))return;
  setBusy(true);setError('');
  try{try{await api(`/instances/${id}/import-folder`,'POST');}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await api(`/instances/${id}/import-folder`,'POST');}await onRequested();}
  catch(e){setError((e as Error).message);}finally{setBusy(false);}
 };
 return <div className={'folder-import-card '+(complete?'is-linked':'')} aria-label="기존 서버 폴더 가져오기">
  <div className="folder-import-heading"><FolderCheck size={20}/><strong>{complete?'가져오기 완료':'실행기의 기존 폴더'}</strong></div>
  <p className="folder-import-path">{source?.path||'기존 폴더 등록만으로 파일이 자동 전송되지는 않습니다. 실행기에서 폴더를 등록하고 아래 가져오기를 실행하세요.'}</p>
  {complete&&!working&&<p>기존 폴더 연결 완료 · 파일은 정상 종료 시 동기화됩니다.</p>}
  {working&&<p>파일을 가져오고 있습니다. 위 전송 카드에서 진행 상황을 확인할 수 있습니다.</p>}
  {source?.state==='INVALID'&&<p role="alert">실행기에 등록한 폴더 경로를 확인하세요.</p>}
  {progress?.phase==='FAILED'&&<p role="alert">가져오기 실패 · {progress.message}</p>}
  {canManage&&!complete&&<button disabled={busy||disabled||working||source?.state==='INVALID'} onClick={start}>{progress?.phase==='FAILED'?'기존 폴더 다시 가져오기':'실행기의 기존 폴더 가져오기'}</button>}
  {!source&&!working&&<p className="muted">서버 사용 허용과 실행 위치 배정이 필요합니다. 이 기능은 실행기 beta.8 이상을 사용합니다.</p>}
  {error&&<p role="alert">{error}</p>}
 </div>;
});
