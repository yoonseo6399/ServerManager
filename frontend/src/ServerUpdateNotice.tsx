import {useEffect,useState} from 'react';
import {api} from './http';
import {waitLabel} from './updateWait';
type Comp={component:string;current:string;target:string|null;stage:string;waitReason:string|null;waitDetail?:string|null};
const names:Record<string,string>={RUNNER_CONTROL:'실행기',GAME_HOST:'게임 상주 서비스'};
/** Tells the server page that its computer is waiting to update, and why. Hidden when the caller cannot see the runner or nothing is waiting. */
export function ServerUpdateNotice({runnerId}:{runnerId:string|null}){
 const [waiting,setWaiting]=useState<Comp[]>([]);
 useEffect(()=>{
  setWaiting([]);if(!runnerId)return;let alive=true;
  const poll=async()=>{if(document.hidden)return;try{const s=await api<{available:boolean;components:Comp[]}>(`/runners/${encodeURIComponent(runnerId)}/update-status?after=2147483647`);if(alive)setWaiting(s.available?s.components.filter(c=>c.stage==='WAITING'&&c.component in names):[]);}catch{if(alive)setWaiting([]);}};
  poll();const timer=setInterval(poll,5000);return()=>{alive=false;clearInterval(timer);};
 },[runnerId]);
 if(!waiting.length)return null;
 return <p className="notice" role="status" data-testid="server-update-notice">업데이트 대기 중 · {waiting.map(c=>`${names[c.component]} ${c.current}${c.target?' → '+c.target:''}: ${c.waitDetail||waitLabel(c.waitReason)}`).join(' / ')}</p>;
}
