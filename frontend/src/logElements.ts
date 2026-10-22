import type {ParsedLog} from './logParser';
export type LogRecord={id:number;rows:ParsedLog[];time:string|null;source:string;partial:boolean};
export type LogElement={id:number;time:string|null;slot:number|null;precision:'50ms'|'second'|'unknown';sources:{source:string;records:LogRecord[]}[]};
export function logRecords(rows:ParsedLog[]):LogRecord[]{
 const records:LogRecord[]=[];let previous:LogRecord|undefined;
 for(const row of rows){
  const body=row.body??row.display,continuation=!!row.frame||/^\s*(?:Caused by:|Suppressed:|\.\.\. \d+ more)/.test(body);
  const adjacent=previous?.rows.at(-1)?.seq===row.seq-1;
  const sameSource=!row.logger||row.logger===previous?.source;
  // A timestamped STDERR frame is still part of the same exception, not a new record.
  const inherit=!!previous&&adjacent&&sameSource&&(continuation||!row.logTime&&!/^(?:Exception in thread|[\w.$]+(?:Exception|Error)\b)/.test(body.trimStart()));
  if(inherit){previous!.rows.push(row);continue;}
  previous={id:row.seq,rows:[row],time:row.logTime??row.time?.match(/\d{2}:\d{2}:\d{2}(?:[.,]\d{1,3})?/)?.[0]??null,source:row.logger||'출처 미확인',partial:continuation};
  // Untagged exception lines following an ERROR header inherit that header's timestamp and source.
  const last=records.at(-1);
  if(!row.logTime&&adjacent&&last&&['ERROR','FATAL'].includes(last.rows[0].level||'')) {last.rows.push(row);previous=last;continue;}
  records.push(previous);
 }
 return records;
}
export function logElements(records:LogRecord[]):LogElement[]{
 const elements:LogElement[]=[];let current:LogElement|undefined,lastKey='';
 for(const record of records){
  const match=record.time?.match(/^(\d{2}:\d{2}:\d{2})(?:[.,](\d{1,3}))?$/);
  const slot=match?.[2]===undefined?null:Math.floor(Number(match[2].padEnd(3,'0'))/50)+1;
  const key=match?`${match[1]}:${slot??'second'}`:`unknown:${record.id}`;
  if(!current||key!==lastKey){current={id:record.id,time:match?.[1]??record.time,slot,precision:slot!==null?'50ms':match?'second':'unknown',sources:[]};elements.push(current);lastKey=key;}
  let group=current.sources.find(g=>g.source===record.source);
  if(!group){group={source:record.source,records:[]};current.sources.push(group);}
  group.records.push(record);
 }
 return elements;
}
