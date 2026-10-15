import {useEffect,useRef,useState} from 'react';
import {api} from './http';
type Event={seq:number;time:string;level:string;component:string;message:string};
type Batch={events:Event[];nextCursor:number;oldestCursor:number;reset:boolean;sessionId:string};
export function SystemLogPanel(){
 const [events,setEvents]=useState<Event[]>([]),[level,setLevel]=useState('ALL'),[component,setComponent]=useState(''),[query,setQuery]=useState(''),[since,setSince]=useState(''),[live,setLive]=useState(true),[error,setError]=useState(''),[loading,setLoading]=useState(false),[truncated,setTruncated]=useState(false);
 const cursor=useRef(0),session=useRef(''),liveRef=useRef(live);liveRef.current=live;
 useEffect(()=>{
  let closed=false,loadingNow=false;cursor.current=0;session.current='';setEvents([]);setTruncated(false);setError('');
  async function load(initial=false){if(closed||loadingNow||document.hidden||(!initial&&!liveRef.current))return;loadingNow=true;setLoading(true);
   try{const params=new URLSearchParams({after:String(cursor.current),limit:'200',level,component,query});if(since)params.set('since',new Date(since).toISOString());let batch=await api<Batch>('/admin/system-logs?'+params);if(closed)return;
    if(session.current&&session.current!==batch.sessionId){params.set('after','0');batch=await api<Batch>('/admin/system-logs?'+params);if(closed)return;}
    const reset=batch.reset||(!!session.current&&session.current!==batch.sessionId);session.current=batch.sessionId;cursor.current=batch.nextCursor;
    setEvents(old=>{const merged=[...(reset?[]:old),...batch.events];return [...new Map(merged.map(e=>[e.seq,e])).values()].slice(-1000);});setTruncated(old=>old||reset||batch.oldestCursor>1);setError('');
   }catch(e){if(!closed)setError((e as Error).message);}finally{loadingNow=false;if(!closed)setLoading(false);}
  }
  load(true);const timer=setInterval(()=>load(),2000);const visible=()=>{load();};document.addEventListener('visibilitychange',visible);
  return()=>{closed=true;if(timer)clearInterval(timer);document.removeEventListener('visibilitychange',visible);};
 },[level,component,query,since]);
 function download(){const blob=new Blob([events.map(e=>`${e.time} ${e.level} [${e.component}] ${e.message}`).join('\n')],{type:'text/plain;charset=utf-8'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='server-manager-logs.txt';a.click();setTimeout(()=>URL.revokeObjectURL(url),0);}
 return <section aria-label="관리 앱 로그"><h2 className="spaced">관리 앱 로그</h2><p>현재 관리 앱 실행 중 수집한 기록입니다. 이전 실행 기록은 포함하지 않습니다. 화면에는 최대 1,000개를 표시합니다.</p><div className="form-grid"><label>수준<select value={level} onChange={e=>setLevel(e.target.value)}><option value="ALL">전체</option>{['INFO','WARN','ERROR'].map(v=><option key={v}>{v}</option>)}</select></label><label>구성요소<input value={component} onChange={e=>setComponent(e.target.value)}/></label><label>내용 검색<input value={query} onChange={e=>setQuery(e.target.value)}/></label><label>조회 시작 시간<input type="datetime-local" value={since} onChange={e=>setSince(e.target.value)}/></label></div><div className="actions"><button onClick={()=>setLive(v=>!v)}>{live?'실시간 보기 일시정지':'실시간 보기 재개'}</button><button disabled={!events.length} onClick={download}>표시된 로그 내보내기</button></div>{error&&<p role="alert">{error}</p>}<p role="status">{loading?'로그를 확인하고 있습니다…':live?'실시간 확인 중':'일시정지됨'}{truncated?' · 보관 범위 밖의 기록은 생략되었습니다.':''}</p>{!events.length&&!loading&&!error&&<p>조건에 맞는 로그가 없습니다.</p>}<pre className="console" style={{maxHeight:420,overflow:'auto',whiteSpace:'pre-wrap'}} aria-label="관리 앱 로그 내용">{events.map(e=>`${new Date(e.time).toLocaleString()} ${e.level} [${e.component}] ${e.message}`).join('\n')}</pre></section>;
}
