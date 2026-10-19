let csrf='';
export class ApiError extends Error {constructor(message:string,public status:number,public code?:string){super(message);}}
export function setCsrf(value:string){csrf=value;}
const SAFE=['GET','HEAD','OPTIONS'];
// The server keeps the CSRF token in the HTTP session. When the session expires (e.g. the tab sat idle),
// the cached token is stale and mutating requests get 403. Fetch a fresh token once and retry.
async function renewCsrf(){
 const res=await fetch('/api/auth/session',{credentials:'same-origin'});
 if(!res.ok)return false;
 const data=await res.json().catch(()=>null);
 if(!data?.csrf)return false;
 csrf=data.csrf;return true;
}
export async function api<T=unknown>(url:string,method='GET',body?:unknown):Promise<T>{
 const send=()=>{
  const headers:Record<string,string>=body instanceof FormData?{}:{'Content-Type':'application/json'};
  if(!SAFE.includes(method))headers['X-CSRF-TOKEN']=csrf;
  return fetch('/api'+url,{method,headers,credentials:'same-origin',body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
 };
 let res=await send();
 let retried=false;
 if(res.status===403&&!SAFE.includes(method)&&!(body instanceof FormData)&&await renewCsrf()){retried=true;res=await send();}
 if(!res.ok){if(res.status===401&&!url.startsWith('/auth/'))window.dispatchEvent(new Event('sm:unauthorized'));const error=await res.json().catch(()=>({message:res.statusText}));throw new ApiError(retried&&res.status===403?'세션이 만료되었거나 권한이 없습니다. 페이지를 새로고침한 뒤 다시 시도하세요.':error.message||'요청 실패',res.status,error.code);}
 const text=await res.text();return text?JSON.parse(text):undefined as T;
}
