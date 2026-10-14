import {useEffect,useRef,useState} from 'react';
import {Check,ChevronLeft,ChevronRight,FlaskConical,Pause,Play,Power,PowerOff,RefreshCw,Search,ShieldCheck,Square,X} from 'lucide-react';
import {api} from './http';
import './mods.css';

type Source={projectId:string;slug:string;title:string;description:string;iconUrl:string|null;versionNumber:string;environment:string;clientOnly:boolean;dependencies:{projectId:string;type:string}[]};
type Mod={filename:string;enabled:boolean;size:number;sha256:string;warning:string|null;source:Source|null};
type Snapshot={revision:string;mods:Mod[];warnings:string[];dependencyFiles:string[];dependencyReasons:Record<string,string[]>;missing?:Record<string,string[]>;projects?:Record<string,{id:string;slug:string;title:string}>};
type Trial={number:number;round:number;groups:string[];disabled:string[];started:boolean;verdict:string|null;note:string;createdAt:string};
type Session={id:string;phase:string;round:number;error:string|null;plan:{groups:{id:string;files:string[]}[];fixed:string[];protectedSuspects:string[]};trials:Trial[];findings:string[][]};
type Page={inventoryRevision:string|null;inventory:Snapshot|null;job:{phase:string;message:string;files:number;bytes:number}|null;diagnostic:Session|null;next:{status:string;groups:string[];message:string}|null;active:boolean};
type Preview={revision:string;enabled:boolean;selected:string[];affected:string[];warnings?:string[]};
type Filter='all'|'enabled'|'disabled'|'missing'|'unknown'|'client';
const verdictLabel:Record<string,string>={PASS:'성공 · 문제 없음',FAIL:'실패 · 원래 문제 재현',INVALID:'판정 불가'};
const busyStates=['RUNNING','STARTING','STOPPING','INSTALLING','IMPORTING','TRANSFERRING','SYNCING','SYNC_FAILED','UNKNOWN'];
export function ModsPanel({id,loader,state,canManage,onChanged,onConsole}:{id:string;loader:string;state:string;canManage:boolean;onChanged:()=>Promise<unknown>;onConsole:()=>void}) {
 const [data,setData]=useState<Page|null>(null),[error,setError]=useState(''),[sending,setSending]=useState(false);
 const [query,setQuery]=useState(''),[filter,setFilter]=useState<Filter>('all'),[page,setPage]=useState(0),[suspects,setSuspects]=useState<Set<string>>(new Set()),[keep,setKeep]=useState<Set<string>>(new Set()),[selected,setSelected]=useState<Set<string>>(new Set());
 const [historyOpen,setHistoryOpen]=useState(false),[historyPage,setHistoryPage]=useState(0);
 const inventoryRevision=useRef<string|null>(null);
 const [preview,setPreview]=useState<Preview|null>(null),[note,setNote]=useState(''),[logTrial,setLogTrial]=useState<number|null>(null);
 const alive=useRef(true);const endpoint=`/instances/${id}/mods`;
 const load=async()=>{let result=await api<Page>(endpoint);if(result.inventoryRevision!==inventoryRevision.current){result=await api<Page>(endpoint+'?inventory=true');inventoryRevision.current=result.inventoryRevision;}if(alive.current)setData(old=>({...result,inventory:result.inventory||old?.inventory||null}));};
 useEffect(()=>{alive.current=true;load().catch(e=>setError(e.message));let timer:ReturnType<typeof setTimeout>;let stopped=false;const poll=async()=>{try{if(!document.hidden)await load();}catch{}finally{if(!stopped)timer=setTimeout(poll,1500);}};timer=setTimeout(poll,1500);return()=>{alive.current=false;stopped=true;clearTimeout(timer);};},[id]);
 const action=async(path:string,body?:unknown)=>{if(sending)return;setSending(true);setError('');try{await api(endpoint+path,'POST',body);await load();await onChanged();}catch(e){setError((e as Error).message);}finally{if(alive.current)setSending(false);}};
 const inv=data?.inventory,session=data?.diagnostic,current=session?.trials.at(-1);
 const working=sending||!!data?.job&&!['COMPLETE','FAILED'].includes(data.job.phase);
 const running=busyStates.includes(state),stopped=!running;
 const locked=working||running||!canManage||!!data?.active;
 const dependencies=new Set(inv?.dependencyFiles||[]);
 const mods=inv?.mods||[],missing=inv?.missing||{};
 const matches:Record<Filter,(m:Mod)=>boolean>={all:()=>true,enabled:m=>m.enabled,disabled:m=>!m.enabled,missing:m=>!!missing[m.filename]?.length,unknown:m=>!m.source,client:m=>!!m.source?.clientOnly};
 const filters:{id:Filter;label:string;count:number}[]=([['all','전체'],['enabled','활성'],['disabled','비활성'],['missing','의존성 누락'],['unknown','Modrinth 미확인'],['client','클라이언트 전용']] as [Filter,string][]).map(([id,label])=>({id,label,count:mods.filter(matches[id]).length}));
 const needle=query.toLowerCase(),filtered=mods.filter(m=>matches[filter](m)&&`${m.filename} ${m.source?.title||''} ${m.source?.slug||''}`.toLowerCase().includes(needle));
 const project=(id:string)=>inv?.projects?.[id]?.title||mods.find(m=>m.source?.projectId===id)?.source?.title||id;
 const name=(file:string)=>mods.find(m=>m.filename===file)?.source?.title||file;
 const selectedEnabled=mods.filter(m=>m.enabled&&selected.has(m.filename)).map(m=>m.filename),selectedDisabled=mods.filter(m=>!m.enabled&&selected.has(m.filename)).map(m=>m.filename);
 const lastPage=Math.max(0,Math.ceil(filtered.length/75)-1),shownPage=Math.min(page,lastPage),visible=filtered.slice(shownPage*75,(shownPage+1)*75);
 const change=(setter:typeof setSelected,value:string,checked:boolean)=>setter(old=>{const next=new Set(old);if(checked)next.add(value);else next.delete(value);return next;});
 const groups=(ids:string[])=>session?.plan.groups.filter(g=>ids.includes(g.id)).flatMap(g=>g.files)||[];
 const requestPreview=async(files:string[],enabled:boolean)=>{if(sending)return;setSending(true);setError('');try{setPreview(await api<Preview>(endpoint+'/toggle-preview','POST',{revision:inv?.revision,files,enabled}));}catch(e){setError((e as Error).message);}finally{setSending(false);}};
 const verdict=(value:string,trial=current)=>{if(trial)action('/diagnostic/verdict',{trial:trial.number,verdict:value,note});};
 if(!['Fabric','Forge','NeoForge'].includes(loader))return <section className="settings-panel"><h2>모드 관리</h2><p>Fabric·Forge·NeoForge 서버에서 사용할 수 있습니다.</p></section>;
 return <section className="mods-panel">
  <div className="mods-heading"><div><h2>모드 관리와 문제 모드 찾기</h2><p>Modrinth에서 확인한 의존성으로 모드를 켜고 끄고, 의심 모드를 골라 문제 원인을 좁혀보세요.</p></div><button disabled={working||running||!canManage} onClick={()=>action('/refresh')}><RefreshCw size={16}/> 모드 목록 불러오기</button></div>
  {error&&<div className="alert error-banner" role="alert">{error}<button onClick={()=>setError('')}>닫기</button></div>}
  {data?.job&&<div className={'mod-job '+data.job.phase.toLowerCase()} role="status"><span className={working?'mod-spinner':''}/><span>{data.job.message}{data.job.files>0&&` · ${data.job.files.toLocaleString()}개 · ${(data.job.bytes/1048576).toFixed(1)} MiB`}</span></div>}
  {inv?.warnings.length? <details className="mod-warnings"><summary>검사 주의사항 {inv.warnings.length}개</summary>{inv.warnings.map((w,i)=><p key={i}>{w}</p>)}</details>:null}
  {session&&session.phase!=='FINISHED'&&<div className="mod-diagnostic">
   <div className="mods-heading"><div><span className="eyebrow">MANUAL MOD TEST · {session.round}차 탐색</span><h3><FlaskConical size={21}/> 선택한 모드에서 원인 찾기</h3></div><span className="pill">{session.phase==='PAUSED'?'일시 중단':data?.active?'격리 검사 중':session.phase}</span></div>
   <p>원본 월드와 모드는 보존됩니다. 각 시도는 <strong>새 테스트 월드</strong>에서 실행됩니다. 기존 접속 주소로 들어가 같은 행동을 재현하고, 같은 문제가 발생했는지 직접 판정하세요.</p>
   <p className="muted">자동 판정·자동 재시작·무인 절전·운영 클라우드 동기화는 검사 중에 동작하지 않습니다. 결과를 판정하기 전에는 다음 후보로 넘어가지 않습니다.</p>
   {session.error&&<div className="alert" role="alert">{session.error}</div>}
   {data?.next&&<div className="mod-search-result" role="status"><strong>{data.next.status==='FOUND'?'재현 원인 후보':data.next.status==='EXHAUSTED'?'탐색 완료':'다음 확인'}</strong><p>{data.next.message}</p>{data.next.status==='FOUND'&&<div><ul>{groups(data.next.groups).map(f=><li key={f}>{f}</li>)}</ul><p className="muted">필수 의존성 모드는 모든 시도에서 유지했습니다. 단일 모드인지 여러 후보의 상호작용인지 이 결과 조합을 기준으로 확인하세요.</p></div>}</div>}
   {session.plan.protectedSuspects.length>0&&<p className="muted"><ShieldCheck size={15}/> 다른 모드의 필수 의존성이라 후보에서 제외: {session.plan.protectedSuspects.join(', ')}</p>}
   {session.findings.length>0&&<details open><summary>찾은 원인 {session.findings.length}개</summary><ol>{session.findings.map((g,n)=><li key={n}>{groups(g).join(' + ')}</li>)}</ol><p className="muted">수동 재현 결과입니다. 모든 가능한 충돌을 찾았다는 의미는 아닙니다.</p></details>}
   {current&&<div className="mod-trial"><h4>시도 {current.number} <span className="muted">{current.groups.length} / {session.plan.groups.length}개 의존성 묶음 활성화</span></h4>
    <details><summary>이번에 켤 의심 모드 ({groups(current.groups).length})</summary><ul>{groups(current.groups).map(f=><li key={f}>{f}</li>)}</ul></details>
    <details><summary>이번에 끌 의심 모드 ({current.disabled.length})</summary><ul>{current.disabled.map(f=><li key={f}>{f}</li>)}</ul></details>
    {current.verdict&&<p><strong>{verdictLabel[current.verdict]}</strong> {current.note}</p>}
    <div className="actions"><button className="primary" disabled={!canManage||working||running||session.phase!=='READY'||!data?.active} onClick={()=>action('/diagnostic/start')}><Play size={16}/> 이 조합으로 테스트 시작</button><button onClick={onConsole}>실시간 콘솔 보기</button>{['RUNNING','STARTING'].includes(state)&&canManage&&<button disabled={working} onClick={async()=>{setError('');try{await api(`/instances/${id}/stop`,'POST');await onChanged();}catch(e){setError((e as Error).message);}}}><Square size={15}/> 저장 후 종료</button>}</div>
    {current.started&&data?.active&&<div className="mod-verdict"><label>재현 상황 / 판정 메모<textarea maxLength={2000} value={note} onChange={e=>setNote(e.target.value)} placeholder="예: 네더 이동 직후 동일한 오류가 발생함"/></label><p className="muted">다른 오류, 의존성 누락, 접속 실패 또는 재현 조건이 달랐다면 ‘판정 불가’를 선택하세요. 실행 중 판정하면 저장 후 정상 종료를 요청합니다.</p><div className="actions"><button disabled={!canManage||working||!['RUNNING','STARTING','STOPPED','FAILED'].includes(state)} onClick={()=>verdict('PASS')}><Check size={16}/> 이번 시도 성공</button><button className="danger-text" disabled={!canManage||working||!['RUNNING','STARTING','STOPPED','FAILED'].includes(state)} onClick={()=>verdict('FAIL')}><X size={16}/> 이번 시도 실패</button><button disabled={!canManage||working||!['RUNNING','STARTING','STOPPED','FAILED'].includes(state)} onClick={()=>verdict('INVALID')}>판정 불가 · 같은 조합 재시도</button></div></div>}
   </div>}
   <div className="actions mod-session-actions">
    {session.phase==='PAUSED'?<button className="primary" disabled={working||running||!canManage} onClick={()=>action('/diagnostic/resume')}><Play size={15}/> 검사 재개</button>:<><button className="primary" disabled={working||running||!canManage||!['JUDGED','RESULT'].includes(session.phase)||!['TEST','INCONCLUSIVE'].includes(data?.next?.status||'')} onClick={()=>action('/diagnostic/next')}>다음 조합 준비 <ChevronRight size={16}/></button><button disabled={working||running||!canManage||data?.next?.status!=='FOUND'} onClick={()=>action('/diagnostic/more')}>찾은 원인을 제외하고 다른 원인 찾기</button><button disabled={working||running||!canManage} onClick={()=>action('/diagnostic/pause')}><Pause size={15}/> 일시 중단 · 원본 복귀</button></>}
    <button disabled={working||running||!canManage} onClick={()=>action('/diagnostic/finish')}>검사 종료 · 원본 복귀</button>
   </div>
   {running&&!['RUNNING','STARTING'].includes(state)&&<p role="status" className="muted">정상 종료와 파일 인계가 끝나면 다음 조합을 준비할 수 있습니다.</p>}
   <details className="mod-history" open={historyOpen} onToggle={e=>setHistoryOpen(e.currentTarget.open)}><summary>시도 이력 ({session.trials.length}) · 판정 수정 및 로그</summary>{historyOpen&&session.trials.slice(historyPage*25,(historyPage+1)*25).map(t=><div key={t.number}><span>#{t.number} · {t.round}차 · {verdictLabel[t.verdict||'']||'미판정'} · {t.note}</span><div className="actions"><button onClick={()=>setLogTrial(t.number)}>보관 로그</button>{t.started&&<button disabled={working||running||!canManage||!data?.active||t.round!==session.round} title="현재 탐색 차수의 판정만 수정할 수 있습니다" onClick={()=>{const answer=prompt('수정 판정: PASS / FAIL / INVALID',t.verdict||'INVALID');if(answer&&['PASS','FAIL','INVALID'].includes(answer))verdict(answer,t);}}>판정 수정</button>}</div></div>)}{historyOpen&&session.trials.length>25&&<div className="actions"><button disabled={!historyPage} onClick={()=>setHistoryPage(historyPage-1)}>이전 이력</button><span>{historyPage+1} / {Math.ceil(session.trials.length/25)}</span><button disabled={(historyPage+1)*25>=session.trials.length} onClick={()=>setHistoryPage(historyPage+1)}>다음 이력</button></div>}</details>
  </div>}
  {!inv?<div className="mods-empty"><Search size={30}/><p>서버를 정지한 뒤 모드 목록을 불러오세요.</p><small>파일 해시로 Modrinth에서 모드 정보와 의존성을 찾아 서버별로 저장합니다.</small></div>:<div className="mods-browser">
   <div className="mods-sidebar" role="group" aria-label="모드 필터">
    <h3>보기</h3>
    {filters.map(f=><button key={f.id} className={'mods-filter-item'+(filter===f.id?' active':'')} aria-pressed={filter===f.id} onClick={()=>{setFilter(f.id);setPage(0);}}><span>{f.label}</span><span className="count">{f.count}</span></button>)}
    <div className="mods-sidebar-help"><p><strong>의심</strong> 표시한 모드에서 문제 원인을 찾고, <strong>유지</strong> 표시한 모드는 검사 내내 켜 둡니다. 다른 모드가 필수로 쓰는 모드는 자동으로 유지됩니다.</p><button disabled={locked} onClick={()=>setSuspects(new Set(mods.filter(m=>m.enabled&&!dependencies.has(m.filename)).map(m=>m.filename)))}>검사 가능한 전체를 의심으로</button></div>
   </div>
   <div className="mods-main">
    <div className="mods-actionbar" role="toolbar" aria-label="선택한 모드 작업">
     <label className="mod-filter"><Search size={17}/><input aria-label="모드 검색" placeholder="모드 이름, 파일명 검색" value={query} onChange={e=>{setQuery(e.target.value);setPage(0);}}/></label>
     <label className="mod-select-all"><input type="checkbox" aria-label="보이는 모드 모두 선택" disabled={locked||!visible.length} checked={!!visible.length&&visible.every(m=>selected.has(m.filename))} onChange={e=>setSelected(old=>{const next=new Set(old);visible.forEach(m=>e.target.checked?next.add(m.filename):next.delete(m.filename));return next;})}/>{selected.size?`${selected.size}개 선택`:'전체 선택'}</label>
     <div className="actions">
      <button disabled={locked||!selectedEnabled.length} onClick={()=>requestPreview(selectedEnabled,false)}><PowerOff size={15}/> 선택 비활성화{selectedEnabled.length?` (${selectedEnabled.length})`:''}</button>
      <button disabled={locked||!selectedDisabled.length} onClick={()=>requestPreview(selectedDisabled,true)}><Power size={15}/> 선택 활성화{selectedDisabled.length?` (${selectedDisabled.length})`:''}</button>
      <button className="primary" disabled={locked||!suspects.size||!!session&&session.phase!=='FINISHED'} onClick={()=>action('/diagnostic',{revision:inv.revision,suspects:[...suspects],keep:[...keep]})}><FlaskConical size={16}/> 의심 {suspects.size}개에서 문제 찾기</button>
      <button disabled={working||!(selected.size||suspects.size||keep.size)} onClick={()=>{setSuspects(new Set());setKeep(new Set());setSelected(new Set());}}>선택 초기화</button>
     </div>
    </div>
    {running&&<p className="muted" role="status">서버 실행·인계 중에는 모드 목록 갱신과 변경이 잠깁니다.</p>}
    <div className="mod-list">{visible.map(m=>{const s=m.source,required=dependencies.has(m.filename),users=inv.dependencyReasons?.[m.filename]||[],missing=inv.missing?.[m.filename]||[],needs=(s?.dependencies||[]).filter(d=>d.type==='required');return <article key={m.filename} className={'mod-card'+(m.enabled?'':' off')+(selected.has(m.filename)?' selected':'')}>
     <input className="mod-card-check" aria-label={`${m.filename} 선택`} type="checkbox" disabled={locked} checked={selected.has(m.filename)} onChange={e=>change(setSelected,m.filename,e.target.checked)}/>
     {s?.iconUrl?<img className="mod-icon" src={s.iconUrl} alt="" loading="lazy" referrerPolicy="no-referrer"/>:<div className="mod-icon placeholder" aria-hidden="true">{(s?.title||m.filename).slice(0,1).toUpperCase()}</div>}
     <div className="mod-card-body">
      <div className="mod-card-title">{s?<a href={`https://modrinth.com/mod/${encodeURIComponent(s.slug)}`} target="_blank" rel="noopener noreferrer">{s.title}</a>:<strong>{m.filename.replace(/\.jar(\.disabled)?$/i,'')}</strong>}{s&&<span className="mod-version">{s.versionNumber}</span>}</div>
      {s?.description&&<p className="mod-description">{s.description}</p>}
      <div className="mod-badges">
       <span className={'mod-badge '+(m.enabled?'on':'off')}>{m.enabled?'활성':'비활성'}</span>
       {s?.clientOnly&&<span className="mod-badge client">클라이언트 전용</span>}
       {required&&<span className="mod-badge library" title={`${users.join(', ')}에서 필수로 사용`}><ShieldCheck size={12}/> {users.length}개 모드가 필수로 사용</span>}
       {!s&&<span className="mod-badge unknown">Modrinth 미확인</span>}
       {missing.length>0&&<span className="mod-badge missing">필수 모드 없음: {missing.map(project).join(', ')}</span>}
      </div>
      {needs.length>0&&<p className="mod-deps">필수 의존성 · {needs.map(d=>project(d.projectId)).join(', ')}</p>}
      <small className="mod-file">{m.filename}</small>
     </div>
     <div className="mod-card-side">
      <button role="switch" aria-checked={m.enabled} aria-label={`${m.filename} ${m.enabled?'비활성화':'활성화'}`} className={'mod-switch'+(m.enabled?' on':'')} disabled={locked} onClick={()=>requestPreview([m.filename],!m.enabled)}><span/></button>
      <label className={'mod-chip'+(suspects.has(m.filename)?' on':'')}><input aria-label={`${m.filename} 의심 모드`} type="checkbox" disabled={locked||!m.enabled||required} checked={suspects.has(m.filename)} onChange={e=>change(setSuspects,m.filename,e.target.checked)}/>의심</label>
      <label className={'mod-chip'+(keep.has(m.filename)||required?' on':'')}><input aria-label={`${m.filename} 항상 유지`} type="checkbox" disabled={locked||!m.enabled||required} checked={keep.has(m.filename)||required} onChange={e=>change(setKeep,m.filename,e.target.checked)}/>유지</label>
     </div>
    </article>;})}{!visible.length&&<p className="mods-empty">조건에 맞는 모드가 없습니다.</p>}</div>
    {filtered.length>75&&<div className="log-history"><button disabled={!shownPage} onClick={()=>setPage(shownPage-1)}><ChevronLeft size={16}/> 이전</button><span>{shownPage+1} / {lastPage+1}</span><button disabled={shownPage===lastPage} onClick={()=>setPage(shownPage+1)}>다음 <ChevronRight size={16}/></button></div>}
   </div>
  </div>}
  {preview&&<div className="modal-backdrop"><div className="dialog mod-confirm" role="dialog" aria-modal="true" aria-label="모드 변경 확인"><h2>{preview.enabled?'모드 활성화':'모드 비활성화'} 확인</h2><p>선택 {preview.selected.length}개{preview.affected.length>preview.selected.length?`와 의존 관계로 함께 바뀌는 ${preview.affected.length-preview.selected.length}개`:''}를 변경합니다. 원본 JAR는 삭제하지 않고 이름만 바꿉니다.</p><ul>{preview.affected.map(f=><li key={f}>{name(f)}{!preview.selected.includes(f)&&<span className="muted"> · {preview.enabled?'필수 의존성이라 함께 활성화':'이 모드를 필수로 사용해 함께 비활성화'}</span>}</li>)}</ul>{!!preview.warnings?.length&&<div className="mod-warnings">{preview.warnings.map((w,i)=><p key={i}>{w}</p>)}</div>}<div className="actions"><button onClick={()=>setPreview(null)}>취소</button><button className="primary" disabled={working||running} onClick={async()=>{await action('/toggle',{revision:preview.revision,files:preview.selected,enabled:preview.enabled});setPreview(null);setSelected(new Set());}}>확인하고 변경</button></div></div></div>}
  {logTrial!==null&&<TrialLogs endpoint={endpoint} trial={logTrial} onClose={()=>setLogTrial(null)}/>}
 </section>;
}
function TrialLogs({endpoint,trial,onClose}:{endpoint:string;trial:number;onClose:()=>void}) {
 const [files,setFiles]=useState<{path:string;name:string}[]>([]),[path,setPath]=useState(''),[text,setText]=useState(''),[offset,setOffset]=useState<number|null>(0),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{let alive=true;api<{path:string;name:string}[]>(`${endpoint}/diagnostic/trials/${trial}/logs`).then(list=>{if(alive){setFiles(list);setPath(list[0]?.path||'');}}).catch(e=>{if(alive)setError(e.message);});return()=>{alive=false;};},[trial]);
 const load=async(position:number,filename=path)=>{setBusy(true);setError('');try{const page=await api<{events:{text:string}[];nextOffset:number|null}>(`${endpoint}/diagnostic/trials/${trial}/log-page?path=${encodeURIComponent(filename)}&offset=${position}`);setText(page.events.map(e=>e.text).join('\n'));setOffset(page.nextOffset);}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 useEffect(()=>{if(path)load(0,path);},[path]);
 return <div className="modal-backdrop"><div role="dialog" aria-modal="true" aria-label={`시도 ${trial} 로그`} className="dialog mod-logs"><div className="mods-heading"><h2>시도 {trial} · 원본 로그</h2><button onClick={onClose}>닫기</button></div><p>종료와 파일 인계 후 보관됩니다. 현재 실행 로그는 실시간 콘솔에서 확인하세요.</p>{error&&<p role="alert">{error}</p>}<select aria-label="보관 로그 파일" value={path} onChange={e=>setPath(e.target.value)}>{files.map(f=><option key={f.path} value={f.path}>{f.name}</option>)}</select>{!files.length&&<p>보관된 로그가 없습니다.</p>}<pre>{text}</pre><div className="actions"><button disabled={busy||!path} onClick={()=>load(0)}>처음</button><button disabled={busy||offset===null||!path} onClick={()=>load(offset!)}>다음 300줄</button></div></div></div>;
}
