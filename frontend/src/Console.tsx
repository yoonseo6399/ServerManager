import React,{memo,startTransition,useCallback,useDeferredValue,useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {Terminal} from 'lucide-react';
import {api} from './http';
import {useBackClose} from './nav';
import {type ParsedLog,type RawLog} from './logParser';
import {useLogParser} from './useLogParser';
import {useLogFilters,LogFilterEditor,hiddenByFilter,type FilterConfig} from './LogFilters';
export {trimLogTime} from './logParser';
type Win={lines:{no:number;kind:string;text:string}[];total:number;first:number;last:number;next:number|null};
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
 return <div role="button" tabIndex={0} className={'log virtual-log '+e.kind} data-event-seq={e.seq} style={{position:'absolute',top,height:ROW,width:'100%'}} onClick={()=>onOpen(e)} onKeyDown={event=>{if(event.key==='Enter')onOpen(e);}}><span className="lineno">{e.seq}</span><time>{time}</time><span className="kind">{e.frame?'FRAME':e.kind==='input'?'명령 →':e.kind.toUpperCase()}</span><span className="log-text">{e.display}</span>{!!e.frame?.mixins.length&&<span className="mixin-badge">Mixins {e.frame.mixins.length}</span>}</div>;
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
 const path=source||'console.log',live=!source;
 const [filter,setFilter]=useState(''),[errorsOnly,setErrorsOnly]=useState(false),[follow,setFollow]=useState(live),[loading,setLoading]=useState(false),[pending,setPending]=useState(true),[error,setError]=useState(''),[top,setTop]=useState(0),[height,setHeight]=useState(420),[detail,setDetail]=useState<ParsedLog|null>(null);
 const [total,setTotal]=useState(0),[tick,setTick]=useState(0),[matchEnd,setMatchEnd]=useState<number|null>(null);
 useBackClose(!!detail,()=>setDetail(null),'log-detail');
 const {parse,progress}=useLogParser(),openDetail=useCallback((event:ParsedLog)=>setDetail(event),[]);
 const viewport=useRef<HTMLDivElement>(null),alive=useRef(true),followRef=useRef(live),busy=useRef(false),visibleRows=useRef(0);
 // Whole-file mode keeps a sparse cache keyed by line number; search mode keeps the matches found so far, in order.
 const cache=useRef(new Map<number,ParsedLog>()),requested=useRef(new Set<number>()),matches=useRef<ParsedLog[]>([]),scan=useRef<{from:number|null;generation:number}>({from:1,generation:0});
 const query=useDeferredValue(filter.trim());followRef.current=follow;
 const appliedFilters=useDeferredValue(filters);
 const searching=!!query||errorsOnly||filters.enabled;
 const url=(from:number,limit:number,q?:string)=>`/instances/${id}/log-window?path=${encodeURIComponent(path)}&from=${from}&limit=${limit}${q?'&q='+encodeURIComponent(q):''}`;
 const refresh=()=>setTick(v=>v+1);
 async function fetchWindow(from:number,limit:number,q?:string){
  const win=await api<Win>(url(from,limit,q));if(!alive.current)return null;
  const raw:RawLog[]=win.lines.map(l=>({seq:l.no,time:null,kind:l.kind,text:l.text}));let parsed:ParsedLog[]=[];
  if(raw.length){const wait=setTimeout(()=>{if(alive.current)setLoading(true);},500);try{parsed=await parse(raw);}finally{clearTimeout(wait);if(alive.current)setLoading(false);}}
  return alive.current?{win,parsed}:null;
 }
 // Reset when the file, the search text or the filter mode changes.
 useEffect(()=>{
  alive.current=true;cache.current=new Map();requested.current=new Set();matches.current=[];scan.current={from:1,generation:scan.current.generation+1};setMatchEnd(null);setPending(true);setError('');
  const generation=scan.current.generation;
  (async()=>{
   try{
    if(searching){const r=await fetchWindow(1,PAGE,query||undefined);if(!r||scan.current.generation!==generation)return;matches.current=r.parsed;scan.current.from=r.win.next;setTotal(r.win.total);setMatchEnd(r.win.next===null?r.win.total:null);refresh();}
    else{
     const probe=await api<Win>(url(1,1));if(!alive.current||scan.current.generation!==generation)return;setTotal(probe.total);
     const start=live?Math.max(1,probe.total-PAGE+1):1;requested.current.add(Math.floor((start-1)/PAGE));
     const r=await fetchWindow(start,PAGE);if(!r||scan.current.generation!==generation)return;for(const e of r.parsed)cache.current.set(e.seq,e);setTotal(r.win.total);refresh();
    }
   }catch(e){if(alive.current)setError((e as Error).message);}finally{if(alive.current)setPending(false);}
  })();
  return()=>{alive.current=false;};
 },[id,path,query,searching]);
 // Dynamic loading: whole-file mode fills the chunks in view; search mode keeps scanning while the end is in view.
 useEffect(()=>{
  if(pending||busy.current||error)return;
  const generation=scan.current.generation;
  (async()=>{
   busy.current=true;
   try{
    if(!searching){
     const first=Math.max(0,Math.floor(top/ROW)-PAGE/4),last=Math.min(total-1,Math.ceil((top+height)/ROW)+PAGE/4);
     for(let chunk=Math.floor(first/PAGE);chunk<=Math.floor(last/PAGE);chunk++){
      if(requested.current.has(chunk))continue;requested.current.add(chunk);
      const r=await fetchWindow(chunk*PAGE+1,PAGE);if(!r||scan.current.generation!==generation)return;
      for(const e of r.parsed)cache.current.set(e.seq,e);setTotal(r.win.total);
      if(cache.current.size>6000){const mid=Math.floor(top/ROW);for(const key of [...cache.current.keys()])if(Math.abs(key-mid)>2500){cache.current.delete(key);requested.current.delete(Math.floor((key-1)/PAGE));}}
      refresh();
     }
    }else if(scan.current.from!==null && top+height>=visibleRows.current*ROW-ROW*PAGE/2){
     const r=await fetchWindow(scan.current.from,PAGE,query||undefined);if(!r||scan.current.generation!==generation)return;
     const seen=new Set(matches.current.map(e=>e.seq));matches.current=[...matches.current,...r.parsed.filter(e=>!seen.has(e.seq))];
     scan.current.from=r.win.next;setTotal(r.win.total);if(r.win.next===null)setMatchEnd(r.win.total);refresh();
    }
   }catch(e){if(alive.current)setError((e as Error).message);}finally{busy.current=false;}
  })();
 },[tick,top,height,pending,searching,total,query,error]);
 // Live tail: re-read the last known line so a partially written line is completed.
 useEffect(()=>{
  if(!live)return;
  const timer=setInterval(()=>{
   if(document.hidden||busy.current||pending)return;
   const from=searching?(matchEnd!==null?Math.max(1,matchEnd):null):Math.max(1,total);
   if(from===null)return;
   const generation=scan.current.generation;busy.current=true;
   (async()=>{
    try{
     const r=await fetchWindow(from,500,searching?query||undefined:undefined);if(!r||scan.current.generation!==generation)return;
     if(searching){const seen=new Set(matches.current.map(e=>e.seq));const add=r.parsed.filter(e=>!seen.has(e.seq));if(add.length)matches.current=[...matches.current,...add];setTotal(r.win.total);if(r.win.next===null)setMatchEnd(r.win.total);else{scan.current.from=r.win.next;setMatchEnd(null);}refresh();}
     // A burst can exceed this tail request's limit. Do not mark the file's last chunk as loaded:
     // it may contain the crash after hundreds of startup lines and was never fetched here.
     else if(r.parsed.length){for(const e of r.parsed)cache.current.set(e.seq,e);setTotal(r.win.total);refresh();}
    }catch(e){if(alive.current)setError((e as Error).message);}finally{busy.current=false;}
   })();
  },1000);
  return()=>clearInterval(timer);
 },[live,searching,matchEnd,total,query,pending,id,path]);
 useEffect(()=>{if(!viewport.current)return;const observer=new ResizeObserver(entries=>setHeight(entries[0].contentRect.height));observer.observe(viewport.current);return()=>observer.disconnect();},[]);
 const rows=useMemo<ParsedLog[]|null>(()=>searching?matches.current.filter(e=>(!errorsOnly||['warn','error'].includes(e.kind)||['WARN','ERROR','FATAL'].includes(e.level||'')||!!e.frame)&&!hiddenByFilter(e,appliedFilters)):null,[tick,searching,errorsOnly,appliedFilters]);
 const count=rows?rows.length:total;visibleRows.current=count;
 useLayoutEffect(()=>{const v=viewport.current;if(!v)return;if(followRef.current)v.scrollTop=v.scrollHeight;setTop(v.scrollTop);},[count]);
 const start=Math.max(0,Math.floor(top/ROW)-8),end=Math.min(count,Math.ceil((top+height)/ROW)+8);
 const shown=[];for(let n=start;n<end;n++){const event=rows?rows[n]:cache.current.get(n+1);shown.push(event?<LogRow key={event.seq} event={event} top={n*ROW} onOpen={openDetail}/>:<div key={'gap'+n} className="log virtual-log output" style={{position:'absolute',top:n*ROW,height:ROW,width:'100%'}}><span className="lineno">{n+1}</span><span className="log-text muted">…</span></div>);}
 const lastMatch=rows?.at(-1)?.seq??0;
 return <section className="console-panel"><div className="toolbar"><div className="panel-title"><span className="green-dot"/>{source?'로그 파일':'실시간 콘솔'} <span className="muted" data-testid="log-count">{searching?`일치 ${count.toLocaleString()}줄${matchEnd===null?' · 검색 중':' · 전체 검색 완료'} · 마지막 일치 #${lastMatch.toLocaleString()} · 전체 ${total.toLocaleString()}줄`:`현재 ${total.toLocaleString()}줄 · 끝 줄 #${total.toLocaleString()}`}</span></div><div className="actions"><input aria-label="로그 검색" placeholder="전체 로그에서 검색…" value={filter} onChange={e=>{setFilter(e.target.value);setFollow(false);setTop(0);if(viewport.current)viewport.current.scrollTop=0;}}/><label><input type="checkbox" checked={errorsOnly} onChange={e=>setErrorsOnly(e.target.checked)}/>오류·경고</label><label><input type="checkbox" checked={follow} onChange={e=>{setFollow(e.target.checked);if(e.target.checked&&viewport.current)viewport.current.scrollTop=viewport.current.scrollHeight;}}/>자동 스크롤</label></div></div>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}<div className="log-history"><span>검색은 서버에서 파일 전체를 대상으로 하며 스크롤하면 이어서 불러옵니다 · 표시되는 번호는 원문 줄 번호입니다 · 프레임을 누르면 Mixin 상세 보기</span></div>
 <div className="log-viewport-shell">{loading&&<div className="log-loading" role="status" aria-live="polite"><span className="log-spinner"/>{progress||'로그 파싱 중…'}<small>긴 로그도 필요한 부분만 불러옵니다.</small></div>}
 <div className="logs virtual-logs" aria-busy={pending||loading} ref={viewport} data-testid="log-viewport" onScroll={e=>{const v=e.currentTarget;setTop(v.scrollTop);if(v.scrollHeight-v.scrollTop-v.clientHeight>ROW*2)setFollow(false);}}>{!count?<div className="console-empty"><Terminal size={28}/><p>{pending?'로그를 준비하고 있습니다.':searching?(matchEnd===null?'서버에서 검색하고 있습니다.':'현재 검색·필터에 맞는 로그가 없습니다.'):'표시할 로그가 없습니다.'}</p></div>:<div style={{height:count*ROW,position:'relative'}}>{shown}</div>}</div>
 </div>
 {!source&&<Command key={id} id={id} enabled={enabled} hint={hint}/>}<div className="panel-foot">{source?'저장된 원문 로그를 조회하고 있습니다.':'일반 입력은 서버 채팅으로 전송 · /로 시작하면 명령 실행'}<span>파싱 결과는 디스크에 저장하지 않습니다.</span></div>
 {detail&&<LogDetail event={detail} onClose={()=>setDetail(null)}/>}
 </section>;
}
