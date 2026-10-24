import {useEffect,useRef,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
import {SaveBar} from './SaveBar';
type UdpStatus={state:string;connections:number;dropped:number;message:string};
type VoiceRoute={instanceId:string;publicPort:number;localPort:number};
type VoiceServer={runnerId?:string|null;instanceId:string;name:string;capable:boolean;localStatus?:UdpStatus|null};
type Relay={voicechatCapable?:boolean;voicechat?:Record<string,UdpStatus>;sableUdpCapable?:boolean;sableUdp?:UdpStatus|null;runnerId:string;name:string;consentAllowed:boolean;active:boolean;ready:boolean;connected:boolean;capable:boolean;streams:number;impactIfCut:string;router:{label:string;externalPort:number;internalAddress:string;internalPort:number;protocol?:string}[];diagnostics:Record<string,{status:string;detail:string}>};
type Config={voicechat?:VoiceRoute[];sableUdpEnabled?:boolean;enabled:boolean;relayRunnerId:string|null;candidateRunnerId:string|null;securePort:number;agentHost:string;publicHost:string;graceSeconds:number;retiring:Record<string,number>;relayHosts:Record<string,{agentHost:string;publicHost:string}>};
type View={voicechatServers?:VoiceServer[];revision:number;phase:string;message:string;gamePort?:number;sableUdpBlockers?:string[];config:Config;relays:Relay[];agents:{runnerId:string;name:string;relays:{relayId:string;state:string;reason:string|null;streams:number}[]}[]};
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
 const udpTarget=initial||view?.relays.find(r=>r.runnerId===view.config.relayRunnerId);
 const candidate=view?.relays.find(r=>r.runnerId===runner);
 const options=()=>view?.relays.map(r=><option key={r.runnerId} value={r.runnerId} disabled={!eligible(r)}>{r.name}{availability(r)}</option>);
 return <section className="settings-panel" aria-label="직접 TCP 중계">
  <h2>직접 중계 · 게임 / 음성</h2><p>플레이어 → 중계 실행기 → 게임 실행기 → Minecraft. 관리 Mac은 게임 데이터를 전달하지 않습니다.</p>
  {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
  <p role="status">{view?`${view.phase} · ${view.message}`:'중계 상태를 불러오는 중입니다…'}</p>
  {config&&view&&<>
   <p aria-label="공유 게임 접속 포트">플레이어 접속·SRV 포트: <strong>{view.gamePort??view.relays[0]?.router[0]?.externalPort??'미확인'}</strong>. 아래 ‘공통 접속 주소·포트·DNS’의 공유 게임 포트에서 변경합니다. 실행기 연결 포트와는 별개입니다.</p>
   {first?<>
    <label>최초 중계 실행기<select disabled={busy} aria-label="최초 중계 실행기" value={config.relayRunnerId||''} onChange={e=>edit({...config,relayRunnerId:e.target.value||null})}><option value="">선택하세요</option>{options()}</select></label>
    <p>중계 실행기를 선택하고 ‘직접 중계 사용’을 켠 뒤 변경 사항을 저장하세요. 아직 상태를 보고하지 않은 실행기도 목록에 표시하며, 확인 전에는 선택할 수 없습니다.</p>
   </>:<p>현재 중계 실행기: <strong>{view.relays.find(r=>r.runnerId===view.config.relayRunnerId)?.name||view.config.relayRunnerId}</strong></p>}
   {!view.relays.length&&<p role="status">등록된 활성 실행기가 없습니다. 서버 실행기에서 먼저 등록하세요.</p>}
   <label className="check-label"><input type="checkbox" checked={config.enabled} disabled={busy} onChange={e=>edit({...config,enabled:e.target.checked})}/>직접 중계 사용</label>
   <h3>전송 방식</h3><p>게임 TCP 중계는 기본으로 사용합니다. Sable 게임 UDP와 Simple Voice Chat 음성 UDP는 각각 선택합니다.</p>
   <label className="check-label"><input type="checkbox" checked={!!config.sableUdpEnabled} disabled={busy||!config.sableUdpEnabled&&(!udpTarget?.sableUdpCapable||!!view.sableUdpBlockers?.length)} onChange={e=>edit({...config,sableUdpEnabled:e.target.checked})}/>직접 중계에 Sable UDP 추가</label>
   {!udpTarget?.sableUdpCapable&&<p className="muted">중계 실행기를 선택하고 실행기·상주 게임호스트의 직접 UDP 지원 보고를 기다리세요.</p>}
   {!!view.sableUdpBlockers?.length&&<p role="status">직접 UDP 업데이트·연결 확인 필요: {view.sableUdpBlockers.join(', ')}</p>}
   <p className="muted">중계 컴퓨터에 UDP {view.gamePort??'공유 게임 포트'}도 포워딩해야 합니다. UDP 인증은 해당 플레이어의 실제 TCP 연결과 묶습니다. 게임 데이터는 관리 Mac을 거치지 않으며 중계↔게임 실행기 구간은 암호화된 TCP 터널을 사용합니다. 회선 손실 시 지연이 늘 수 있습니다.</p>
   <p className="muted">NeoForge 1.21.1 · Sable 2.0.6 지원. 보조 모드와 등록 연결은 서버 시작 때 준비합니다. 이미 실행 중인 게임은 다음 정상 시작부터 적용되며, 미연결 시 TCP 대체 통신을 사용합니다.</p>
   <h3>Simple Voice Chat · 음성 UDP</h3>
   <p>음성은 게임 접속 포트·SRV와 별개입니다. 서버마다 공개 UDP 포트를 하나씩 사용합니다. 기본값은 24454이며, 중계 컴퓨터의 공유기·방화벽에서 아래 UDP 포트도 허용하세요. Sable UDP를 끄면 게임 TCP와 같은 번호의 공개 UDP 포트를 음성에 사용할 수도 있습니다. 중계와 게임이 같은 컴퓨터이면 내부 음성 포트는 다르게 지정합니다.</p>
   {!view.voicechatServers?.length&&<p>공통 접속 주소·포트·DNS를 켜고 서버의 게임 실행기를 지정하면 음성 중계를 설정할 수 있습니다.</p>}
   {(view.voicechatServers||[]).map(server=>{
    const entry=config.voicechat?.find(v=>v.instanceId===server.instanceId);
    const change=(patch:Partial<VoiceRoute>)=>edit({...config,voicechat:(config.voicechat||[]).map(v=>v.instanceId===server.instanceId?{...v,...patch}:v)});
    return <div className="member" key={server.instanceId}>
     <label className="check-label"><input type="checkbox" aria-label={`${server.name} Simple Voice Chat 중계`} checked={!!entry} disabled={busy||!entry&&(!server.capable||!udpTarget?.voicechatCapable)} onChange={e=>{
      if(!e.target.checked){edit({...config,voicechat:(config.voicechat||[]).filter(v=>v.instanceId!==server.instanceId)});return;}
      let port=24454;while((config.voicechat||[]).some(v=>v.publicPort===port||v.localPort===port)||port===view.gamePort)port++;
      let localPort=port;if(server.runnerId===config.relayRunnerId){localPort=34454;while(localPort===port||(config.voicechat||[]).some(v=>v.publicPort===localPort||v.localPort===localPort)||localPort===view.gamePort)localPort++;}
      edit({...config,voicechat:[...(config.voicechat||[]),{instanceId:server.instanceId,publicPort:port,localPort}]});
     }}/>{server.name} 음성 중계</label>
     {(!server.capable||!udpTarget?.voicechatCapable)&&<p className="muted">중계·게임 실행기와 상주 게임호스트의 음성 중계 업데이트·연결 확인이 필요합니다.</p>}
     {entry&&<>
      <label>공개 음성 UDP 포트<input aria-label={`${server.name} 공개 음성 UDP 포트`} type="number" min={1024} max={65535} disabled={busy} value={entry.publicPort} onChange={e=>change({publicPort:+e.target.value})}/></label>
      <label>게임 실행기 내부 음성 UDP 포트<input aria-label={`${server.name} 내부 음성 UDP 포트`} type="number" min={1024} max={65535} disabled={busy} value={entry.localPort} onChange={e=>change({localPort:+e.target.value})}/></label>
      <p>플레이어 음성 주소: <strong>{config.relayHosts?.[config.relayRunnerId||'']?.publicHost||config.publicHost||'공개 중계 주소'}:{entry.publicPort}</strong> → 게임 실행기 127.0.0.1:{entry.localPort}</p>
      <p role="status">설정 적용: {server.localStatus?.state||'다음 정상 서버 시작 때 준비'} · {server.localStatus?.message||'Simple Voice Chat 모드를 설치하세요. 음성 설정은 백업 후 자동 반영하며 서버를 자동 재시작하지 않습니다.'}</p>
     </>}
    </div>;
   })}
   <p className="muted">모드는 별도로 설치하세요. 음성 포트·공개 주소·로컬 바인딩은 다음 서버 시작 때 자동 준비하고, 해제 후 다음 시작에는 기존 값을 복원합니다. 중계↔게임 실행기는 기존 암호화 TCP 터널을 사용하므로 회선 손실 시 음성 지연이 늘 수 있습니다.</p>
   <div className="form-grid">
    <label>실행기 연결 포트<input disabled={busy} type="number" min={1024} max={65535} value={config.securePort} onChange={e=>edit({...config,securePort:+e.target.value})}/></label>
    <label>전환 유예 시간 (초)<input disabled={busy} type="number" value={config.graceSeconds} onChange={e=>edit({...config,graceSeconds:+e.target.value})}/></label>
    <label>게임 실행기가 연결할 중계 주소<input disabled={busy} value={config.agentHost} onChange={e=>edit({...config,agentHost:e.target.value})}/></label>
    <label>플레이어용 공개 주소<input disabled={busy} value={config.publicHost} onChange={e=>edit({...config,publicHost:e.target.value})}/></label>
   </div>
   {config.enabled&&first&&(!initial||!eligible(initial))&&<p role="status">{!initial?'저장하려면 최초 중계 실행기를 선택하세요.':'선택한 실행기의 온라인 연결·중계 기능·로컬 허용을 확인해야 저장할 수 있습니다.'}</p>}
   <SaveBar label="직접 중계" dirty={dirty.current} busy={busy} disabled={config.enabled&&first&&(!initial||!eligible(initial))} onReset={()=>adopt(view)} onSave={()=>run(async()=>{const next=await api<View>('/admin/direct-relay','PUT',{config,revision:draftRevision.current});if(alive.current){setView(next);adopt(next);}})}/>
   <p>설치 화면에서 “관리 웹 소유자의 게임 중계 사용 허용 (TCP / Sable UDP)”을 먼저 켜세요. 기존 버전에서는 “TCP 중계 사용 허용”으로 표시됩니다. 공유기·방화벽·DNS는 자동으로 변경하지 않습니다.</p>
   {!first&&<>
    {!!config.voicechat?.length&&<p className="muted">음성 공개 주소가 바뀌는 중계 전환은 서버를 다시 시작해야 새 주소가 모드에 반영됩니다. 전환 유예 동안 기존 주소를 유지하거나 다음 정상 시작 시점에 맞춰 전환하세요.</p>}
    <label>새 중계 실행기<select disabled={busy} aria-label="새 중계 실행기" value={runner} onChange={e=>setRunner(e.target.value)}><option value="">선택하세요</option>{options()}</select></label>
    <div className="actions">
     <button disabled={busy||!view.config.enabled||!candidate||!eligible(candidate)||runner===view.config.relayRunnerId||dirty.current} onClick={()=>run(()=>api('/admin/direct-relay/prepare','POST',{runnerId:runner,agentHost:config.agentHost,publicHost:config.publicHost}))}>새 중계 준비</button>
     <button disabled={busy||dirty.current||view.phase!=='CANDIDATE_READY'} onClick={()=>run(()=>api('/admin/direct-relay/commit','POST'))}>준비된 중계로 전환</button>
     <button disabled={busy||dirty.current||!view.config.candidateRunnerId} onClick={()=>run(()=>api('/admin/direct-relay/cancel','POST'))}>준비 취소</button>
    </div>
   </>}
   {view.relays.map(r=><div className="member" key={r.runnerId}>
    <h3>{r.name}</h3><p>연결: {r.connected?'온라인':'오프라인'} · 로컬 허용: {!r.capable?'미확인 (상태 보고 대기)':r.consentAllowed?'허용':'거부'} · 활성: {r.active?'켜짐':'꺼짐'} · 준비: {r.ready?'완료':'대기'} · 접속 {r.streams}개</p>
    {config.sableUdpEnabled&&<p role="status">Sable UDP: {r.sableUdp?.state||'보고 대기'} · 확인된 접속 {r.sableUdp?.connections||0}개 · {r.sableUdp?.message||'UDP 왕복은 아직 확인되지 않았습니다'}</p>}
    {Object.entries(r.voicechat||{}).map(([id,status])=><p role="status" key={id}>음성 · {view.voicechatServers?.find(v=>v.instanceId===id)?.name||id}: {status.state} · 왕복 확인 {status.connections}개 · {status.message}</p>)}
    <h4>공유기 포트포워딩 입력값</h4><div className="relay-port-scroll"><table className="relay-port-table"><thead><tr><th>용도</th><th>프로토콜</th><th>외부 포트</th><th>내부 주소</th><th>내부 포트</th></tr></thead><tbody>{r.router.map((row,i)=><tr key={i}><td>{row.label}</td><td>{row.protocol||'TCP'}</td><td>{row.externalPort}</td><td>{row.internalAddress||'상태 보고 대기'}</td><td>{row.internalPort}</td></tr>)}</tbody></table></div>
    {Object.entries(r.diagnostics).map(([key,d])=><p key={key}>{{consent:'로컬 허용',portConflict:'포트 상태',agentConnection:'실행기 연결',dnsTarget:'DNS 대상',externalReachability:'외부 도달 여부'}[key]||key}: {d.status==='UNVERIFIED'?'미검증':d.status} · {d.detail}</p>)}
    <button disabled={busy||!eligible(r)} onClick={()=>run(async()=>{const result=await api<{status:string;detail:string}>('/admin/direct-relay/external-check','POST',{runnerId:r.runnerId});setNotice('관리 서버 위치에서의 접속 시험: '+(result.status==='REACHABLE_FROM_MANAGER'?'연결 가능':result.status==='NOT_REACHABLE_FROM_MANAGER'?'연결 불가':'미검증')+' · '+result.detail);},false)}>관리 서버 위치에서 TCP 연결 검사</button>
    <button disabled={busy||dirty.current||!r.active} onClick={()=>{if(confirm(r.impactIfCut+'\n즉시 차단할까요?'))run(()=>api('/admin/direct-relay/cut','POST',{runnerId:r.runnerId}));}}>즉시 차단</button>
   </div>)}
   <p className="muted">연결 검사는 TCP 포트만 검사합니다. UDP는 실제 왕복이 확인되어야 연결됨으로 표시합니다. 음성 왕복 표시는 패킷 전달 확인이며, 마이크·오디오 품질까지 검증한 결과는 아닙니다.</p>
   {view.agents.map(a=><div key={a.runnerId}><h3>{a.name} 연결</h3>{a.relays.map(r=><p key={r.relayId}>{r.state} {r.reason||''} · {r.streams}개</p>)}</div>)}
  </>}
 </section>;
}
