import {useState} from 'react';
import {api} from './http';
type Release={version:string;name:string;prerelease:boolean;publishedAt:string;url:string;installable:boolean;reason:string|null};
export function ReleasePicker({supported,busy,onInstall}:{supported:boolean;busy:boolean;onInstall:(version:string,allowPrerelease:boolean)=>void}){
 const [releases,setReleases]=useState<Release[]|null>(null),[version,setVersion]=useState(''),[loading,setLoading]=useState(false),[error,setError]=useState(''),[allowBeta,setAllowBeta]=useState(false);
 const chosen=releases?.find(r=>r.version===version);
 async function refresh(){setLoading(true);setError('');try{const rows=await api<Release[]>('/admin/updates/releases');setReleases(rows);setVersion('');setAllowBeta(false);}catch(e){setError((e as Error).message);}finally{setLoading(false);}}
 return <section aria-label="릴리즈 선택 업데이트"><h3>릴리즈를 선택해서 수동 업데이트</h3><p>안정판과 베타를 직접 선택합니다. 현재보다 높은 버전만 설치할 수 있으며, 자동 업데이트 설정은 바뀌지 않습니다.</p>
 <button disabled={busy||loading} onClick={refresh}>{loading?'릴리즈 조회 중…':'릴리즈 목록 새로고침'}</button>
 {error&&<p role="alert">{error}</p>}
 {releases&&<><label className="security-label">설치할 릴리즈<select aria-label="설치할 릴리즈" disabled={busy||loading} value={version} onChange={e=>{setVersion(e.target.value);setAllowBeta(false);}}><option value="">릴리즈를 선택하세요</option>{releases.map(r=><option key={r.version} value={r.version} disabled={!r.installable}>{r.version} · {r.prerelease?'BETA':'STABLE'}{r.reason?' · '+r.reason:''}</option>)}</select></label>{releases.length===0&&<p>공개된 릴리즈가 없습니다.</p>}</>}
 {chosen&&<><p>{chosen.name} · {chosen.publishedAt?.slice(0,10)} · <a href={chosen.url} target="_blank" rel="noreferrer">릴리즈 설명</a></p>{chosen.prerelease&&<label className="check-label"><input aria-label="베타 설치 동의" type="checkbox" checked={allowBeta} disabled={busy} onChange={e=>setAllowBeta(e.target.checked)}/>시험 중인 베타 버전을 설치하는 데 동의합니다.</label>}</>}
 <button disabled={busy||loading||!supported||!chosen?.installable||!!chosen?.prerelease&&!allowBeta} onClick={()=>{if(chosen&&confirm(`${chosen.version} ${chosen.prerelease?'베타':'안정판'}로 업데이트할까요? 서명 검증 후 안전하게 재시작할 수 있을 때 적용합니다.`))onInstall(chosen.version,allowBeta);}}>선택한 릴리즈 설치</button>
 {!supported&&<p className="muted">수동 적용은 설치 앱에서 지원합니다.</p>}</section>;
}
