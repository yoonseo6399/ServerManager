import {useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
type Capability='ALLOWED'|'DENIED'|'UNKNOWN';
type Runner={id:string;name:string;online:boolean;capability:Capability};
type Config={runnerId:string|null;model:string};
type Model={id:string;label:string;supportsThinking:boolean};
type View={config:Config;runners:Runner[];models:Model[]};
type Probe={reachable:boolean;version:string|null;models:{name:string;sizeBytes:number}[];loaded:string[];tags:Record<string,string>;installed:Record<string,boolean>;message:string};
const capabilityText:Record<Capability,string>={ALLOWED:'로컬 LLM 사용 가능',DENIED:'허용 안 됨 · 실행기 컴퓨터에서 llm-enabled 설정 필요',UNKNOWN:'오프라인이거나 0.5.0 미만'};
const gb=(bytes:number)=>`${(bytes/1024/1024/1024).toFixed(1)}GB`;
export function QwenSettings(){
 const auth=useAuth();const [view,setView]=useState<View|null>(null),[runnerId,setRunnerId]=useState(''),[model,setModel]=useState('14b');
 const [probe,setProbe]=useState<Probe|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function load(){const result=await api<View>('/admin/qwen');setView(result);setRunnerId(result.config.runnerId??'');setModel(result.config.model);}
 useEffect(()=>{load().catch(e=>setError(e.message));},[]);
 const runner=view?.runners.find(r=>r.id===runnerId);const ready=!!runner&&runner.online&&runner.capability==='ALLOWED';
 // Saving and unloading change what runs on someone's computer, so they need a fresh passkey check like other admin settings.
 async function run<T>(fn:()=>Promise<T>):Promise<T|undefined>{setBusy(true);setError('');setNotice('');try{try{return await fn();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);return await fn();}}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 async function save(){const result=await run(()=>api<{unloaded:boolean|null}>('/admin/qwen','PUT',{runnerId:runnerId||null,model}));if(!result)return;await load();setNotice(result.unloaded===true?'저장했습니다. 이전 설정의 로컬 모델은 메모리에서 내렸습니다.':'저장했습니다. 다음 /agent 요청부터 적용됩니다. 개인 기본 모델은 /agent setting에서 선택할 수 있습니다.');}
 async function check(){const result=await run(()=>api<Probe>('/admin/qwen/probe','POST',{runnerId}));if(result)setProbe(result);}
 async function unload(){const result=await run(()=>api<Probe>('/admin/qwen/unload','POST',{runnerId}));if(result){setProbe(result);setNotice('로컬 모델을 메모리에서 내렸습니다.');}}
 return <section className="discord-settings" aria-labelledby="qwen-heading"><h2 id="qwen-heading">로컬 LLM (Ollama)</h2>
 <p><code>/agent</code>의 추론 컴퓨터와 공통 기본 모델을 고릅니다. 프로젝트 도구·명령은 관리 앱 컴퓨터에서 실행합니다. Thinking 지원 모델은 기본 켜짐이며 Discord 옵션으로 끌 수 있습니다. Coder는 Thinking을 지원하지 않아 자동으로 끕니다. 지원 모델은 하나씩 메모리에 올리며 모델을 바꾸면 이전 모델을 먼저 내립니다. 새 모델은 관리 앱과 추론 실행기를 모두 업데이트한 후 해당 실행기에서 ollama pull로 설치하세요.</p>
 {error&&<p role="alert" className="alert error-banner">{error}</p>}{notice&&<p role="status" className="notice alert">{notice}</p>}
 <form onSubmit={e=>{e.preventDefault();save();}}><fieldset className="discord-fieldset" disabled={busy||!view}>
 <div className="discord-rule"><label>실행 컴퓨터<select value={runnerId} onChange={e=>{setRunnerId(e.target.value);setProbe(null);}}>
  <option value="">자동 (허용된 첫 실행기, 없으면 관리 앱 컴퓨터)</option>
  {view?.runners.map(r=><option key={r.id} value={r.id}>{r.name} · {r.online?capabilityText[r.capability]:'오프라인'}</option>)}
 </select></label>
 <label>모델<select value={model} onChange={e=>setModel(e.target.value)}>{view?.models?.map(m=><option key={m.id} value={m.id}>{m.label}</option>)}</select></label></div>
 {model==='gemma4-31b'&&<p className="muted">31B는 24GB 컴퓨터에서 메모리 여유가 작을 수 있습니다. 긴 문맥·다른 앱 동시 실행 시 메모리와 스왑을 확인하세요.</p>}
 {view?.models?.find(m=>m.id===model)?.supportsThinking===false&&<p className="muted">선택한 모델은 Thinking 미지원 · 자동 꺼짐</p>}
 {runnerId&&!ready&&<p className="muted">{runner?(runner.online?capabilityText[runner.capability]:'실행기가 오프라인입니다.'):'등록된 실행기가 아닙니다.'} 지정한 실행기를 쓸 수 없으면 관리 앱 컴퓨터로 대체하지 않고 요청이 실패합니다.</p>}
 {!runnerId&&<p className="muted">환경 확인과 메모리 해제는 실행기를 직접 지정했을 때 사용할 수 있습니다.</p>}
 <div className="actions"><button type="submit">로컬 LLM 설정 저장</button>
 <button type="button" disabled={!ready} onClick={check}>Ollama 환경 확인</button>
 <button type="button" disabled={!ready||!probe?.loaded.length} onClick={unload}>메모리에서 내리기</button></div>
 {probe&&<div className="discord-status" role="status"><strong>{probe.reachable?`Ollama ${probe.version??''} 실행 중`:'Ollama에 연결하지 못했습니다'}</strong><span>{probe.message}</span>
  <ul>{Object.entries(probe.tags).map(([size,tag])=><li key={size}>{view?.models?.find(m=>m.id===size)?.label??size} · <code>{tag}</code> · {probe.installed[size]?'설치됨':'설치 안 됨'}{probe.loaded.includes(tag)?' · 메모리에 올라감':''}</li>)}</ul>
  {probe.models.length>0&&<p className="muted">설치된 모델: {probe.models.map(m=>`${m.name} (${gb(m.sizeBytes)})`).join(', ')}</p>}</div>}
 </fieldset></form></section>;
}
