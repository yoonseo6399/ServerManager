import {useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
type Config={provider:string;credentialRef:string;model:string;reasoning:string;parallelism:number;maxTurns:number;tokenBudget:number};
type View={config:Config;keyConfigured:boolean;keys:{id:string;project:string;enabled:boolean}[]};
export function MainAgentSettings(){
 const auth=useAuth();const [view,setView]=useState<View|null>(null),[config,setConfig]=useState<Config>({provider:'OPENAI',credentialRef:'pool:*',model:'gpt-6.1-sol',reasoning:'medium',parallelism:3,maxTurns:12,tokenBudget:120000});
 const [key,setKey]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function load(){const result=await api<View>('/admin/main-agent');setView(result);setConfig(result.config);}
 useEffect(()=>{load().catch(e=>setError(e.message));},[]);
 async function save(){setBusy(true);setError('');try{
  const send=()=>api('/admin/main-agent','PUT',{config,key:key.trim()||null});
  try{await send();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await send();}
  setKey('');await load();setNotice('저장했습니다. 다음 메인 작업부터 적용됩니다.');
 }catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <section className="discord-settings" aria-labelledby="main-agent-heading"><h2 id="main-agent-heading">메인 에이전트</h2>
 <p><code>/cloud-agent</code>는 선택한 클라우드 메인 모델이 서브 작업을 분배·병합하는 명령입니다. <code>/agent</code>는 로컬 프로젝트 에이전트이며, Gemini를 직접 실행하려면 <code>/sub-agent</code>를 사용하세요.</p>
 {error&&<p role="alert" className="alert error-banner">{error}</p>}{notice&&<p role="status" className="notice alert">{notice}</p>}
 <p>OpenAI API 키: {view?.keyConfigured?'설정됨':'미설정'}</p>
 <form onSubmit={e=>{e.preventDefault();save();}}><fieldset className="discord-fieldset" disabled={busy||!view}>
 <label>제공자<select value={config.provider} onChange={e=>setConfig({...config,provider:e.target.value,model:e.target.value==='GEMINI'?'gemini-3.8-flash':'gpt-6.1-sol'})}><option value="OPENAI">OpenAI</option><option value="GEMINI">Gemini</option></select></label>
 {config.provider==='GEMINI'&&<label>Gemini 키<select value={config.credentialRef} onChange={e=>setConfig({...config,credentialRef:e.target.value})}><option value="pool:*">사용 가능한 모든 키</option>{view?.keys.map(k=><option key={k.id} value={'key:'+k.id} disabled={!k.enabled}>{k.id} · {k.project}</option>)}{[...new Set(view?.keys.map(k=>k.project))].map(p=><option key={p} value={'project:'+p}>프로젝트: {p}</option>)}</select><span>서브 에이전트와 같은 프로젝트·모델의 사용 한도를 공유합니다.</span></label>}
 <label className="security-label">OpenAI API 키<input type="password" autoComplete="new-password" spellCheck={false} value={key} placeholder={view?.keyConfigured?'비워두면 기존 키 유지':'OpenAI API 키'} onChange={e=>setKey(e.target.value)} /></label>
 <div className="discord-rule"><label>모델 ID<input required maxLength={80} value={config.model} onChange={e=>setConfig({...config,model:e.target.value})}/></label>
 <label>추론 강도{config.provider==='GEMINI'?<span>Gemini 모델의 기본값으로 자동 관리</span>:<select value={config.reasoning} onChange={e=>setConfig({...config,reasoning:e.target.value})}>{['auto','none','low','medium','high','xhigh','max'].map(v=><option key={v} value={v}>{v}</option>)}</select>}</label>
 <label>동시 서브 작업<input type="number" min={1} max={3} value={config.parallelism} onChange={e=>setConfig({...config,parallelism:Number(e.target.value)})}/></label></div>
 <div className="discord-rule"><label>메인 호출 상한<input type="number" min={1} max={20} value={config.maxTurns} onChange={e=>setConfig({...config,maxTurns:Number(e.target.value)})}/></label>
 <label>메인 작업 토큰 예산<input type="number" min={1000} max={1000000} value={config.tokenBudget} onChange={e=>setConfig({...config,tokenBudget:Number(e.target.value)})}/></label></div>
 <p className="muted">모델별 지원 추론 강도와 API 접근 권한이 다릅니다. auto는 추론 설정을 생략합니다. 요약 지원 모델에서는 추론 요약을 표시하며 내부 사고 과정 전체를 제공하지 않습니다. 토큰 예산은 완료된 응답을 기준으로 확인하므로 한 응답만큼 초과할 수 있습니다. OpenAI API 요금은 Gemini와 별도입니다.</p>
 <button type="submit">메인 에이전트 설정 저장</button></fieldset></form></section>;
}
