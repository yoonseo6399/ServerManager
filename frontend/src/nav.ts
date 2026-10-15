import {useEffect,useRef} from 'react';
import {navigateWithUnsaved} from './SaveBar';
/** Browser history integration: the dashboard is a single page, so every navigation-level state is mirrored into history entries. */
export type View={selected:string;tab:string;folder:string;editor:string|null};
type Entry={view?:View;modal?:string;idx?:number};
const entry=()=>(history.state as Entry|null)||{};
const same=(a:View,b:View)=>a.selected===b.selected&&a.tab===b.tab&&a.folder===b.folder&&a.editor===b.editor;
const urlFor=(v:View)=>['runners','network','account','security','users','ai','integrations','system'].includes(v.tab)?'#'+v.tab:window.location.pathname+window.location.search;
let position=0;
/** Mirrors `view` into history (push on change) and calls `apply` when Back/Forward moves to another view. */
export function useViewHistory(view:View,apply:(v:View)=>void){
 const latest=useRef(view),applyRef=useRef(apply),restoring=useRef<View|null>(null);
 latest.current=view;applyRef.current=apply;
 // Dependent resets (selecting a server clears its folder/editor in a follow-up render) must not leave intermediate entries.
 useEffect(()=>{
  const timer=setTimeout(()=>{
   const current=latest.current,target=restoring.current;
   if(target){if(same(current,target))restoring.current=null;return;}
   const st=entry();
   if(!st.view){history.replaceState({view:current,idx:position},'',urlFor(current));return;}
   if(same(st.view,current))return;
   // The first automatic selection and a view change made while a modal entry is on top replace instead of stacking.
   if((!st.view.selected&&!!current.selected&&st.view.tab===current.tab)||st.modal){history.replaceState({view:current,idx:st.idx??position},'',urlFor(current));return;}
   position=(st.idx??position)+1;history.pushState({view:current,idx:position},'',urlFor(current));
  },0);
  return()=>clearTimeout(timer);
 },[view.selected,view.tab,view.folder,view.editor]);
 useEffect(()=>{
  let ignore=false;
  const on=(e:PopStateEvent)=>{
   if(ignore){ignore=false;return;}
   const st=e.state as Entry|null;
   if(!st?.view)return;
   const delta=(st.idx??position)-position;position=st.idx??position;
   if(same(st.view,latest.current))return;
   let ran=false;
   navigateWithUnsaved(()=>{ran=true;restoring.current=st.view!;applyRef.current(st.view!);});
   if(!ran&&delta){ignore=true;position-=delta;history.go(-delta);}
  };
  window.addEventListener('popstate',on);return()=>window.removeEventListener('popstate',on);
 },[]);
 return {cancelRestore:()=>{restoring.current=null;}};
}
/** Lets Back close an open dialog instead of leaving the page. */
export function useBackClose(open:boolean,onClose:()=>void,key:string){
 const close=useRef(onClose),mine=useRef(false);close.current=onClose;
 const release=()=>{mine.current=false;setTimeout(()=>{if(entry().modal===key)history.back();},0);};
 useEffect(()=>{
  if(open&&!mine.current){const st=entry();position=(st.idx??position)+1;history.pushState({...st,modal:key,idx:position},'');mine.current=true;}
  else if(!open&&mine.current)release();
 },[open]);
 useEffect(()=>{
  const on=()=>{if(mine.current&&entry().modal!==key){mine.current=false;close.current();}};
  window.addEventListener('popstate',on);
  return()=>{window.removeEventListener('popstate',on);if(mine.current)release();};
 },[key]);
}
