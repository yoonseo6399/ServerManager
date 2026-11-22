import {useEffect,useId,useMemo,useRef,useState,type ReactNode} from 'react';
import {createPortal} from 'react-dom';
import type {Mixin,ParsedLog} from './logParser';

/** Keep dismissal outside the scrolling/expanding report, including on short mobile screens. */
export function LogDetailDialog({label,title,onClose,children}:{label:string;title:string;onClose:()=>void;children:ReactNode}){
 const dialog=useRef<HTMLElement>(null),close=useRef<HTMLButtonElement>(null),dismiss=useRef(onClose);
 dismiss.current=onClose;
 useEffect(()=>{
  const previous=document.activeElement as HTMLElement|null,overflow=document.body.style.overflow;
  document.body.style.overflow='hidden';close.current?.focus();
  function keydown(event:KeyboardEvent){
   if(event.key==='Escape'){event.preventDefault();event.stopPropagation();dismiss.current();}
   if(event.key==='Tab'){
    const items=[...dialog.current!.querySelectorAll<HTMLElement>('button:not(:disabled),summary,[href],input,select,textarea,[tabindex="0"]')].filter(e=>e.getClientRects().length>0);
    const first=items[0],last=items.at(-1);
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
    else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
   }
  }
  document.addEventListener('keydown',keydown,true);
  return()=>{document.removeEventListener('keydown',keydown,true);document.body.style.overflow=overflow;if(previous?.isConnected)previous.focus({preventScroll:true});};
 },[]);
 return createPortal(<div className="modal-backdrop log-detail-backdrop" onClick={e=>{if(e.target===e.currentTarget)dismiss.current();}}>
  <section ref={dialog} role="dialog" aria-modal="true" aria-label={label} className="modal log-detail">
   <div className="log-detail-header"><h2 title={title}>{title}</h2><button ref={close} type="button" onClick={onClose}>닫기</button></div>
   <div className="log-detail-body">{children}</div>
  </section>
 </div>,document.body);
}

function Pages({page,count,onChange,name}:{page:number;count:number;onChange:(page:number)=>void;name:string}){
 if(count<=1)return null;
 return <div className="log-detail-pages" role="group" aria-label={`${name} 페이지`}>
  <button disabled={page===0} onClick={()=>onChange(page-1)}>{name} 이전</button>
  <span>{page+1} / {count}</span><button disabled={page+1===count} onClick={()=>onChange(page+1)}>{name} 다음</button>
 </div>;
}

/** Preserve every character while avoiding a multi-megabyte layout in one render. */
export function LogText({text,initiallyOpen=false}:{text:string;initiallyOpen?:boolean}){
 const [open,setOpen]=useState(initiallyOpen),[page,setPage]=useState(0);
 const boundaries=useMemo(()=>{
  const result=[0];let offset=0;
  while(offset<text.length){let end=Math.min(text.length,offset+32768);const c=text.charCodeAt(end-1);if(end<text.length&&c>=0xd800&&c<=0xdbff)end--;result.push(end);offset=end;}
  return result.length===1?[0,0]:result;
 },[text]);
 const count=boundaries.length-1,current=Math.min(page,count-1);
 return <details open={open} onToggle={e=>setOpen(e.currentTarget.open)}><summary>원문 보기</summary>{open&&<>
  <Pages page={current} count={count} onChange={setPage} name="원문"/>
  <pre>{text.slice(boundaries[current],boundaries[current+1])}</pre>
 </>}</details>;
}

export function MixinGroups({mixins}:{mixins:Mixin[]}){
 const [page,setPage]=useState(0),count=Math.ceil(mixins.length/100),current=Math.min(page,Math.max(0,count-1));
 const groups=useMemo(()=>{
  const result=new Map<string,Map<string,Mixin[]>>();
  for(const m of mixins.slice(current*100,current*100+100)){
   if(!result.has(m.modId))result.set(m.modId,new Map());
   const configs=result.get(m.modId)!;if(!configs.has(m.config))configs.set(m.config,[]);configs.get(m.config)!.push(m);
  }
  return result;
 },[mixins,current]);
 return <><Pages page={current} count={count} onChange={setPage} name="Mixin"/>
  <div className="mixin-groups">{[...groups].map(([mod,configs])=><section key={mod}><h4>{mod}</h4>{[...configs].map(([config,items])=><div key={config}><code>{config}</code><ul>{items.map((m,i)=><li key={i}>{m.className} <small>{m.phase}</small></li>)}</ul></div>)}</section>)}</div>
 </>;
}

export function FrameMixins({rows}:{rows:ParsedLog[]}){
 const [page,setPage]=useState(0),frames=useMemo(()=>rows.filter(row=>row.frame),[rows]);
 const count=Math.ceil(frames.length/10),current=Math.min(page,Math.max(0,count-1)),id=useId();
 return <><h3 id={id}>프레임별 Mixin 정보 · {frames.length}개</h3><p>클래스 연결 정보이며 오류 원인으로 단정하지 않습니다.</p>
  <Pages page={current} count={count} onChange={setPage} name="프레임"/>
  <div aria-labelledby={id}>{frames.slice(current*10,current*10+10).map(row=><details key={row.seq} open><summary>#{row.seq} {row.display}</summary>{row.frame!.mixins.length?<MixinGroups mixins={row.frame!.mixins}/>:<p>이 프레임에 Mixin 정보가 없습니다.</p>}</details>)}</div>
 </>;
}
