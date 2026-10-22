import {useEffect,useRef,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
type Status={supported:boolean;bootId:string;stage:string;canRestart:boolean;reason:string|null;message:string};
const key='sm.manager.restart';
type Pending={bootId:string;at:number};
function saved():Pending|null {try{const value=JSON.parse(sessionStorage.getItem(key)||'null');return value&&typeof value.bootId==='string'&&Date.now()-value.at<120000?value:null;}catch{return null;}}
export function ServiceRestartPanel(){
 const auth=useAuth(),[status,setStatus]=useState<Status|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('');
 const pending=useRef<Pending|null>(saved());
 useEffect(()=>{
  let closed=false,timer:ReturnType<typeof setTimeout>;let polling=false;
  const poll=async()=>{
   if(polling)return;polling=true;
   try {
    if(pending.current){
     if(Date.now()-pending.current.at>120000){pending.current=null;sessionStorage.removeItem(key);setError('재연결 확인 시간이 초과됐습니다. 관리 앱 로그를 확인하세요.');setBusy(false);}
     else {
      const info=await api<{bootId?:string}>('/auth/session');
      if(info.bootId&&info.bootId!==pending.current.bootId){pending.current=null;sessionStorage.removeItem(key);setBusy(false);setMessage('관리 앱이 새 프로세스로 다시 연결됐습니다. 로그인이 풀렸다면 패스키로 다시 로그인하세요.');}
      else {setBusy(true);setMessage('관리 앱 재시작·재연결 확인 중…');}
     }
    } else {const value=await api<Status>('/admin/service-restart');if(!closed&&value.bootId)setStatus(value);}
   }catch(e){if(!closed){if(pending.current)setMessage('관리웹 연결이 잠시 끊겼습니다. 재연결을 기다립니다…');else setError((e as Error).message);}}
   finally {polling=false;if(!closed)timer=setTimeout(poll,1500);}
  };poll();return()=>{closed=true;clearTimeout(timer);};
 },[]);
 async function restart(){
  if(!status||!confirm('관리 앱을 재시작할까요? 관리웹·Discord 봇 연결이 잠시 끊깁니다. 유지 가능한 상주 게임만 실행 중일 때 허용하며, 파일 전송·준비 작업 중에는 거부합니다.'))return;
  setBusy(true);setError('');
  try{
   let value:Status;
   try {value=await api<Status>('/admin/service-restart','POST',{});}
   catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);value=await api<Status>('/admin/service-restart','POST',{});}
   const request={bootId:value.bootId,at:Date.now()};pending.current=request;sessionStorage.setItem(key,JSON.stringify(request));setStatus(value);setMessage('재시작을 요청했습니다. 관리웹 재연결을 확인합니다…');
  }catch(e){setError((e as Error).message);setBusy(false);}
 }
 return <section aria-label="관리 앱 재시작"><h2>관리 앱 재시작</h2><p>현재 버전으로 관리웹·봇 제어 프로그램을 다시 실행합니다. 운영체제나 게임 상주 서비스를 재부팅하지 않습니다.</p>{error&&<p role="alert" className="error-banner">{error}</p>}<p role="status">{message||status?.message||'재시작 지원 여부를 확인하고 있습니다.'}</p><button disabled={busy||!status?.supported||!status.canRestart} onClick={restart}>관리 앱 재시작</button></section>;
}
