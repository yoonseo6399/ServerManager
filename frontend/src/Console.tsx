import React,{memo,startTransition,useCallback,useDeferredValue,useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {Terminal} from 'lucide-react';
import {api} from './http';
import {useBackClose} from './nav';
import {type ParsedLog,type RawLog} from './logParser';
import {useLogParser} from './useLogParser';
import {useLogFilters,LogFilterEditor,hiddenByFilter,type FilterConfig} from './LogFilters';
export {trimLogTime} from './logParser';
type Page={events:RawLog[];hasOlder?:boolean;nextOffset?:number|null;revision?:string};
type LogFile={path:string;name:string;size:number;compressed:boolean};
const PAGE=300,ROW=28;
const Command=memo(function Command({id,enabled,hint}:{id:string;enabled:boolean;hint:string}){
 const [value,setValue]=useState(''),[pending,setPending]=useState(false),[error,setError]=useState('');const lock=useRef(false);
 async function submit(e:React.FormEvent){e.preventDefault();if(!enabled||lock.current||!value.trim())return;const text=value.trim(),command=text.startsWith('/')?text.slice(1).trim():`say ${text}`;if(!command){setError('/ 뒤에 실행할 명령을 입력하세요.');return;}if(/[\r\n]/.test(command)||command.length>4096){setError('4096자 이내의 한 줄을 입력하세요.');return;}lock.current=true;setPending(true);setError('');const sent=value;setValue('');try{await api(`/instances/${id}/command`,'POST',{command});}catch(e){setError((e as Error).message);setValue(sent);}finally{lock.current=false;setPending(false);}}
 return <>{error&&<div role="alert" className="error-banner alert">{error}</div>}{!enabled&&<div className="console-state">{hint}</div>}<form className={'command-bar '+(!enabled?'command-disabled':'')} onSubmit={submit}><span>❯</span><input aria-label="콘솔 명령" disabled={!enabled||pending} placeholder={enabled?'채팅을 입력하세요 · 명령은 /list, /stop처럼 입력':hint} value={value} onChange={e=>setValue(e.target.value)}/><button disabled={!enabled||pending||!value.trim()}>보내기 ↵</button></form></>;
});
const clock=new Intl.DateTimeFormat('en-GB',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'});
const LogRow=memo(function LogRow({event:e,top,onOpen}:{event:ParsedLog;top:number;onOpen:(event:ParsedLog)=>void}){
 const time=e.time?clock.format(new Date(e.time)):(/^\[(?:\d{4}-\d{2}-\d{2}[ T])?(\d{2}:\d{2}:\d{2})/.exec(e.text)?.[1]||'—');
 return <div role="button" tabIndex={0} className={'log virtual-log '+e.kind} data-event-seq={e.seq} style={{position:'absolute',top,height:ROW,width:'100%'}} onClick={()=>onOpen(e)} onKeyDown={event=>{if(event.key==='Enter')onOpen(e);}}><time>{time}</time><span className="kind">{e.frame?'FRAME':e.kind==='input'?'명령 →':e.kind.toUpperCase()}</span><span className="log-text">{e.display}</span>{!!e.frame?.mixins.length&&<span className="mixin-badge">Mixins {e.frame.mixins.length}</span>}</div>;
});
function LogDetail({event,onClose}:{event:ParsedLog;onClose:()=>void}){
 const frame=event.frame;
 const groups=useMemo(()=>{const result=new Map<string,Map<string,NonNullable<typeof frame>['mixins']>>();for(const mixin of frame?.mixins||[]){if(!result.has(mixin.modId))result.set(mixin.modId,new Map());const configs=result.get(mixin.modId)!;if(!configs.has(mixin.config))configs.set(mixin.config,[]);configs.get(mixin.config)!.push(mixin);}return result;},[frame]);
 return <div className="modal-backdrop"><section role="dialog" aria-label="로그 상세" className="modal log-detail"><h2>{frame?`${frame.className.split('.').pop()}.${frame.method}()`:'로그 전문'}</h2>{frame&&<><p>{frame.source}</p><code>{frame.className}</code><p className="muted">{frame.module} {frame.origin}</p><h3>클래스에 연결된 Mixins · {frame.mixins.length}개</h3><p>이 목록만으로 해당 메서드를 직접 수정했는지 또는 오류 원인인지는 판단할 수 없습니다.</p>{!groups.size&&<p>이 프레임에 표시된 Mixin 정보가 없습니다.</p>}<div className="mixin-groups">{[...groups].map(([mod,configs])=><section key={mod}><h4>{mod}</h4>{[...configs].map(([config,mixins])=><div key={config}><code>{config}</code><ul>{mixins.map((m,i)=><li key={i}>{m.className} <small>{m.phase}</small></li>)}</ul></div>)}</section>)}</div>{!!frame.metadata.length&&<details><summary>기타 변환 정보</summary><pre>{frame.metadata.join('\n')}</pre></details>}</>}{frame?<details><summary>원문 보기</summary><pre>{event.text}</pre></details>:<pre>{event.text}</pre>}<button onClick={onClose}>닫기</button></section></div>;
}
export const ConsolePanel=memo(function ConsolePanel(props:{id:string;enabled:boolean;hint:string}){
 const filters=useLogFilters(props.id);
 const [source,setSource]=useState(''),[files,setFiles]=useState<LogFile[]>([]),[error,setError]=useState(''),[revision,setRevision]=useState(0);
 useEffect(()=>{let active=true;api<LogFile[]>(`/instances/${props.id}/log-files`).then(rows=>{if(active&&Array.isArray(rows)){setFiles(rows);setError('');}}).catch(e=>{if(active)setError(e.message);});return()=>{active=false;};},[props.id,revision]);
 return <><div className="log-source"><label>로그 파일<select aria-label="로그 파일" value={source} onChange={e=>setSource(e.target.value)}><option value="">실시간 콘솔</option>{files.map(file=><option key={file.path} value={file.path}>{file.name} · {(file.size/1024).toFixed(0)} KiB{file.compressed?' · 압축':''}</option>)}</select></label><button onClick={()=>setRevision(v=>v+1)}>로그 다시 불러오기</button><small>원문 파일만 저장 · 파싱 결과는 메모리에서만 처리</small></div>{error&&<div role="alert" className="alert error-banner">{error}</div>}<LogFilterEditor key={props.id} {...filters} onChange={filters.change} onSave={filters.save}/><ConsoleView key={`${props.id}:${source}:${revision}`} {...props} source={source} filters={filters.config}/></>;
});
function ConsoleView({id,enabled,hint,source,filters}:{id:string;enabled:boolean;hint:string;source:string;filters:FilterConfig}){
 const [events,setEvents]=useState<ParsedLog[]>([]),[filter,setFilter]=useState(''),[errorsOnly,setErrorsOnly]=useState(false),[follow,setFollow]=useState(!source),[hasOlder,setHasOlder]=useState(false),[loading,setLoading]=useState(false),[pending,setPending]=useState(true),[error,setError]=useState(''),[top,setTop]=useState(0),[height,setHeight]=useState(420),[detail,setDetail]=useState<ParsedLog|null>(null),[fileNav,setFileNav]=useState({previous:false,next:false,page:1});
 useBackClose(!!detail,()=>setDetail(null),'log-detail');
 const {parse,progress}=useLogParser(),openDetail=useCallback((event:ParsedLog)=>setDetail(event),[]);
 const viewport=useRef<HTMLDivElement>(null),cursor=useRef<number|null>(null),busy=useRef(false),alive=useRef(true),anchor=useRef<number|null>(null),followRef=useRef(!source);
 const file=useRef<{offset:number;next:number|null;revision?:string;history:number[]}>({offset:0,next:null,history:[]});
 const appliedFilters=useDeferredValue(filters);
 const query=useDeferredValue(filter.toLowerCase());followRef.current=follow;
 async function load(mode:'initial'|'poll'|'older'|'next'|'previous'){
  if(busy.current)return;busy.current=true;
  if(mode!=='poll')setPending(true);
  let wait:ReturnType<typeof setTimeout>|undefined;
  try{
   let url=`/instances/${id}/event-page?limit=${PAGE}`;
   let offset=file.current.offset;
   if(source){if(mode==='next')offset=file.current.next!;if(mode==='previous')offset=file.current.history.at(-1)!;url=`/instances/${id}/log-file-page?path=${encodeURIComponent(source)}&limit=${PAGE}&offset=${offset}${file.current.revision?'&revision='+encodeURIComponent(file.current.revision):''}`;}
   else if(mode==='older')url+='&before='+events[0].seq;
   else if(cursor.current!==null)url+='&after='+cursor.current;
   const result=await api<Page>(url);if(!alive.current)return;
   const incoming=!source&&mode==='poll'&&cursor.current!==null?result.events.filter(event=>event.seq>cursor.current!):result.events;
   let parsed:ParsedLog[]=[];
   if(incoming.length){wait=setTimeout(()=>{if(alive.current)setLoading(true);},500);parsed=await parse(incoming);clearTimeout(wait);if(!alive.current)return;setLoading(false);}
   setError('');
   if(source){if(mode==='next')file.current.history.push(file.current.offset);if(mode==='previous')file.current.history.pop();file.current.offset=offset;file.current.next=result.nextOffset??null;file.current.revision=result.revision;setFileNav({previous:!!file.current.history.length,next:result.nextOffset!=null,page:file.current.history.length+1});setEvents(parsed);if(viewport.current)viewport.current.scrollTop=0;setTop(0);}
   else if(mode==='older'){anchor.current=rows.length*ROW-(viewport.current?.scrollTop||0);setHasOlder(!!result.hasOlder);setEvents(prev=>{const seen=new Set(prev.map(e=>e.seq));return [...parsed.filter(e=>!seen.has(e.seq)),...prev];});}
   else {if(cursor.current===null)setHasOlder(!!result.hasOlder);if(parsed.length){cursor.current=parsed.at(-1)!.seq;startTransition(()=>setEvents(prev=>{const seen=new Set(prev.map(e=>e.seq));return [...prev,...parsed.filter(e=>!seen.has(e.seq))].slice(-10000);}));}}
  }catch(e){if(alive.current)setError((e as Error).message);}finally{clearTimeout(wait);busy.current=false;if(alive.current){setLoading(false);if(mode!=='poll')setPending(false);}}
 }
 useEffect(()=>{alive.current=true;load('initial');const timer=source?undefined:setInterval(()=>{if(!document.hidden)load('poll');},1000);return()=>{alive.current=false;clearInterval(timer);};},[id,source]);
 useEffect(()=>{if(!viewport.current)return;const observer=new ResizeObserver(entries=>setHeight(entries[0].contentRect.height));observer.observe(viewport.current);return()=>observer.disconnect();},[]);
 const rows=useMemo(()=>events.filter(e=>(!errorsOnly||['warn','error'].includes(e.kind)||['WARN','ERROR','FATAL'].includes(e.level||'')||!!e.frame)&&e.search.includes(query)&&!hiddenByFilter(e,appliedFilters)),[events,errorsOnly,query,appliedFilters]);
 useLayoutEffect(()=>{const v=viewport.current;if(!v)return;if(anchor.current!==null){v.scrollTop=Math.max(0,rows.length*ROW-anchor.current);anchor.current=null;}else if(followRef.current)v.scrollTop=v.scrollHeight;setTop(v.scrollTop);},[rows]);
 const start=Math.max(0,Math.floor(top/ROW)-8),end=Math.min(rows.length,Math.ceil((top+height)/ROW)+8);
 return <section className="console-panel"><div className="toolbar"><div className="panel-title"><span className="green-dot"/>{source?'로그 파일':'실시간 콘솔'} <span className="muted">{events.length.toLocaleString()}줄 로드됨</span></div><div className="actions"><input aria-label="로그 검색" placeholder="메서드·모드·로그 검색…" value={filter} onChange={e=>{setFilter(e.target.value);setFollow(false);setTop(0);if(viewport.current)viewport.current.scrollTop=0;}}/><label><input type="checkbox" checked={errorsOnly} onChange={e=>setErrorsOnly(e.target.checked)}/>오류·경고</label><label><input type="checkbox" checked={follow} onChange={e=>{setFollow(e.target.checked);if(e.target.checked&&viewport.current)viewport.current.scrollTop=viewport.current.scrollHeight;}}/>자동 스크롤</label></div></div>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}<div className="log-history">{source?<><button disabled={!fileNav.previous||pending} onClick={()=>load('previous')}>이전 페이지</button><span>{fileNav.page} 페이지</span><button disabled={!fileNav.next||pending} onClick={()=>load('next')}>다음 로그 300줄</button></>:<button disabled={!hasOlder||pending} onClick={()=>{setFollow(false);load('older');}}>이전 로그 300줄 불러오기</button>}<span>검색·필터는 로드된 로그에 적용 · {rows.length.toLocaleString()}줄 표시 · 프레임을 누르면 Mixin 상세 보기</span></div>
 <div className="log-viewport-shell">{loading&&<div className="log-loading" role="status" aria-live="polite"><span className="log-spinner"/>{progress||'로그 파싱 중…'}<small>긴 로그도 페이지 단위로 처리합니다.</small></div>}
 <div className="logs virtual-logs" aria-busy={pending||loading} ref={viewport} data-testid="log-viewport" onScroll={e=>{const v=e.currentTarget;setTop(v.scrollTop);if(v.scrollHeight-v.scrollTop-v.clientHeight>ROW*2)setFollow(false);}}>{!rows.length?<div className="console-empty"><Terminal size={28}/><p>{pending?'로그를 준비하고 있습니다.':events.length?'현재 검색·필터에 맞는 로그가 없습니다.':'표시할 로그가 없습니다.'}</p></div>:<div style={{height:rows.length*ROW,position:'relative'}}>{rows.slice(start,end).map((event,offset)=><LogRow key={event.seq} event={event} top={(start+offset)*ROW} onOpen={openDetail}/>)}</div>}</div>
 </div>
 {!source&&<Command key={id} id={id} enabled={enabled} hint={hint}/>}<div className="panel-foot">{source?'저장된 원문 로그를 조회하고 있습니다. 변경된 파일은 다시 불러오세요.':'일반 입력은 서버 채팅으로 전송 · /로 시작하면 명령 실행'}<span>파싱 결과는 디스크에 저장하지 않습니다.</span></div>
 {detail&&<LogDetail event={detail} onClose={()=>setDetail(null)}/>}
 </section>;
}
