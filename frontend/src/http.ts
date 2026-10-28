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
export async function api<T=unknown>(url:string,method='GET',body?:unknown,signal?:AbortSignal):Promise<T>{
 const send=()=>{
  const binary=body instanceof Blob||body instanceof ArrayBuffer;
  const headers:Record<string,string>=body instanceof FormData?{}:{'Content-Type':binary?'application/octet-stream':'application/json'};
  if(!SAFE.includes(method))headers['X-CSRF-TOKEN']=csrf;
  return fetch('/api'+url,{method,headers,credentials:'same-origin',signal,body:body===undefined?undefined:body instanceof FormData||body instanceof Blob||body instanceof ArrayBuffer?body:JSON.stringify(body)});
 };
 let res=await send();
 let retried=false;
 if(res.status===403&&!SAFE.includes(method)&&!(body instanceof FormData)&&await renewCsrf()){retried=true;res=await send();}
 if(!res.ok){if(res.status===401&&!url.startsWith('/auth/'))window.dispatchEvent(new Event('sm:unauthorized'));const error=await res.json().catch(()=>({message:res.statusText}));throw new ApiError(retried&&res.status===403?'세션이 만료되었거나 권한이 없습니다. 페이지를 새로고침한 뒤 다시 시도하세요.':error.message||'요청 실패',res.status,error.code);}
 const text=await res.text();return text?JSON.parse(text):undefined as T;
}

/** XHR exposes actual upload bytes; fetch does not currently expose upload progress. */
export function uploadFile<T=unknown>(url:string,body:FormData,onProgress:(percent:number)=>void):Promise<T>{
 return new Promise((resolve,reject)=>{
  const xhr=new XMLHttpRequest();xhr.open('POST','/api'+url);xhr.withCredentials=true;xhr.setRequestHeader('X-CSRF-TOKEN',csrf);
  xhr.upload.onprogress=e=>{if(e.lengthComputable)onProgress(Math.round(e.loaded/e.total*100));};
  xhr.onerror=()=>reject(new Error('파일 전송 중 연결이 끊겼습니다.'));xhr.onabort=()=>reject(new Error('업로드가 취소되었습니다.'));
  xhr.onload=()=>{let data:any;try{data=xhr.responseText?JSON.parse(xhr.responseText):undefined;}catch{reject(new Error(`업로드 응답을 읽을 수 없습니다 (HTTP ${xhr.status}).`));return;}
   if(xhr.status>=200&&xhr.status<300){onProgress(100);resolve(data);}else{if(xhr.status===401)window.dispatchEvent(new Event('sm:unauthorized'));reject(new ApiError(data?.message||`업로드 실패 (HTTP ${xhr.status})`,xhr.status,data?.code));}};
  xhr.send(body);
 });
}
