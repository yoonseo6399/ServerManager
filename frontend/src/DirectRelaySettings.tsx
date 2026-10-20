import {useEffect,useRef,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
import {SaveBar} from './SaveBar';
type Relay={runnerId:string;name:string;consentAllowed:boolean;active:boolean;ready:boolean;connected:boolean;capable:boolean;streams:number;impactIfCut:string;router:{label:string;externalPort:number;internalAddress:string;internalPort:number}[];diagnostics:Record<string,{status:string;detail:string}>};
type Config={enabled:boolean;relayRunnerId:string|null;candidateRunnerId:string|null;securePort:number;agentHost:string;publicHost:string;graceSeconds:number;retiring:Record<string,number>;relayHosts:Record<string,{agentHost:string;publicHost:string}>};
type View={revision:number;phase:string;message:string;gamePort?:number;config:Config;relays:Relay[];agents:{runnerId:string;name:string;relays:{relayId:string;state:string;reason:string|null;streams:number}[]}[]};
const eligible=(r:Relay)=>r.connected&&r.capable&&r.consentAllowed;
const availability=(r:Relay)=>!r.connected?' · 오프라인':!r.capable?' · 상태 보고 대기 / 업데이트 확인':!r.consentAllowed?' · 로컬 허용 필요':'';
export function DirectRelaySettings(){
 const draftRevision=useRef<number|null>(null),latestRevision=useRef(-1),dirty=useRef(false),alive=useRef(true);
 const auth=useAuth(),[view,setView]=useState<View|null>(null),[config,setConfig]=useState<Config|null>(null),[runner,setRunner]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const adopt=(v:View)=>{latestRevision.current=Math.max(latestRevision.current,v.revision);setConfig(v.config);draftRevision.current=v.revision;dirty.current=false;};
 const edit=(v:Config)=>{dirty.current=true;setConfig(v);};
 const load=async(reset=false)=>{const v=await api<View>('/admin/direct-relay');if(!alive.current||v.revision<latestRevision.current)return;latestRevision.current=v.revision;setView(v);if(reset||!dirty.current)adopt(v);};
 useEffect(()=>{alive.current=true;const poll=()=>{if(!document.hidden)load().catch(e=>{if(alive.current)setError(e.message);});};poll();const timer=setInterval(poll,2000);return()=>{alive.current=false;clearInterval(timer);};},[]);
 async function run(action:()=>Promise<unknown>,reset=true){setBusy(true);setError('');try{try{await action();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await action();}await load(reset);}catch(e){if(alive.current)setError((e as Error).message);}finally{if(alive.current)setBusy(false);}}
 const first=!view?.config.relayRunnerId||!view.config.enabled;
 const initial=config?.relayRunnerId?view?.relays.find(r=>r.runnerId===config.relayRunnerId):null;
 const candidate=view?.relays.find(r=>r.runnerId===runner);
 const options=()=>view?.relays.map(r=><option key={r.runnerId} value={r.runnerId} disabled={!eligible(r)}>{r.name}{availability(r)}</option>);
 return <section className="settings-panel" aria-label="직접 TCP 중계">
  <h2>직접 TCP 중계</h2><p>플레이어 → 중계 실행기 → 게임 실행기 → Minecraft. 관리 Mac은 게임 데이터를 전달하지 않습니다.</p>
  {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
  <p role="status">{view?`${view.phase} · ${view.message}`:'중계 상태를 불러오는 중입니다…'}</p>
  {config&&view&&<>
   <p aria-label="공유 게임 접속 포트">플레이어 접속·SRV 포트: <strong>{view.gamePort??view.relays[0]?.router[0]?.externalPort??'미확인'}</strong>. 아래 ‘게임 주소·DNS·기존 관리 Mac 경유 중계’의 공유 게임 포트에서 변경합니다. 실행기 연결 포트와는 별개입니다.</p>
   {first?<>
    <label>최초 중계 실행기<select disabled={busy} aria-label="최초 중계 실행기" value={config.relayRunnerId||''} onChange={e=>edit({...config,relayRunnerId:e.target.value||null})}><option value="">선택하세요</option>{options()}</select></label>
    <p>중계 실행기를 선택하고 ‘직접 중계 사용’을 켠 뒤 변경 사항을 저장하세요. 아직 상태를 보고하지 않은 실행기도 목록에 표시하며, 확인 전에는 선택할 수 없습니다.</p>
   </>:<p>현재 중계 실행기: <strong>{view.relays.find(r=>r.runnerId===view.config.relayRunnerId)?.name||view.config.relayRunnerId}</strong></p>}
   {!view.relays.length&&<p role="status">등록된 활성 실행기가 없습니다. 서버 실행기에서 먼저 등록하세요.</p>}
   <label className="check-label"><input type="checkbox" checked={config.enabled} disabled={busy} onChange={e=>edit({...config,enabled:e.target.checked})}/>직접 중계 사용</label>
   <div className="form-grid">
    <label>실행기 연결 포트<input disabled={busy} type="number" min={1024} max={65535} value={config.securePort} onChange={e=>edit({...config,securePort:+e.target.value})}/></label>
    <label>전환 유예 시간 (초)<input disabled={busy} type="number" value={config.graceSeconds} onChange={e=>edit({...config,graceSeconds:+e.target.value})}/></label>
    <label>게임 실행기가 연결할 중계 주소<input disabled={busy} value={config.agentHost} onChange={e=>edit({...config,agentHost:e.target.value})}/></label>
    <label>플레이어용 공개 주소<input disabled={busy} value={config.publicHost} onChange={e=>edit({...config,publicHost:e.target.value})}/></label>
   </div>
   {config.enabled&&first&&(!initial||!eligible(initial))&&<p role="status">{!initial?'저장하려면 최초 중계 실행기를 선택하세요.':'선택한 실행기의 온라인 연결·중계 기능·로컬 허용을 확인해야 저장할 수 있습니다.'}</p>}
   <SaveBar label="직접 중계" dirty={dirty.current} busy={busy} disabled={config.enabled&&first&&(!initial||!eligible(initial))} onReset={()=>adopt(view)} onSave={()=>run(async()=>{const next=await api<View>('/admin/direct-relay','PUT',{config,revision:draftRevision.current});if(alive.current){setView(next);adopt(next);}})}/>
   <p>설치 화면에서 “관리 웹 소유자의 TCP 중계 사용 허용”을 먼저 켜세요. 공유기·방화벽·DNS는 자동으로 변경하지 않습니다.</p>
   {!first&&<>
    <label>새 중계 실행기<select disabled={busy} aria-label="새 중계 실행기" value={runner} onChange={e=>setRunner(e.target.value)}><option value="">선택하세요</option>{options()}</select></label>
    <div className="actions">
     <button disabled={busy||!view.config.enabled||!candidate||!eligible(candidate)||runner===view.config.relayRunnerId||dirty.current} onClick={()=>run(()=>api('/admin/direct-relay/prepare','POST',{runnerId:runner,agentHost:config.agentHost,publicHost:config.publicHost}))}>새 중계 준비</button>
     <button disabled={busy||dirty.current||view.phase!=='CANDIDATE_READY'} onClick={()=>run(()=>api('/admin/direct-relay/commit','POST'))}>준비된 중계로 전환</button>
     <button disabled={busy||dirty.current||!view.config.candidateRunnerId} onClick={()=>run(()=>api('/admin/direct-relay/cancel','POST'))}>준비 취소</button>
    </div>
   </>}
   {view.relays.map(r=><div className="member" key={r.runnerId}>
    <h3>{r.name}</h3><p>연결: {r.connected?'온라인':'오프라인'} · 로컬 허용: {!r.capable?'미확인 (상태 보고 대기)':r.consentAllowed?'허용':'거부'} · 활성: {r.active?'켜짐':'꺼짐'} · 준비: {r.ready?'완료':'대기'} · 접속 {r.streams}개</p>
    <h4>공유기 포트포워딩 입력값</h4><table><thead><tr><th>용도</th><th>외부 TCP 포트</th><th>내부 주소</th><th>내부 TCP 포트</th></tr></thead><tbody>{r.router.map((row,i)=><tr key={i}><td>{row.label}</td><td>{row.externalPort}</td><td>{row.internalAddress||'상태 보고 대기'}</td><td>{row.internalPort}</td></tr>)}</tbody></table>
    {Object.entries(r.diagnostics).map(([key,d])=><p key={key}>{{consent:'로컬 허용',portConflict:'포트 상태',agentConnection:'실행기 연결',dnsTarget:'DNS 대상',externalReachability:'외부 도달 여부'}[key]||key}: {d.status==='UNVERIFIED'?'미검증':d.status} · {d.detail}</p>)}
    <button disabled={busy||!eligible(r)} onClick={()=>run(async()=>{const result=await api<{status:string;detail:string}>('/admin/direct-relay/external-check','POST',{runnerId:r.runnerId});setNotice('관리 서버 위치에서의 접속 시험: '+(result.status==='REACHABLE_FROM_MANAGER'?'연결 가능':result.status==='NOT_REACHABLE_FROM_MANAGER'?'연결 불가':'미검증')+' · '+result.detail);},false)}>관리 서버 위치에서 연결 검사</button>
    <button disabled={busy||dirty.current||!r.active} onClick={()=>{if(confirm(r.impactIfCut+'\n즉시 차단할까요?'))run(()=>api('/admin/direct-relay/cut','POST',{runnerId:r.runnerId}));}}>즉시 차단</button>
   </div>)}
   {view.agents.map(a=><div key={a.runnerId}><h3>{a.name} 연결</h3>{a.relays.map(r=><p key={r.relayId}>{r.state} {r.reason||''} · {r.streams}개</p>)}</div>)}
  </>}
 </section>;
}
