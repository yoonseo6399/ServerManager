import {useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
import {SaveBar} from './SaveBar';
type Root={id:string;name:string;path:string};
type Policy={roots:Root[];executables:string[];engine:string};
type FileView={path:string;content:string;digest:string};
type Task={id:string;purpose:string;computer:string;workspace:string;stageDirectory:string;model:string;files:FileView[];changes:{path:string;before:string;after:string}[];command:{program:string;args:string[]}|null;state:string;approval:string;expiresAt:string;review:{allow:boolean;risk:string;reason:string}|null;message:string;logs:string[];cloudDisclosure:string;backups:string[]};
type View={policy:Policy;tasks:Task[];aider:{available:boolean;message:string}};
const terminal=(t:Task)=>['APPLIED','CANCELLED','FAILED'].includes(t.state);
export function AssistantPanel(){
 const auth=useAuth();const [policy,setPolicy]=useState<Policy>({roots:[],executables:[],engine:'native'}),[savedPolicy,setSavedPolicy]=useState<Policy>({roots:[],executables:[],engine:'native'}),[tasks,setTasks]=useState<Task[]>([]),[loaded,setLoaded]=useState(false);
 const [rootId,setRootId]=useState(''),[purpose,setPurpose]=useState(''),[files,setFiles]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[code,setCode]=useState('');
 const [query,setQuery]=useState(''),[source,setSource]=useState('gmail'),[from,setFrom]=useState(''),[until,setUntil]=useState(''),[googleConsent,setGoogleConsent]=useState(false),[answer,setAnswer]=useState('');
 async function load(initial=false){const view=await api<View>('/admin/assistant');setTasks(view.tasks);if(initial){setPolicy(view.policy);setSavedPolicy(view.policy);setRootId(view.policy.roots[0]?.id??'');setLoaded(true);}}
 useEffect(()=>{load(true).catch(e=>setError(e.message));},[]);
 useEffect(()=>{if(!tasks.some(t=>['PREPARING','APPLYING'].includes(t.state)))return;const timer=setInterval(()=>{load().catch(e=>setError(e.message));},2000);return()=>clearInterval(timer);},[tasks]);
 async function run(action:()=>Promise<unknown>){setBusy(true);setError('');try{try{await action();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await action();}await load();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 function root(index:number,field:keyof Root,value:string){setPolicy({...policy,roots:policy.roots.map((r,i)=>i===index?{...r,[field]:value}:r)});}
 return <section className="discord-settings" aria-labelledby="assistant-title"><h2 id="assistant-title">로컬 비서 · 파일과 개인 정보 조회</h2>
 <p>Discord의 <code>/agent</code>는 전체 경로·명령을 다루는 프로젝트 에이전트입니다. <code>/agent setting</code>에서 개인 기본 모델·Thinking·명시적 승인을 설정하고 <code>/agent channel</code>로 대화를 이어가세요. 아래 웹 파일 초안은 기존 제한 모드를 유지하며, 복사본과 클라우드 검토 후 최종 승인으로 반영합니다. 메일·일정 조회도 별도로 사용할 수 있습니다.</p>
 {error&&<p role="alert" className="alert error-banner">{error}</p>}
 <details><summary>파일 접근 권한과 편집 엔진</summary><p>관리 앱 컴퓨터의 기존 텍스트 파일 1~5개, 각 48KB까지 지원합니다. 관련 구간을 골라 작은 변경을 만듭니다. 새 파일·삭제·이동·비밀 값·관리 앱 데이터 접근은 지원하지 않습니다. 파일 접근·수정은 현재 macOS 관리 앱에서만 지원합니다. Linux와 Windows에서는 접근을 차단합니다. 파일 하나의 교체는 원자적이며, 여러 파일 도중 충돌하면 외부 변경을 보존하면서 복구를 시도하고 사본을 남깁니다.</p>
 <fieldset disabled={busy||!loaded}>{policy.roots.map((r,i)=><div className="discord-rule" key={i}>
 <label>폴더 ID<input aria-label={`폴더 ID ${i+1}`} value={r.id} onChange={e=>root(i,'id',e.target.value)}/></label>
 <label>이름<input value={r.name} onChange={e=>root(i,'name',e.target.value)}/></label>
 <label>관리 앱 컴퓨터의 전체 폴더 경로<input value={r.path} onChange={e=>root(i,'path',e.target.value)}/></label>
 <button type="button" onClick={()=>setPolicy({...policy,roots:policy.roots.filter((_,j)=>j!==i)})}>폴더 허용 삭제</button></div>)}
 <button type="button" disabled={policy.roots.length>=10} onClick={()=>setPolicy({...policy,roots:[...policy.roots,{id:'',name:'',path:''}]})}>허용할 폴더 추가</button>
 <label>명령 허용 목록 · 실행 파일의 전체 경로, 한 줄에 하나<textarea value={policy.executables.join('\n')} onChange={e=>setPolicy({...policy,executables:e.target.value.split('\n').map(v=>v.trim()).filter(Boolean)})}/></label>
 <p className="alert">기본은 명령 실행 금지입니다. 허용 목록은 OS 샌드박스가 아닙니다. 승인한 프로그램은 관리 앱 사용자 권한으로 폴더 밖 파일과 네트워크에 접근할 수 있습니다. 디스크의 인증 정보에도 접근할 수 있으므로 신뢰하는 진단 도구만 등록하세요. 쉘·인터프리터·관리자 실행 도구는 등록할 수 없습니다.</p>
 <label>편집 엔진<select value="native" disabled><option value="native">기본 구조화 편집 · 정확한 구문 교체</option><option value="aider">Aider · 설치와 격리 검증 후 제공 예정</option></select></label>
 <p className="muted">Aider는 설치하거나 실행하지 않습니다. 이 버전은 기본 편집 엔진을 사용합니다. 권한 변경은 진행 중인 작업과 승인을 철회합니다.</p>
 <SaveBar label="비서 접근 권한" dirty={JSON.stringify(policy)!==JSON.stringify(savedPolicy)} busy={busy} error={error} saveLabel="접근 권한 저장" onSave={()=>run(async()=>{const saved=await api<Policy>('/admin/assistant/policy','PUT',policy);setPolicy(saved);setSavedPolicy(saved);})} onReset={()=>setPolicy(savedPolicy)}/></fieldset></details>
 <form onSubmit={e=>{e.preventDefault();run(async()=>{await api('/admin/assistant/tasks','POST',{purpose,rootId,files:files.split(',').map(f=>f.trim()).filter(Boolean)});});}}>
 <fieldset disabled={busy||!loaded}><legend>간단한 파일 수정</legend><label>허용된 작업 폴더<select value={rootId} onChange={e=>setRootId(e.target.value)}><option value="">폴더 선택</option>{policy.roots.map(r=><option key={r.id} value={r.id}>{r.name} ({r.id})</option>)}</select></label>
 <label>수정할 기존 파일 · 폴더 안 경로, 쉼표로 구분<input required value={files} onChange={e=>setFiles(e.target.value)} placeholder="notes.txt, src/example.kt"/></label>
 <label>요청과 완료 조건<textarea required maxLength={1500} value={purpose} onChange={e=>setPurpose(e.target.value)}/></label><button disabled={!rootId||!purpose.trim()||!files.trim()}>파일 확인·요청 준비</button></fieldset></form>
 {tasks.map(t=><article key={t.id} className="discord-status" aria-label={`비서 작업 ${t.id}`}><h3>{t.purpose}</h3><p><strong>{t.state}</strong> · {t.model} · {t.computer}</p><p>{t.message}</p><p>작업 폴더: <code>{t.workspace}</code></p><p>{t.cloudDisclosure}</p>
 {t.review&&<p>메인 모델 검토: <strong>{t.review.risk}</strong> · {t.review.reason} 위험 평가는 안전 보증이 아닙니다.</p>}
 <details><summary>승인할 파일 내용과 변경 전후 보기</summary>{t.files.map(f=><div key={f.path}><h4>{f.path}</h4><p>원본 SHA256: <code>{f.digest}</code></p><pre style={{maxHeight:280,overflow:'auto',whiteSpace:'pre-wrap'}}>{f.content}</pre>{t.changes.find(c=>c.path===f.path)&&<><strong>변경 후</strong><pre style={{maxHeight:280,overflow:'auto',whiteSpace:'pre-wrap'}}>{t.changes.find(c=>c.path===f.path)!.after}</pre></>}</div>)}</details>
 {t.command&&<div className="alert"><strong>최종 승인에 포함된 명령</strong><p>프로그램: <code>{t.command.program}</code></p><p>정확한 인자(JSON): <code>{JSON.stringify(t.command.args)}</code></p><p>실행 폴더: <code>{t.stageDirectory}</code></p><p>네트워크와 폴더 밖 접근을 OS에서 차단하지 않습니다. 사용자 권한으로 실행하며 환경 변수는 비웁니다. 명령이 복사본을 수정하면 검토한 파일 변경의 반영을 거부합니다.</p></div>}
 {['CONSENT','REVIEWED'].includes(t.state)&&<p>이 승인은 현재 요청·파일·명령에 한 번만 적용됩니다. 만료: {new Date(t.expiresAt).toLocaleString()}</p>}
 <div className="actions">{['CONSENT','REVIEWED'].includes(t.state)&&<button disabled={busy} onClick={()=>run(()=>api(`/admin/assistant/tasks/${t.id}/approve`,'POST',{token:t.approval,state:t.state}))}>{t.state==='CONSENT'?'위 내용을 모델에 전송하고 초안 만들기':'표시한 변경과 명령 최종 승인'}</button>}
 {!terminal(t)&&<button disabled={busy} onClick={()=>run(()=>api(`/admin/assistant/tasks/${t.id}/cancel`,'POST'))}>거절·취소</button>}
 {terminal(t)&&<button disabled={busy} onClick={()=>run(()=>api(`/admin/assistant/tasks/${t.id}`,'DELETE'))}>기록·임시 복사본 삭제</button>}</div>
 {t.backups.length>0&&<p>복구용 원본: {t.backups.join(', ')}</p>}<details><summary>진행 기록</summary><pre style={{whiteSpace:'pre-wrap'}}>{t.logs.join('\n')}</pre></details></article>)}
 <details><summary>Gmail·캘린더를 로컬 모델로 요약</summary><p>내 계정에서 연결한 Google 계정만 조회합니다. 조회 조건은 사용자가 정하며, 메일·일정의 문장으로 파일·명령 권한을 얻을 수 없습니다. 결과를 선택한 로컬 추론 컴퓨터로 보내며 메인 클라우드 검토에는 보내지 않습니다.</p>
 <fieldset disabled={busy}><label>조회 대상<select value={source} onChange={e=>setSource(e.target.value)}><option value="gmail">Gmail 검색</option><option value="calendar">캘린더 일정</option></select></label>
 {source==='gmail'?<label>Gmail 검색 조건<input value={query} maxLength={500} onChange={e=>setQuery(e.target.value)} placeholder="is:unread newer_than:7d"/></label>:<><label>시작<input type="datetime-local" value={from} onChange={e=>setFrom(e.target.value)}/></label><label>종료<input type="datetime-local" value={until} onChange={e=>setUntil(e.target.value)}/></label></>}
 <label><input type="checkbox" checked={googleConsent} onChange={e=>setGoogleConsent(e.target.checked)}/>조회 결과 일부를 로컬 추론 컴퓨터로 보내 요약하는 데 동의합니다</label>
 <button disabled={!googleConsent} onClick={()=>run(async()=>{const result=await api<{answer:string;notice:string}>('/admin/assistant/google','POST',{source,query,from:from?new Date(from).toISOString():'',until:until?new Date(until).toISOString():'',consent:googleConsent});setAnswer(result.notice+'\n\n'+result.answer);})}>조회·요약</button>{answer&&<pre style={{whiteSpace:'pre-wrap'}}>{answer}</pre>}</fieldset></details>
 <details><summary>Discord 소유자 계정 연결</summary><p>관리 웹 소유자 계정과 Discord 계정을 연결해야 파일 비서를 사용할 수 있습니다. Discord 최고 관리자 역할도 확인합니다. 아래 코드를 <code>/agent link code:코드</code>에 입력하세요. 코드·연결은 앱 재시작 때 사라집니다.</p>
 <button disabled={busy} onClick={()=>run(async()=>{const result=await api<{code:string}>('/admin/assistant/discord-link','POST');setCode(result.code);})}>5분 연결 코드 발급</button>
 <button disabled={busy} onClick={()=>run(async()=>{await api('/admin/assistant/discord-link','DELETE');setCode('');})}>모든 Discord 비서 연결 해제</button>{code&&<p>한 번만 사용할 코드: <code>{code}</code></p>}</details>
 </section>;
}
