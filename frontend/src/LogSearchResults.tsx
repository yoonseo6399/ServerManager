import {useEffect,useMemo,useRef,useState} from 'react';
import {api} from './http';
import {parseLog,type ParsedLog} from './logParser';
import type {FilterConfig} from './LogFilters';
type Row={no:number;kind:string;text:string;level?:string;logger?:string};
type View={id:string;state:string;scanned:number;bytes:number;totalBytes:number;matches:number;retained:number;offset:number;next:number|null;rows:Row[];error?:string};
export function LogSearchResults({id,path,query,filters,errors,onOpen}:{id:string;path:string;query:string;filters:FilterConfig;errors:boolean;onOpen:(row:ParsedLog)=>void}){
 const [view,setView]=useState<View|null>(null),[error,setError]=useState(''),[offset,setOffset]=useState(0),[revision,setRevision]=useState(0);
 const generation=useRef(0),job=useRef<string|null>(null),activeOffset=useRef(0);activeOffset.current=offset;
 const rules=useMemo(()=>JSON.stringify({enabled:filters.enabled,rules:filters.rules}),[filters.enabled,filters.rules]);
 useEffect(()=>{
  const epoch=++generation.current,abort=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined;let owned:string|null=null;
  setView(null);setError('');setOffset(0);activeOffset.current=0;job.current=null;
  async function poll(){try{const next=await api<View>(`/instances/${id}/log-search/${owned}?offset=${activeOffset.current}`,'GET',undefined,abort.signal);if(abort.signal.aborted||generation.current!==epoch)return;setView(next);if(next.state==='FAILED')setError(next.error||'로그 검색에 실패했습니다.');else timer=setTimeout(poll,next.state==='COMPLETE'?3000:400);}catch(e){if(!abort.signal.aborted&&generation.current===epoch)setError((e as Error).message);}}
  const debounce=setTimeout(async()=>{try{const next=await api<View>(`/instances/${id}/log-search`,'POST',{path,query,errorsOnly:errors,filters:JSON.parse(rules)},abort.signal);owned=next.id;if(abort.signal.aborted||generation.current!==epoch){void api(`/instances/${id}/log-search/${owned}`,'DELETE').catch(()=>{});return;}job.current=owned;setView(next);void poll();}catch(e){if(!abort.signal.aborted&&generation.current===epoch)setError((e as Error).message);}},250);
  return()=>{abort.abort();clearTimeout(debounce);clearTimeout(timer);if(owned)void api(`/instances/${id}/log-search/${owned}`,'DELETE').catch(()=>{});};
 },[id,path,query,rules,errors,revision]);
 useEffect(()=>{if(!job.current)return;const abort=new AbortController(),epoch=generation.current;api<View>(`/instances/${id}/log-search/${job.current}?offset=${offset}`,'GET',undefined,abort.signal).then(v=>{if(!abort.signal.aborted&&epoch===generation.current)setView(v);}).catch(e=>{if(!abort.signal.aborted)setError(e.message);});return()=>abort.abort();},[id,offset]);
 const rows=useMemo(()=>view?.rows.map(row=>({...parseLog({seq:row.no,text:row.text,kind:row.kind,time:null}),level:row.level,logger:row.logger}))||[],[view]);
 const busy=!view||['QUEUED','SCANNING'].includes(view.state);
 return <div className="full-log-search"><p role="status" data-testid="log-search-progress">{busy?'파일 전체 검색 중':'파일 전체 검색 완료'} · {view?.scanned.toLocaleString()||0}줄 확인 · 일치 {view?.matches.toLocaleString()||0}줄 {view&&view.totalBytes>0?`· ${Math.floor(view.bytes/view.totalBytes*100)}%`:''}</p><progress aria-label="전체 로그 검색 진행률" max={Math.max(1,view?.totalBytes||1)} value={view?.bytes||0}/><p className="muted">검색 시작 시점의 열린 파일 전체를 백그라운드에서 확인합니다. 제외 필터가 적용되며 원문 줄 번호를 유지합니다.{view&&view.matches>view.retained?' 결과가 많아 처음 100,000개를 표시합니다. 검색어를 좁히면 나머지 결과도 확인할 수 있습니다.':''}</p>{error&&<p role="alert">{error}</p>}<div className="logs grouped-logs" data-testid="log-search-results" aria-busy={busy}>{rows.map(row=><div key={row.seq} className={'log-block-line '+row.kind} data-event-seq={row.seq} role="button" tabIndex={0} onClick={()=>onOpen(row)} onKeyDown={e=>{if(e.key==='Enter')onOpen(row);}}><span className="lineno">{row.seq}</span><pre>{row.text}</pre></div>)}{!busy&&!rows.length&&!error&&<p>현재 검색·필터에 맞는 로그가 없습니다.</p>}</div><div className="actions"><button disabled={!offset} onClick={()=>setOffset(Math.max(0,offset-100))}>이전 검색 결과</button><span>{offset+1}–{offset+rows.length} / {view?.matches.toLocaleString()||0}</span><button disabled={view?.next==null} onClick={()=>setOffset(view!.next!)}>다음 검색 결과</button><button onClick={()=>setRevision(v=>v+1)}>다시 검색</button></div></div>;
}
