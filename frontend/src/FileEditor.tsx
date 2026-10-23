import {memo,useState} from 'react';
import {SaveBar} from './SaveBar';
export type EditableFile={path:string;content:string;revision?:string};
export const FileEditor=memo(function FileEditor({file,disabled,busy,onClose,onSave}:{file:EditableFile;disabled:boolean;busy:boolean;onClose:()=>void;onSave:(file:EditableFile)=>void}){
 const [content,setContent]=useState(file.content);
 const dirty=content!==file.content||file.revision===undefined;
 const save=()=>onSave({...file,content});
 return <div className="editor"><div><strong>{file.path}{dirty?' · 미저장':''}</strong><button disabled={busy} onClick={onClose}>닫기</button></div><textarea aria-label="파일 내용" spellCheck={false} disabled={disabled||busy} value={content} onChange={e=>setContent(e.target.value)}/><footer><span>{busy?'저장 결과를 확인하고 있습니다…':disabled?'현재는 읽기 전용입니다.':dirty?'변경 내용은 아직 저장되지 않았습니다.':'저장된 내용입니다. 원본은 저장 전 자동 백업합니다.'}</span><button className="primary" disabled={busy||disabled||!dirty} onClick={save}>저장</button></footer><SaveBar label={'파일: '+file.path} dirty={dirty} busy={busy} disabled={disabled} onSave={save} onReset={()=>setContent(file.content)} saveLabel="파일 저장"/></div>;
});
