import {SaveBar} from './SaveBar';
import {DiscordModulesSettings} from './DiscordModulesSettings';
import {memo,useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';
type Grant={guildId:string;roleId:string;instanceId:string;canStart:boolean;canStop:boolean;canRestart:boolean};
type Route={guildId:string;channelId:string;instanceId:string;errors:boolean;stops:boolean;starts:boolean;panel:boolean};
type SuperAdmin={guildId:string;roleId:string};
type Config={enabled:boolean;grants:Grant[];alerts:Route[];superAdmins:SuperAdmin[]};
type View={config:Config;revision:number;tokenConfigured:boolean};
type Status={state:string;message:string;inviteUrl:string|null;guilds:{id:string;name:string}[]};
type Props={instances:{config:{id:string;name:string}}[]};
export const DiscordSettings=memo(function DiscordSettings({instances}:Props){
 const auth=useAuth();
 const [view,setView]=useState<View|null>(null),[config,setConfig]=useState<Config>({enabled:false,grants:[],alerts:[],superAdmins:[]});
 const [status,setStatus]=useState<Status|null>(null),[token,setToken]=useState(''),[clearToken,setClearToken]=useState(false);
 const [busy,setBusy]=useState(false),[dirty,setDirty]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const normalize=(value:Config):Config=>({...value,grants:value.grants.map(g=>({...g,canStop:g.canStop??false,canRestart:g.canRestart??false})),alerts:value.alerts.map(a=>({...a,panel:a.panel??false,starts:a.starts??true})),superAdmins:value.superAdmins||[]});
 async function load(){const value=await api<View>('/admin/discord');setView(value);setConfig(normalize(value.config));setToken('');setClearToken(false);setDirty(false);}
 useEffect(()=>{let stopped=false;let timer:ReturnType<typeof setTimeout>;load().catch(e=>setError(e.message));const poll=async()=>{try{if(!document.hidden){const value=await api<Status>('/admin/discord/status');if(!stopped)setStatus(value);}}catch{}finally{if(!stopped)timer=setTimeout(poll,5000);}};poll();return()=>{stopped=true;clearTimeout(timer);};},[]);
 function change(value:Config){setConfig(value);setDirty(true);setNotice('');}
 async function run(fn:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{try{await fn();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await fn();}}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 const serverSelect=(id:string,onChange:(id:string)=>void,label:string)=><label>{label}<select value={id} onChange={e=>onChange(e.target.value)} required><option value="">서버 선택</option>{id&&!instances.some(i=>i.config.id===id)&&<option value={id}>삭제된 서버 · 규칙을 삭제하세요</option>}{instances.map(({config:i})=><option key={i.id} value={i.id}>{i.name}</option>)}</select></label>;
 return <section className="discord-settings" aria-labelledby="discord-heading"><h2 id="discord-heading">Discord 봇</h2><p>친구들은 채널 제어 패널이나 <code>/mc</code> 명령으로 허용된 서버를 조회하고 제어할 수 있습니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}{notice&&<div role="status" className="notice alert">{notice}</div>}
 <div className="discord-status"><strong>{status?.state||'확인 중'}</strong><span>{status?.message}</span></div>
 <DiscordModulesSettings/>
 {status?.guilds.length!==0&&<p className="muted">연결된 Discord 서버: {status?.guilds.map(g=>`${g.name} (${g.id})`).join(', ')}</p>}
 <form id="discord-settings-form" onSubmit={e=>{e.preventDefault();if(!view)return;run(async()=>{const saved=await api<View>('/admin/discord','PUT',{config,revision:view.revision,token:token.trim()||null,clearToken});setView(saved);setConfig(normalize(saved.config));setToken('');setClearToken(false);setDirty(false);setNotice('Discord 설정을 저장했습니다. 연결 상태는 잠시 후 갱신됩니다.');setStatus(await api('/admin/discord/status'));});}}>
 <fieldset disabled={busy||!view} className="discord-fieldset">
 <label className="check-label"><input type="checkbox" checked={config.enabled} onChange={e=>change({...config,enabled:e.target.checked})}/>Discord 연동 켜기</label>
 <label className="security-label">봇 토큰<input type="password" autoComplete="new-password" spellCheck={false} maxLength={512} value={token} placeholder={view?.tokenConfigured&&!clearToken?'저장된 토큰 있음 · 비워두면 유지':'Discord Developer Portal의 Bot 토큰'} onChange={e=>{setToken(e.target.value);setClearToken(false);setDirty(true);}}/></label>
 <div className="actions"><button type="button" disabled={!view?.tokenConfigured||clearToken} onClick={()=>{setToken('');setClearToken(true);change({...config,enabled:false});}}>저장된 토큰 삭제 예약</button>{clearToken&&<span>저장하면 토큰이 삭제되고 연결이 종료됩니다.</span>}</div>
 <h3>최고관리자 역할</h3><p className="muted">설정한 Discord 서버의 모든 Minecraft 서버를 시작·중지·재시작할 수 있습니다. 웹 계정이나 콘솔 권한은 부여되지 않습니다.</p>
 {config.superAdmins.map((a,index)=><div className="discord-rule" key={index}><label>Discord 서버 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={a.guildId} onChange={e=>change({...config,superAdmins:config.superAdmins.map((x,n)=>n===index?{...x,guildId:e.target.value.trim()}:x)})}/></label><label>최고관리자 역할 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={a.roleId} onChange={e=>change({...config,superAdmins:config.superAdmins.map((x,n)=>n===index?{...x,roleId:e.target.value.trim()}:x)})}/></label><button type="button" onClick={()=>change({...config,superAdmins:config.superAdmins.filter((_,n)=>n!==index)})}>역할 삭제</button></div>)}
 <button type="button" onClick={()=>change({...config,superAdmins:[...config.superAdmins,{guildId:status?.guilds[0]?.id||'',roleId:''}]})}>최고관리자 역할 추가</button>
 <h3>역할별 서버 권한</h3><p className="muted">규칙이 없으면 접근할 수 없습니다. 조회·시작·중지·재시작 권한을 각각 설정합니다. @everyone은 지원하지 않습니다.</p>
 {config.grants.map((g,index)=><div className="discord-rule" key={index}>
 <label>Discord 서버 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={g.guildId} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,guildId:e.target.value.trim()}:x)})}/></label>
 <label>역할 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={g.roleId} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,roleId:e.target.value.trim()}:x)})}/></label>
 {serverSelect(g.instanceId,id=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,instanceId:id}:x)}),'관리할 Minecraft 서버')}
 <label className="check-label"><input type="checkbox" checked={g.canStart} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,canStart:e.target.checked}:x)})}/>서버 시작 허용</label>
 <label className="check-label"><input type="checkbox" checked={g.canStop} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,canStop:e.target.checked}:x)})}/>서버 중지 허용</label>
 <label className="check-label"><input type="checkbox" checked={g.canRestart} onChange={e=>change({...config,grants:config.grants.map((x,n)=>n===index?{...x,canRestart:e.target.checked}:x)})}/>서버 재시작 허용</label>
 <button type="button" onClick={()=>change({...config,grants:config.grants.filter((_,n)=>n!==index)})}>역할 규칙 삭제</button></div>)}
 <button type="button" onClick={()=>change({...config,grants:[...config.grants,{guildId:status?.guilds[0]?.id||'',roleId:'',instanceId:instances[0]?.config.id||'',canStart:false,canStop:false,canRestart:false}]})}>역할 규칙 추가</button>
 <h3>서버별 제어 패널·알림</h3><p className="muted">패널을 켠 채널은 해당 Minecraft 서버 전용으로 사용합니다. 기존 메시지를 정리하고 패널·알림만 남기므로 별도 채널을 지정하세요. 실행 중의 오류는 카운트에만 반영하고, 실제 비정상 종료 때 오류 요약을 알립니다.</p>
 {config.alerts.map((a,index)=><div className="discord-rule" key={index}>
 <label>알림 Discord 서버 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={a.guildId} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,guildId:e.target.value.trim()}:x)})}/></label>
 <label>텍스트 채널 ID<input required inputMode="numeric" pattern="[1-9][0-9]{16,19}" value={a.channelId} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,channelId:e.target.value.trim()}:x)})}/></label>
 {serverSelect(a.instanceId,id=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,instanceId:id}:x)}),'알림 대상 Minecraft 서버')}
 <label className="check-label"><input type="checkbox" checked={a.errors} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,errors:e.target.checked}:x)})}/>비정상 종료·크래시 알림</label>
 <label className="check-label"><input type="checkbox" checked={a.starts} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,starts:e.target.checked}:x)})}/>시작 완료 알림</label>
 <label className="check-label"><input type="checkbox" checked={a.stops} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,stops:e.target.checked}:x)})}/>종료 알림</label>
 <label className="check-label"><input type="checkbox" checked={a.panel} onChange={e=>change({...config,alerts:config.alerts.map((x,n)=>n===index?{...x,panel:e.target.checked}:x)})}/>전용 채널 제어 패널</label>
 <button type="button" onClick={()=>change({...config,alerts:config.alerts.filter((_,n)=>n!==index)})}>알림 규칙 삭제</button></div>)}
 <button type="button" onClick={()=>change({...config,alerts:[...config.alerts,{guildId:status?.guilds[0]?.id||'',channelId:'',instanceId:instances[0]?.config.id||'',errors:true,stops:true,starts:true,panel:true}]})}>채널 규칙 추가</button>
 <div className="actions spaced"><button type="button" onClick={()=>run(load)}>저장된 설정 다시 불러오기</button><button type="button" disabled={dirty||!view?.config.enabled} onClick={()=>run(async()=>{await api('/admin/discord/reconnect','POST');setNotice('다시 연결을 요청했습니다.');})}>다시 연결</button></div>
 </fieldset></form>
 <SaveBar label="Discord 설정" dirty={dirty&&!!view&&(!!token||clearToken||JSON.stringify(config)!==JSON.stringify(normalize(view.config)))} busy={busy} error={error} saveLabel="Discord 설정 저장" onSave={()=>{(document.getElementById('discord-settings-form') as HTMLFormElement)?.requestSubmit();}} onReset={()=>{if(view)setConfig(normalize(view.config));setToken('');setClearToken(false);setDirty(false);setError('');}}/>
 {status?.inviteUrl&&<p><a href={status.inviteUrl} target="_blank" rel="noreferrer">Discord 서버에 봇 초대하기</a></p>}
 <details><summary>처음 연결하는 방법</summary><ol><li><a href="https://discord.com/developers/applications" target="_blank" rel="noreferrer">Discord Developer Portal</a>에서 앱과 봇을 만들고 Bot 토큰을 이 화면에 저장하세요.</li><li>연동을 켠 뒤 위의 초대 링크로 봇을 초대하세요. 전용 채널에는 채널 보기·메시지 보내기·임베드 링크·메시지 기록 보기·메시지 관리·파일 첨부 권한이 필요합니다.</li><li>Discord 설정 → 고급 → 개발자 모드를 켜고 서버·역할·텍스트 채널 ID를 복사해 규칙에 넣으세요.</li><li>Interactions Endpoint URL은 비워 두세요. 추가 포트 개방과 Message Content / Server Members 특권 인텐트는 필요하지 않습니다.</li><li>관리 앱과 Mac이 켜져 있어야 명령과 알림이 작동합니다. Mac 자체를 켜는 기능은 아닙니다.</li></ol></details></section>;
},(a,b)=>JSON.stringify(a.instances.map(i=>i.config.id+':'+i.config.name))===JSON.stringify(b.instances.map(i=>i.config.id+':'+i.config.name)));
