import {useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

type PoolKey={id:string;project:string;rpm:number;tpm:number;rpd:number;enabled:boolean};
type PoolView={keys:PoolKey[];usage:string;localUsageOnly:boolean};
const empty:PoolKey={id:'',project:'',rpm:5,tpm:250000,rpd:20,enabled:true};
export function AgentKeySettings(){
 const auth=useAuth();
 const [view,setView]=useState<PoolView|null>(null),[form,setForm]=useState<PoolKey>(empty),[secret,setSecret]=useState('');
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const existing=!!view?.keys.some(k=>k.id===form.id);
 async function load(){setView(await api<PoolView>('/admin/agent-keys'));}
 useEffect(()=>{load().catch(e=>setError(e.message));},[]);
 async function run(fn:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{try{await fn();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await fn();}}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <section className="discord-settings" aria-labelledby="agent-keys-heading"><h2 id="agent-keys-heading">루오노타르 Gemini API 키</h2>
 <p>프로젝트별 RPM·입력 TPM·RPD를 공유하며 사용 가능한 키를 선택합니다. 같은 Google 프로젝트의 키에는 같은 프로젝트 ID를 입력하세요.</p>
 {error&&<p role="alert" className="alert error-banner">{error}</p>}{notice&&<p role="status" className="notice alert">{notice}</p>}
 {view?.keys.map(k=><div className="discord-rule" key={k.id}><strong>{k.id} · {k.project} · {k.enabled?'활성':'비활성'}</strong><span>RPM {k.rpm} / 입력 TPM {k.tpm.toLocaleString()} / RPD {k.rpd}</span><button type="button" disabled={busy} onClick={()=>{setForm({...k});setSecret('');}}>설정 편집</button><button type="button" disabled={busy} onClick={()=>run(async()=>{await api('/admin/agent-keys/'+encodeURIComponent(k.id),'DELETE');await load();if(form.id===k.id){setForm(empty);setSecret('');}setNotice('키 등록을 해제했습니다.');})}>등록 해제</button></div>)}
 <form onSubmit={e=>{e.preventDefault();run(async()=>{await api('/admin/agent-keys','PUT',{...form,key:secret.trim()||null});setSecret('');await load();setNotice('저장했습니다. 다음 API 요청부터 적용됩니다.');});}}>
 <fieldset disabled={busy||!view} className="discord-fieldset">
 <div className="discord-rule"><label>키 이름<input required pattern="[A-Za-z0-9_-]{1,40}" maxLength={40} value={form.id} onChange={e=>setForm({...form,id:e.target.value})}/></label><label>Google 프로젝트 ID<input required pattern="[A-Za-z0-9_-]{1,80}" maxLength={80} value={form.project} onChange={e=>{const project=e.target.value;const same=view?.keys.find(k=>k.project===project);setForm({...form,project,...(same?{rpm:same.rpm,tpm:same.tpm,rpd:same.rpd}:{})});}}/></label></div>
 <label className="security-label">API 키<input type="password" autoComplete="new-password" spellCheck={false} maxLength={512} required={!existing} value={secret} placeholder={existing?'비워두면 저장된 키 유지':'새 API 키'} onChange={e=>setSecret(e.target.value)}/></label>
 <div className="discord-rule">{(['rpm','tpm','rpd'] as const).map(name=><label key={name}>{name==='tpm'?'입력 TPM':name.toUpperCase()}<input required type="number" min={1} max={name==='rpm'?100000:name==='tpm'?1000000000:100000000} value={form[name]} onChange={e=>setForm({...form,[name]:Number(e.target.value)})}/></label>)}</div>
 <p className="muted">AI Studio의 실제 한도를 입력하세요. 표시된 초기값은 자동 조회한 값이 아닙니다. 한도를 바꾸면 같은 프로젝트의 다른 키에도 적용됩니다.</p>
 <label className="check-label"><input type="checkbox" checked={form.enabled} onChange={e=>setForm({...form,enabled:e.target.checked})}/>사용할 키</label>
 <div className="actions"><button type="submit">{existing?'키 설정 저장':'키 추가'}</button><button type="button" onClick={()=>{setForm(empty);setSecret('');}}>새 키 입력</button></div>
 </fieldset></form>
 <h3>프로젝트별 사용량</h3><pre style={{whiteSpace:'pre-wrap'}}>{view?.usage||'확인 중'}</pre><p className="muted">이 봇에서 기록한 사용량입니다. 다른 앱의 사용과 과거 사용량은 포함되지 않습니다. 일일 요청은 미국 태평양 시간 자정에 초기화됩니다.</p>
 <button type="button" disabled={busy} onClick={()=>run(load)}>사용량 새로고침</button>
 </section>;
}
