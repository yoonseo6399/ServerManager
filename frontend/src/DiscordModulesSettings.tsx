import {useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
import {SaveBar} from './SaveBar';
type Module={id:string;label:string;enabled:boolean};
type View={revision:number;warning:string;modules:Module[]};

export function DiscordModulesSettings(){
 const auth=useAuth();const [view,setView]=useState<View|null>(null),[draft,setDraft]=useState<Module[]>([]);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function load(){const v=await api<View>('/admin/discord/modules');setView(v);setDraft(v.modules);}
 useEffect(()=>{load().catch(e=>setError(e.message));},[]);
 const dirty=!!view&&JSON.stringify(draft)!==JSON.stringify(view.modules);
 async function save(){if(!view)return;setBusy(true);setError('');setNotice('');try{
  const submit=()=>api<View>('/admin/discord/modules','PUT',{revision:view.revision,disabled:draft.filter(m=>!m.enabled).map(m=>m.id)});
  let v:View;try{v=await submit();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);v=await submit();}
  setView(v);setDraft(v.modules);setNotice('모듈 설정을 적용했습니다.');
 }catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <section aria-labelledby="discord-modules-heading"><h3 id="discord-modules-heading">봇 기능 모듈</h3>
 <p className="muted">기능별로 켜고 끌 수 있습니다. 꺼진 기능의 명령·버튼·대화 호출은 차단하며, 음악을 끄면 재생을 종료하고 음성 채널에서 나갑니다.</p>
 {view?.warning&&<p role="alert">{view.warning}</p>}{error&&<p role="alert" className="alert error-banner">{error}</p>}{notice&&<p role="status">{notice}</p>}
 <fieldset disabled={busy||!view}>{draft.map(m=><label className="check-label" key={m.id}><input type="checkbox" checked={m.enabled} onChange={e=>{setDraft(draft.map(x=>x.id===m.id?{...x,enabled:e.target.checked}:x));setNotice('');}}/>{m.label}</label>)}
 <div className="actions"><button type="button" onClick={()=>{setError('');load().catch(e=>setError(e.message));}}>다시 불러오기</button></div></fieldset>
 <SaveBar label="봇 모듈 설정" dirty={dirty||Boolean(view?.warning)} busy={busy} error={error} saveLabel="모듈 설정 저장" onSave={save} onReset={()=>{if(view)setDraft(view.modules);setError('');}}/>
 </section>;
}
