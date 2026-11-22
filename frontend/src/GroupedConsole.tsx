import {useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {api} from './http';
import {useBackClose} from './nav';
import {LogDetailDialog,LogText,FrameMixins} from './LogDetail';
import {useLogParser} from './useLogParser';
import {hiddenByFilter,type FilterConfig} from './LogFilters';
import {logElements,logRecords,type LogRecord} from './logElements';
import type {ParsedLog} from './logParser';
type Window={lines:{no:number;kind:string;text:string}[];total:number;first:number;last:number;next:number|null};
const PAGE=300;
export function GroupedConsole({id,source,filters}:{id:string;source:string;filters:FilterConfig}){
 const path=source||'console.log',live=!source;
 const [rows,setRows]=useState<ParsedLog[]>([]),[total,setTotal]=useState(0),[from,setFrom]=useState<number|null>(null),[query,setQuery]=useState(''),[errors,setErrors]=useState(false),[error,setError]=useState(''),[loading,setLoading]=useState(false),[detail,setDetail]=useState<LogRecord|null>(null),[more,setMore]=useState(false);
 const {parse}=useLogParser(),viewport=useRef<HTMLDivElement>(null),serial=useRef(0);
 const [follow,setFollow]=useState(live),[refresh,setRefresh]=useState(0);
 const following=useRef(live),pinnedStart=useRef(1),lastTop=useRef(0);
 function pause(){
  if(!following.current)return;
  following.current=false;pinnedStart.current=rows[0]?.seq||1;
  // Invalidate an in-flight tail response before React processes the scroll update.
  serial.current++;setFollow(false);
 }
 function latest(){
  following.current=true;setFollow(true);setFrom(live?null:Math.max(1,total-PAGE+1));setRefresh(n=>n+1);
 }
 function navigate(start:number){pause();setFrom(start);}
 function scroll(){
  const v=viewport.current;if(!v)return;
  const down=v.scrollTop>lastTop.current+.5;
  if(v.scrollHeight-v.clientHeight-v.scrollTop>2)pause();
  else if(live&&from===null&&down&&!following.current)latest();
  lastTop.current=v.scrollTop;
 }
 const detailRequest=useRef<AbortController|null>(null);
 function closeDetail(){detailRequest.current?.abort();detailRequest.current=null;setMore(false);setDetail(null);}
 function openDetail(record:LogRecord){closeDetail();setDetail(record);}
 useBackClose(!!detail,closeDetail,'log-block-detail');
 useEffect(()=>()=>{detailRequest.current?.abort();detailRequest.current=null;},[id,path]);
 useEffect(()=>{
  let closed=false,timer:ReturnType<typeof setTimeout>;const request=++serial.current;
  async function load(){
   setLoading(true);
   try{
    const base=`/instances/${id}/log-window?path=${encodeURIComponent(path)}`;
    const probe=await api<Window>(base+'&from=1&limit=1');
    const start=from??(live?(follow?Math.max(1,probe.total-PAGE+1):pinnedStart.current):1);
    const page=await api<Window>(base+`&from=${start}&limit=${PAGE}`);
    const parsed=await parse(page.lines.map(row=>({seq:row.no,kind:row.kind,text:row.text,time:null})));
    if(!closed&&serial.current===request){setRows(parsed);setTotal(page.total);setError('');}
   }catch(e){if(!closed)setError((e as Error).message);}finally{if(!closed){setLoading(false);if(live&&from===null)timer=setTimeout(load,1500);}}
  }
  load();return()=>{closed=true;clearTimeout(timer);};
 },[id,path,from,live,parse,follow,refresh]);
 // Build whole records before filters: a frame must keep its header's timestamp and source.
 const records=useMemo(()=>logRecords(rows),[rows]);
 const elements=useMemo(()=>logElements(records.filter(record=>(!errors||record.rows.some(row=>row.frame||['ERROR','FATAL','WARN'].includes(row.level||'')||['error','warn'].includes(row.kind)))&&record.rows.some(row=>!hiddenByFilter(row,filters))&&(!query||record.rows.some(row=>row.search.includes(query.toLowerCase()))))),[records,errors,filters,query]);
 // Follow only after the parsed blocks have been committed to the DOM.
 useLayoutEffect(()=>{
  const v=viewport.current;if(!v)return;
  if(following.current)v.scrollTop=v.scrollHeight;
  lastTop.current=v.scrollTop;
 },[elements,follow,from]);
 async function expandDetail(){
  if(!detail||more)return;
  const target=detail,request=new AbortController();detailRequest.current=request;setMore(true);
  try{
   const start=Math.max(1,target.id-200),raw:ParsedLog[]=[];let offset=start;
   for(let n=0;n<4;n++){
    if(request.signal.aborted)return;
    const page=await api<Window>(`/instances/${id}/log-window?path=${encodeURIComponent(path)}&from=${offset}&limit=500`,'GET',undefined,request.signal);
    if(request.signal.aborted)return;
    const parsed=await parse(page.lines.map(row=>({seq:row.no,kind:row.kind,text:row.text,time:null})));
    if(request.signal.aborted)return;
    raw.push(...parsed);if(page.next===null)break;offset=page.next;
   }
   const record=logRecords(raw).find(r=>r.rows.some(row=>row.seq===target.id));
   if(record&&detailRequest.current===request&&!request.signal.aborted)setDetail(record);
  }catch(e){if(!request.signal.aborted&&detailRequest.current===request)setError((e as Error).message);}
  finally{if(detailRequest.current===request){detailRequest.current=null;setMore(false);}}
 }
 return <section className="console-panel grouped-console"><div className="toolbar"><div className="panel-title">{live?'실시간 콘솔':'로그 파일'} <span data-testid="log-count">전체 {total.toLocaleString()}줄 · #{rows[0]?.seq||0}–{rows.at(-1)?.seq||0}</span></div><div className="actions"><button onClick={latest}>아래로 이동</button>{live&&<small role="status">{follow&&from===null?'새 로그 따라가는 중':'자동 스크롤 일시 정지'}</small>}<input aria-label="블록 검색" placeholder="현재 구간에서 검색…" value={query} onChange={e=>setQuery(e.target.value)}/><label><input type="checkbox" checked={errors} onChange={e=>setErrors(e.target.checked)}/>오류·경고</label></div></div>
 <p className="log-history">기본 모두 펼침 · 출처별 묶음 · 밀리초가 있는 로그는 50ms 시간 구간(실제 게임 틱 번호가 아님) · 검색은 현재 300줄 구간</p>
 {error&&<p role="alert" className="error-banner">{error}</p>}
 <div className="logs grouped-logs" ref={viewport} data-testid="log-viewport" aria-busy={loading} onScroll={scroll} onWheel={e=>{if(e.deltaY<0)pause();}}>
 {elements.length?elements.map(element=><section className="log-element" key={element.id}><header>{element.time||'시각 미확인'}{element.slot!==null?` · ${element.slot}/20 시간 구간`:element.time?' · 초 단위':''}</header>{element.sources.map(group=><details key={group.source} open><summary>{group.source}</summary>{group.records.map(record=><div key={record.id} className="log-record" role="button" tabIndex={0} onClick={()=>openDetail(record)} onKeyDown={e=>{if(e.key==='Enter')openDetail(record);}}>{record.rows.map(row=><div key={row.seq} className={'log-block-line '+row.kind} data-event-seq={row.seq}><span className="lineno">{row.seq}</span><time>{row.logTime||record.time||'—'}</time><span>{row.frame?row.display:row.body??row.display}</span>{!!row.frame?.mixins.length&&<small> · Mixins {row.frame.mixins.length}</small>}</div>)}</div>)}</details>)}</section>):<p>{loading?'로그를 불러오는 중…':'표시할 로그가 없습니다.'}</p>}
 </div><div className="log-history actions"><button disabled={!rows.length||rows[0].seq<=1} onClick={()=>navigate(Math.max(1,(rows[0]?.seq||1)-PAGE))}>이전 구간</button><button disabled={!rows.length||(rows.at(-1)?.seq||0)>=total} onClick={()=>navigate((rows.at(-1)?.seq||0)+1)}>다음 구간</button><button onClick={latest}>최신 구간</button><small>모든 원문 줄을 보려면 ‘원문 줄 보기’를 사용하세요.</small></div>
 {detail&&<LogDetailDialog label="로그 블록 상세" title={`${detail.source} · #${detail.id}`} onClose={closeDetail}>
  <p>{detail.time||'시각 미확인'} · {detail.rows.length}줄{detail.partial?' · 앞부분이 이전 구간에 있을 수 있습니다':''}</p>
  <button disabled={more} onClick={expandDetail}>{more?'이어 읽는 중…':'주변 원문과 스택 더 읽기 (최대 2,000줄)'}</button>
  <LogText key={`raw-${detail.id}`} text={detail.rows.map(row=>row.text).join('\n')}/>
  <FrameMixins key={`frames-${detail.id}`} rows={detail.rows}/>
 </LogDetailDialog>}
 </section>;
}
