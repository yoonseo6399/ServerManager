import {useEffect,useLayoutEffect,useRef} from 'react';
import {createPortal} from 'react-dom';
import './save-bar.css';
const pending=new Map<symbol,{busy:boolean;reset:()=>void}>();
export function navigateWithUnsaved(action:()=>void){
 if([...pending.values()].some(p=>p.busy))return;
 if(pending.size&&!confirm('저장하지 않은 변경 사항이 있습니다. 변경을 버리고 이동할까요?'))return;
 [...pending.values()].forEach(p=>p.reset());action();
}
export function useUnsavedGuard(dirty:boolean,busy:boolean,onReset:()=>void){
 const key=useRef(Symbol()),reset=useRef(onReset);reset.current=onReset;
 useLayoutEffect(()=>{if(dirty||busy)pending.set(key.current,{busy,reset:()=>reset.current()});return()=>{pending.delete(key.current);};},[dirty,busy]);
 useEffect(()=>{if(!dirty&&!busy)return;const guard=(e:BeforeUnloadEvent)=>{e.preventDefault();e.returnValue='';};window.addEventListener('beforeunload',guard);return()=>window.removeEventListener('beforeunload',guard);},[dirty,busy]);
}
function host(){let node=document.getElementById('unsaved-bars');if(!node){node=document.createElement('div');node.id='unsaved-bars';document.body.appendChild(node);}return node;}
export function SaveBar({label,dirty,busy=false,disabled=false,error='',onSave,onReset,saveLabel='변경 사항 저장'}:{label:string;dirty:boolean;busy?:boolean;disabled?:boolean;error?:string;onSave:()=>void;onReset:()=>void;saveLabel?:string}){
 useUnsavedGuard(dirty,busy,onReset);
 if(!dirty)return null;
 return createPortal(<section className="save-bar" aria-label={label+' 변경 사항'} aria-busy={busy}><div><strong>{label}</strong><span role="status">{busy?'변경 사항을 저장하고 있습니다…':'저장하지 않은 변경 사항이 있습니다.'}</span>{error&&<small className="save-error">{error}</small>}</div><div className="save-bar-actions"><button type="button" disabled={busy} onClick={onReset}>되돌리기</button><button type="button" className="primary" disabled={busy||disabled} onClick={onSave}>{saveLabel}</button></div></section>,host());
}
