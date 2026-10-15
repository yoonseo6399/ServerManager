import React,{memo,createContext,useContext,useEffect,useRef,useState} from 'react';
import {ShieldCheck,KeyRound,LogIn} from 'lucide-react';
import {GoogleConnectionSettings} from './GoogleConnectionSettings';
import {SystemLogPanel} from './SystemLogPanel';
import {DesktopSettings} from './DesktopSettings';
import {DiscordSettings} from './DiscordSettings';
import {QwenSettings} from './QwenSettings';
import {AgentKeySettings} from './AgentKeySettings';
import {MainAgentSettings} from './MainAgentSettings';
import {WebDavSettings} from './WebDavSettings';
import {api,setCsrf,ApiError} from './http';
import {SaveBar} from './SaveBar';

type User={id:string;name:string;owner:boolean;administrator?:boolean;role?:string;permissions:Record<string,string>};
type Session={appVersion:string;user:User|null;csrf:string;origin:string;publicOrigin:string;setupRequired:boolean};
type Auth={session:Session;refresh:()=>Promise<void>;login:(reauth?:boolean)=>Promise<void>;register:(token?:string)=>Promise<void>;logout:()=>Promise<void>};
const Context=createContext<Auth|null>(null);
export function useAuth(){const value=useContext(Context);if(!value)throw Error('Authentication context missing');return value;}
function decode(value:string):ArrayBuffer {const text=atob(value.replace(/-/g,'+').replace(/_/g,'/'));const bytes=new Uint8Array(text.length);for(let i=0;i<text.length;i++)bytes[i]=text.charCodeAt(i);return bytes.buffer;}
function encode(buffer:ArrayBuffer){let text='';for(const byte of new Uint8Array(buffer))text+=String.fromCharCode(byte);return btoa(text).replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,'');}
function serialize(key:PublicKeyCredential){
 const response=key.response as AuthenticatorAttestationResponse & AuthenticatorAssertionResponse;
 return {id:key.id,rawId:encode(key.rawId),type:key.type,clientExtensionResults:key.getClientExtensionResults(),authenticatorAttachment:key.authenticatorAttachment,response:{clientDataJSON:encode(response.clientDataJSON),...(response.attestationObject?{attestationObject:encode(response.attestationObject),transports:response.getTransports?.()||[]}:{}),...(response.authenticatorData?{authenticatorData:encode(response.authenticatorData),signature:encode(response.signature),userHandle:response.userHandle?encode(response.userHandle):null}:{})}};
}
export function AuthGate({children}:{children:React.ReactNode}){
 const [session,setSession]=useState<Session|null>(null),[error,setError]=useState(''),[token,setToken]=useState(''),[busy,setBusy]=useState(false);
 const version=useRef('');
 async function refresh(){const data=await api<Session>('/auth/session');if(version.current&&version.current!==data.appVersion){window.location.reload();return;}version.current=data.appVersion;setCsrf(data.csrf);setSession(data);}
 useEffect(()=>{refresh().catch(e=>setError(e.message));const listener=()=>refresh().catch(e=>setError(e.message));window.addEventListener('sm:unauthorized',listener);return()=>window.removeEventListener('sm:unauthorized',listener);},[]);
 async function login(reauth=false){
  if(!window.PublicKeyCredential)throw Error('이 브라우저에서 패스키를 사용할 수 없습니다. 최신 Safari 또는 Chrome에서 관리 주소를 여세요.');
  const options=await api<any>('/auth/login/options','POST',{reauthenticate:reauth});
  options.publicKey.challenge=decode(options.publicKey.challenge);
  if(options.publicKey.allowCredentials)options.publicKey.allowCredentials=options.publicKey.allowCredentials.map((c:any)=>({...c,id:decode(c.id)}));
  const key=await navigator.credentials.get(options) as PublicKeyCredential|null;if(!key)throw Error('인증이 취소되었습니다.');
  await api('/auth/login/finish','POST',{credential:serialize(key)});await refresh();
 }
 async function register(invite?:string){
  if(!window.PublicKeyCredential)throw Error('패스키를 지원하는 Safari 또는 Chrome에서 여세요.');
  const options=await api<any>('/auth/register/options','POST',{token:invite});
  options.publicKey.challenge=decode(options.publicKey.challenge);options.publicKey.user.id=decode(options.publicKey.user.id);
  if(options.publicKey.excludeCredentials)options.publicKey.excludeCredentials=options.publicKey.excludeCredentials.map((c:any)=>({...c,id:decode(c.id)}));
  const key=await navigator.credentials.create(options) as PublicKeyCredential|null;if(!key)throw Error('등록이 취소되었습니다.');
  await api('/auth/register/finish','POST',{credential:serialize(key),label:'패스키 '+new Date().toLocaleDateString()});await refresh();
 }
 async function logout(){await api('/auth/logout','POST');await refresh();}
 async function run(action:()=>Promise<void>){setBusy(true);setError('');try{await action();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 if(session?.user)return <Context.Provider value={{session,refresh,login,register,logout}}>{children}</Context.Provider>;
 const wrongOrigin=session&&window.location.origin!==session.origin;
 return <div className="auth-page"><section className="auth-card"><div className="auth-icon"><ShieldCheck size={34}/></div><div className="eyebrow">SERVER MANAGER · PRIVATE ACCESS</div><h1>{session?.setupRequired?'소유자 등록':'관리자 로그인'}</h1><p>등록된 패스키로만 서버 관리에 접근할 수 있습니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}
 {!session&&!error&&<p>로그인 상태를 확인하고 있습니다…</p>}
 {wrongOrigin?<div className="auth-origin"><p>패스키는 등록한 도메인에서만 사용할 수 있습니다.</p><a className="button-link" href={session.origin}>관리 주소에서 열기 → {session.origin}</a></div>:<>
 {!session?.setupRequired&&<button className="primary" disabled={busy||!session} onClick={()=>run(()=>login())}><LogIn size={18}/> 패스키로 로그인</button>}
 <div className="enrollment"><label>{session?.setupRequired?'이 Mac의 소유자 등록 코드':'초대 코드'}<input aria-label="등록 코드" type="password" autoComplete="off" value={token} onChange={e=>setToken(e.target.value)} placeholder="일회용 코드를 입력하세요"/></label>
 <button disabled={busy||!session||!token.trim()} onClick={()=>run(()=>register(token.trim()))}><KeyRound size={17}/> 패스키 등록</button>
 {session?.setupRequired&&<small>서버 Mac의 데이터 폴더에서 security/setup-code.txt를 확인하세요. 코드는 30분 동안 유효합니다.</small>}</div></>}
 <small className="auth-note">비밀번호·공개 회원가입 없음 · 초대 전용</small></section></div>;
}

type Member={id:string;name:string;owner:boolean;role?:string;enabled:boolean;registered:boolean;permissions:Record<string,string>};
const AuditHistory=memo(function AuditHistory({logs}:{logs:any[]}){return <div className="audit-list">{logs.map((entry,index)=><div key={index}><time>{new Date(entry.at).toLocaleString()}</time><span>{entry.action}</span><strong>{entry.status}</strong></div>)}</div>;});
export type SettingsSection='account'|'users'|'ai'|'integrations'|'system';
const sectionTitles:Record<SettingsSection,string>={account:'내 계정',users:'사용자와 권한',ai:'AI 비서·에이전트',integrations:'연동 서비스',system:'시스템'};
export function SecurityPanel({instances,section='account'}:{instances:{config:{id:string;name:string}}[];section?:SettingsSection}){
 const auth=useAuth();const [members,setMembers]=useState<Member[]>([]),[keys,setKeys]=useState<{id:string;label:string;createdAt:string}[]>([]),[logs,setLogs]=useState<any[]>([]);
 const [savedMembers,setSavedMembers]=useState<Member[]>([]);
 const [startup,setStartup]=useState<{supported:boolean;enabled:boolean;dataDirectory:string}|null>(null);
 const [name,setName]=useState(''),[grants,setGrants]=useState<Record<string,string>>({}),[invite,setInvite]=useState(''),[inviteRole,setInviteRole]=useState('MEMBER'),[error,setError]=useState(''),[message,setMessage]=useState(''),[busy,setBusy]=useState(false);
 async function load(){if(section==='account')setKeys(await api('/auth/credentials'));if(auth.session.user?.owner&&section==='users'){const rows=await api<Member[]>('/admin/users');setMembers(rows);setSavedMembers(rows);setLogs(await api('/admin/audit'));}if(auth.session.user?.owner&&section==='system')setStartup(await api('/admin/startup'));}
 useEffect(()=>{load().catch(e=>setError(e.message));},[section]);
 async function run(action:()=>Promise<unknown>,text='완료했습니다.'){setBusy(true);setError('');setMessage('');try{try{await action();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await action();}await load();setMessage(text);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <section className="settings-panel security-panel"><h2>{sectionTitles[section]}</h2><p>{auth.session.user?.name} · {auth.session.user?.owner?'소유자':auth.session.user?.administrator?'관리자 · 모든 서버':'초대된 사용자'}</p>{error&&<div role="alert" className="alert error-banner">{error}</div>}{message&&<div className="notice alert">{message}</div>}
 {section==='account'&&<><div className="actions"><button disabled={busy} onClick={()=>run(()=>auth.login(true),'본인 확인을 완료했습니다. 5분간 민감 작업이 가능합니다.')}>패스키 다시 인증</button><button disabled={busy} onClick={()=>run(()=>auth.register(),'패스키를 추가했습니다.')}>패스키 추가</button><button disabled={busy} onClick={()=>run(async()=>{await api('/auth/revoke-sessions','POST');await auth.refresh();})}>내 모든 세션 종료</button></div>
 <div className="key-list">{keys.map(key=><div key={key.id}><span>{key.label}<small>{new Date(key.createdAt).toLocaleString()}</small></span><button disabled={busy||keys.length<2} onClick={()=>run(async()=>{await api('/auth/credentials/'+encodeURIComponent(key.id),'DELETE');await auth.refresh();})}>삭제</button></div>)}</div>
 <GoogleConnectionSettings/></>}
 {auth.session.user?.owner&&section==='users'&&<><h2>사용자 초대</h2><p>초대 코드는 24시간 동안 한 번 사용할 수 있습니다.</p><label className="security-label">사용자 이름<input value={name} onChange={e=>setName(e.target.value)}/></label><label className="security-label">계정 역할<select aria-label="초대 계정 역할" value={inviteRole} onChange={e=>setInviteRole(e.target.value)}><option value="MEMBER">일반 사용자 · 서버별 권한</option><option value="ADMIN">관리자 · 모든 서버</option></select></label><div className="permission-grid">{instances.map(({config:i})=><label key={i.id}>{i.name}<select disabled={inviteRole!=='MEMBER'} value={grants[i.id]||''} onChange={e=>{const next={...grants};if(e.target.value)next[i.id]=e.target.value;else delete next[i.id];setGrants(next);}}><option value="">접근 불가</option><option value="VIEWER">조회만</option><option value="ADMIN">서버 관리</option></select></label>)}</div>{invite&&<label className="security-label">한 번만 표시되는 초대 코드<input readOnly value={invite} onFocus={e=>e.target.select()}/></label>}
 <SaveBar label="사용자 초대" dirty={!!name.trim()||Object.keys(grants).length>0||inviteRole!=='MEMBER'} busy={busy} disabled={!name.trim()} saveLabel="초대 코드 발급" onSave={()=>run(async()=>{const result=await api<{token:string}>('/admin/invitations','POST',{name,permissions:grants,role:inviteRole});setInvite(result.token);setName('');setGrants({});setInviteRole('MEMBER');},'초대 코드를 발급했습니다.')} onReset={()=>{setName('');setGrants({});setInviteRole('MEMBER');}}/>
 <h2 className="spaced">사용자 권한</h2><p>관리자는 현재와 앞으로 추가되는 모든 서버의 생성·삭제·실행·파일·콘솔을 관리합니다. 사용자 권한·보안 설정과 다른 사람 실행기의 업데이트는 소유자가 관리합니다. 소유자 변경은 패스키가 등록된 계정에만 가능합니다.</p>{members.map(member=>{
 const role=member.role||(member.owner?'OWNER':'MEMBER');
 const save=()=>api('/admin/users/'+member.id,'PUT',{enabled:member.enabled,permissions:member.permissions,role});
 return <div className="member" key={member.id} data-testid={'member-'+member.id}><strong>{member.name} {member.owner?'· 소유자':!member.enabled?'· 비활성화':member.registered?'· 등록 완료':'· 초대 대기'}</strong>
 <label className="security-label">계정 역할<select disabled={busy} aria-label={member.name+' 계정 역할'} value={role} onChange={e=>{const role=e.target.value;setMembers(old=>old.map(m=>m.id===member.id?{...m,role}:m));}}><option value="MEMBER">일반 사용자 · 서버별 권한</option><option value="ADMIN">관리자 · 모든 서버</option><option value="OWNER" disabled={!member.registered}>소유자 · 전체 관리</option></select></label>
 <div className="permission-grid">{instances.map(({config:i})=><label key={i.id}>{i.name}<select disabled={busy||role!=='MEMBER'} aria-label={member.name+' · '+i.name+' 권한'} value={member.permissions[i.id]||''} onChange={e=>{const value=e.target.value;setMembers(old=>old.map(m=>{if(m.id!==member.id)return m;const permissions={...m.permissions};if(value)permissions[i.id]=value;else delete permissions[i.id];return {...m,permissions};}));}}><option value="">접근 불가</option><option value="VIEWER">조회만</option><option value="ADMIN">서버 관리</option></select></label>)}</div><div className="actions"><button disabled={busy} onClick={()=>{if(role==='OWNER'&&!member.owner&&!confirm(`${member.name}에게 계정·보안 설정을 포함한 소유자 권한을 부여할까요?`))return;run(save,'권한을 저장했습니다. 대상 계정은 다시 로그인하면 적용됩니다.');}}>권한 저장</button>{!member.owner&&role!=='OWNER'&&<button disabled={busy} onClick={()=>run(()=>api('/admin/users/'+member.id,'PUT',{enabled:!member.enabled,permissions:member.permissions,role}))}>{member.enabled?'계정 비활성화':'계정 활성화'}</button>}</div></div>;
 })}
 <SaveBar label="사용자 권한" dirty={JSON.stringify(members)!==JSON.stringify(savedMembers)} busy={busy} onSave={()=>{if(members.some(m=>m.role==='OWNER'&&!savedMembers.find(old=>old.id===m.id)?.owner)&&!confirm('선택한 사용자에게 계정·보안을 포함한 소유자 권한을 부여할까요?'))return;run(()=>Promise.all(members.filter((m,i)=>JSON.stringify(m)!==JSON.stringify(savedMembers[i])).map(m=>api('/admin/users/'+m.id,'PUT',{enabled:m.enabled,permissions:m.permissions,role:m.role||(m.owner?'OWNER':'MEMBER')}))),'권한을 저장했습니다.');}} onReset={()=>setMembers(savedMembers)}/>
 <h2 className="spaced">최근 감사 기록</h2><AuditHistory logs={logs}/></>}
 {auth.session.user?.owner&&section==='system'&&<><h2 className="spaced">Mac 자동 시작</h2><label className="check-label"><input type="checkbox" aria-label="Mac 로그인 시 자동 실행" disabled={busy||!startup?.supported} checked={startup?.enabled||false} onChange={e=>{const enabled=e.target.checked;setStartup(old=>old&&({...old,enabled}));run(async()=>{try{setStartup(await api('/admin/startup','PUT',{enabled}));}catch(error){setStartup(old=>old&&({...old,enabled:!enabled}));throw error;}},enabled?'다음 Mac 로그인부터 자동 실행합니다.':'자동 실행을 해제했습니다.');}}/>Mac 로그인 시 서버 관리자 자동 실행</label><p>{startup?.supported?'변경 사항은 다음 로그인부터 적용됩니다. Minecraft 서버는 자동으로 시작하지 않습니다.':'설치 패키지의 Server Manager 앱에서 설정할 수 있습니다.'}</p>{startup&&<p className="muted">데이터 위치: {startup.dataDirectory}</p>}
 <DesktopSettings/><SystemLogPanel/><h2 className="spaced">외부 접속 설정</h2><label className="security-label">공개 HTTPS URL<input readOnly value={auth.session.publicOrigin} placeholder="공개 관리 주소가 설정되지 않았습니다"/></label><p>외부 접속 주소를 변경하면 새 도메인에서 패스키를 다시 등록해야 합니다.</p>
 </>}
 {auth.session.user?.owner&&section==='ai'&&<><MainAgentSettings/><QwenSettings/><AgentKeySettings/></>}
 {auth.session.user?.owner&&section==='integrations'&&<><DiscordSettings instances={instances}/><WebDavSettings instances={instances}/></>}
 {section!=='account'&&!auth.session.user?.owner&&<p role="status">이 설정은 관리 웹 소유자만 사용할 수 있습니다.</p>}
 </section>;
}
