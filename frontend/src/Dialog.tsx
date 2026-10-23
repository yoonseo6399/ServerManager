import {useEffect,useRef,type ReactNode} from 'react';
import {useUnsavedGuard} from './SaveBar';
import {createPortal} from 'react-dom';
/** Native modal owns focus containment, background inertness and focus restoration. */
export function Dialog({label,busy=false,onClose,children,className=''}:{label:string;busy?:boolean;onClose:()=>void;children:ReactNode;className?:string}){
 useUnsavedGuard(false,busy,()=>{});
 const ref=useRef<HTMLDialogElement>(null);
 useEffect(()=>{const node=ref.current!;const previous=document.activeElement as HTMLElement|null;node.showModal();return()=>{node.close();previous?.focus();};},[]);
 return createPortal(<dialog ref={ref} className={'modal native-dialog '+className} aria-label={label} aria-modal="true" aria-busy={busy} onKeyDown={e=>{if(e.key!=='Tab')return;const nodes=Array.from(ref.current!.querySelectorAll<HTMLElement>('button,a[href],input,select,textarea,[tabindex]')).filter(n=>!n.matches(':disabled')&&n.tabIndex>=0&&n.getClientRects().length>0);const first=nodes[0],last=nodes[nodes.length-1];if(!first){e.preventDefault();ref.current!.focus();return;}if(e.shiftKey&&(document.activeElement===first||document.activeElement===ref.current)){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}} onCancel={e=>{e.preventDefault();if(!busy)onClose();}}>{children}</dialog>,document.body);
}
