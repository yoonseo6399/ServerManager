import {memo,useEffect,useState} from 'react';
import {api,ApiError} from './http';
import {useAuth} from './Auth';

type Runner={id:string;name:string;platform:string;online:boolean;enabled:boolean;version?:string;allowedInstanceIds:string[]};

export const RunnerAssignment=memo(function RunnerAssignment({instanceId,runnerId,disabled,onAssigned,onRegister,requireRunner=false,syncEnabled=true}:{instanceId:string;runnerId:string|null;disabled:boolean;onAssigned:()=>Promise<void>;onRegister:()=>void;requireRunner?:boolean;syncEnabled?:boolean}){
 const auth=useAuth();
 const [runners,setRunners]=useState<Runner[]>([]),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>;const poll=async()=>{try{const result=await api<Runner[]>('/runners');if(live){setRunners(Array.isArray(result)?result:[]);setError('');}}catch(e){if(live)setError('실행기 목록을 불러오지 못했습니다. '+(e as Error).message);}finally{if(live)timer=setTimeout(poll,10000);}};poll();return()=>{live=false;clearTimeout(timer);};},[]);
 const selected=runners.find(r=>r.id===runnerId);
 const supportsSync=!!selected?.version&&/^\d+\.\d+\.\d+$/.test(selected.version)&&selected.version.split('.').map(Number).reduce((a,n)=>a*10000+n,0)>=40005;
 const choices=runners.filter(r=>r.enabled&&r.allowedInstanceIds.includes(instanceId));
 const visible=runners.filter(r=>r.enabled);
 const assign=async(value:string)=>{setBusy(true);setError('');try{const result=await api<{config?:{port:number}}>(`/instances/${instanceId}/runner`,'PUT',{runnerId:value||null});await onAssigned();setNotice(result?.config?`실행기를 배정했습니다. 내부 게임 포트: ${result.config.port}`:'실행기를 배정했습니다.');}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 const toggleSync=async(enabled:boolean)=>{if(enabled&&!confirm('현재 실행기의 원본을 중앙에 반영합니다. 동기화가 완료될 때까지 실행기 변경이 잠깁니다. 계속할까요?'))return;setBusy(true);setError('');try{try{await api(`/instances/${instanceId}/file-sync`,'PUT',{enabled});}catch(e){if(!(e instanceof ApiError)||e.status!==428)throw e;await auth.login(true);await api(`/instances/${instanceId}/file-sync`,'PUT',{enabled});}await onAssigned();}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 return <section className="member runner-assignment" aria-label="서버 실행 위치"><div className="actions"><h3>실행 컴퓨터</h3><button onClick={onRegister}>실행기 등록·관리</button></div><p>{disabled?'서버를 완전히 종료한 뒤 실행 위치를 변경할 수 있습니다.':'실행할 컴퓨터를 선택한 뒤 서버를 시작하세요.'}</p>
 <p className="muted">한 실행기에 여러 서버를 배정할 수 있습니다. 배정할 때 내부 게임 포트가 겹치면 빈 포트로 자동 분리합니다. Simple Voice Chat도 서버별로 서로 다른 내부 음성 포트를 지정하세요.</p>
 <label className="security-label">실행 위치<select aria-label="실행 위치" value={runnerId||''} disabled={disabled||busy||!syncEnabled} onChange={e=>assign(e.target.value)}><option value="" disabled={requireRunner}>이 관리 Mac{requireRunner?' · 공유 포트 사용 불가':''}</option>{visible.map(r=><option key={r.id} value={r.id} disabled={!r.allowedInstanceIds.includes(instanceId)}>{r.name} · {r.platform} · {r.online?'온라인':'오프라인'}{!r.allowedInstanceIds.includes(instanceId)?' · 서버 사용 허용 필요':''}</option>)}{runnerId&&!visible.some(r=>r.id===runnerId)&&<option value={runnerId}>등록되지 않았거나 접근할 수 없는 실행기</option>}</select></label>
 {!choices.length&&<p className="notice">이 서버에 허용된 원격 실행기가 없습니다. ‘실행기 등록·관리’에서 실행기를 설치·등록하고 이 서버의 실행을 허용하세요. {requireRunner?'공유 포트를 이용하려면 별도 실행기를 등록·선택해야 합니다.':runnerId?'현재 배정된 실행기의 등록 상태와 서버 사용 허용을 확인하세요.':'현재는 관리 Mac에서 실행됩니다.'}</p>}
 {selected&&<p className="muted">현재 실행기: {selected.name} · {selected.online?'연결됨':'오프라인'}. {requireRunner?'공개 게임 주소·포트와 포워딩 대상은 공통 접속·직접 중계 설정에서 확인하세요.':'게임 포트는 관리 Mac에서 공개됩니다. 외부 접속은 이 Mac의 게임 포트를 포워딩하세요.'}</p>}
 <label className="check-label"><input type="checkbox" aria-label="서버 파일 동기화" checked={syncEnabled} disabled={disabled||busy||!selected?.online||!supportsSync} onChange={e=>toggleSync(e.target.checked)}/>서버 파일 동기화</label>
 <p className="muted">{syncEnabled?'ON · 정상 종료 후 변경 파일을 중앙·클라우드에 반영하며 실행기를 이동할 수 있습니다.':'OFF · 현재 실행기로 고정됩니다. 웹 파일 관리자는 이 실행기의 원본 폴더를 직접 읽고 수정합니다. 중앙·클라우드에는 업로드하지 않습니다.'}</p>
 {!supportsSync&&<p className="muted">동기화 선택과 실행기 파일 관리에는 연결된 실행기 0.4.5 이상이 필요합니다.</p>}
 {notice&&<p role="status">{notice}</p>}
 {error&&<p role="alert" className="error-banner">{error}</p>}</section>;
});
