import {SaveBar} from './SaveBar';
import {memo,useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

type Config={enabled:boolean;url:string;username:string};
type View={config:Config;revision:number;passwordConfigured:boolean};
type Status={enabled:boolean;inProgress:boolean;lastSuccess:string|null;lastError:string|null;generation:string|null};
type Instance={config:{id:string;name:string}};

export const WebDavSettings=memo(function WebDavSettings({instances}:{instances:Instance[]}){
 const auth=useAuth();
 const [view,setView]=useState<View|null>(null),[config,setConfig]=useState<Config>({enabled:false,url:'',username:''});
 const [password,setPassword]=useState(''),[clearPassword,setClearPassword]=useState(false),[dirty,setDirty]=useState(false),[busy,setBusy]=useState(false);
 const [statuses,setStatuses]=useState<Record<string,Status>>({}),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const load=async()=>{const value=await api<View>('/admin/webdav');if(!value?.config)return;setView(value);setConfig(value.config);setPassword('');setClearPassword(false);setDirty(false);};
 useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>;load().catch(e=>setError(e.message));const poll=async()=>{try{if(!document.hidden&&instances.length){const entries=await Promise.all(instances.map(async i=>[i.config.id,await api<Status>(`/admin/webdav/status/${i.config.id}`)] as const));if(live)setStatuses(Object.fromEntries(entries));}}catch{}finally{if(live)timer=setTimeout(poll,10000);}};poll();return()=>{live=false;clearTimeout(timer);};},[instances.map(i=>i.config.id).join(',')]);
 async function run(action:()=>Promise<void>,message:string){setBusy(true);setError('');setNotice('');try{try{await action();}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await action();}setNotice(message);}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 const change=(next:Config)=>{setConfig(next);setDirty(true);};
 return <section className="webdav-settings"><h2>WebDAV 서버 데이터 동기화</h2><p>서버가 완전히 종료된 뒤 변경된 파일만 클라우드에 올립니다. 큰 파일이 바뀌면 그 파일 전체를 전송합니다.</p>
 {error&&<div role="alert" className="alert error-banner">{error}</div>}{notice&&<div role="status" className="notice alert">{notice}</div>}
 <label className="check-label"><input disabled={busy||!view} type="checkbox" checked={config.enabled} onChange={e=>change({...config,enabled:e.target.checked})}/>동기화 켜기</label>
 <label className="security-label">HTTPS WebDAV 폴더 URL<input disabled={busy||!view} value={config.url} onChange={e=>change({...config,url:e.target.value})} placeholder="https://cloud.example.com/remote.php/dav/files/user/ServerManager"/></label>
 <label className="security-label">WebDAV 계정 이름<input disabled={busy||!view} value={config.username} onChange={e=>change({...config,username:e.target.value})}/></label>
 <label className="security-label">앱 비밀번호<input disabled={busy||!view} type="password" autoComplete="new-password" value={password} placeholder={view?.passwordConfigured&&!clearPassword?'저장된 비밀번호 있음 · 비워두면 유지':'WebDAV 앱 비밀번호'} onChange={e=>{setPassword(e.target.value);setClearPassword(false);setDirty(true);}}/></label>
 <div className="actions"><button disabled={!view?.passwordConfigured||busy} onClick={()=>{setPassword('');setClearPassword(true);change({...config,enabled:false});}}>저장된 비밀번호 삭제 예약</button></div>
 <SaveBar label="WebDAV 설정" dirty={dirty&&!!view&&(!!password||clearPassword||JSON.stringify(config)!==JSON.stringify(view.config))} busy={busy} error={error} saveLabel="WebDAV 설정 저장" onSave={()=>run(async()=>{const saved=await api<View>('/admin/webdav','PUT',{config,revision:view!.revision,password:password||null,clearPassword});setView(saved);setConfig(saved.config);setPassword('');setClearPassword(false);setDirty(false);},'WebDAV 설정을 저장했습니다.')} onReset={()=>{if(view)setConfig(view.config);setPassword('');setClearPassword(false);setDirty(false);setError('');}}/>
 {config.enabled&&<><h3>서버별 동기화 상태</h3>{instances.map(({config:i})=>{const status=statuses[i.id];return <div className="member" key={i.id}><strong>{i.name} · {status?.inProgress?'동기화 중':status?.lastError?'실패':status?.lastSuccess?'완료':'대기'}</strong>{status?.lastError&&<p role="alert">{status.lastError}</p>}<p className="muted">마지막 완료: {status?.lastSuccess?new Date(status.lastSuccess).toLocaleString():'없음'}</p><button disabled={busy||status?.inProgress} onClick={()=>run(()=>api(`/admin/webdav/sync/${i.id}`,'POST'),'동기화를 요청했습니다.')}>종료된 서버 지금 동기화</button></div>;})}</>}
 </section>;
});
