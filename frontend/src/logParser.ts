export type RawLog={seq:number;time:string|null;kind:string;text:string};
export type Mixin={modId:string;config:string;className:string;phase:string};
export type Frame={className:string;method:string;source:string;module:string;mixins:Mixin[];metadata:string[];origin:string};
export type ParsedLog=RawLog&{display:string;search:string;level?:string;logger?:string;frame?:Frame;logTime?:string;body?:string;thread?:string};
export function trimLogTime(text:string){return text.replace(/^\[(?:\d{4}-\d{2}-\d{2}[ T])?\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\]\s*/, '');}
export function parseLog(event:RawLog):ParsedLog {
 const clean=event.text.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g,''),display=trimLogTime(clean);
 const prefix=/^(?:\[[^\]\r\n]{1,240}\]\s*:?\s*){1,5}/.exec(clean.trimStart())?.[0]||'';
 const tags=[...prefix.matchAll(/\[([^\]\r\n]{1,240})\]/g)].map(m=>m[1]);
 const levelTag=tags.find(t=>/(?:^|[ /])(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)$/i.test(t));
 const level=levelTag?.match(/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)$/i)?.[1]?.toUpperCase()||(['warn','error'].includes(event.kind)?event.kind.toUpperCase():undefined);
 const levelIndex=levelTag===undefined?-1:tags.indexOf(levelTag);
 const timePattern=/\b\d{2}:\d{2}:\d{2}(?:[.,]\d{1,3})?\b/;
 const logTime=tags.map(t=>t.match(timePattern)?.[0]).find(Boolean);
 const logger=levelIndex>=0?tags.slice(levelIndex+1).find(t=>!timePattern.test(t))?.replace(/\/$/,''):undefined;
 const body=prefix?clean.trimStart().slice(prefix.length).replace(/^\s*:\s?/, ''):clean;
 const thread=levelTag?.includes('/')?levelTag.slice(0,levelTag.lastIndexOf('/')):undefined;
 const result:ParsedLog={...event,display,search:clean.toLowerCase(),level,logger,logTime,body,thread};
 const match=/^\s*at\s+([^\s(]+)\(([^)]*)\)(.*)$/.exec(body);
 if(!match)return result;
 const slash=match[1].lastIndexOf('/'),call=match[1].slice(slash+1),dot=call.lastIndexOf('.');
 if(dot<1)return result;
 const suffix=match[3],metadataMatch=/\{(.*)\}\s*$/.exec(suffix),mixins:Mixin[]=[],metadata:string[]=[];
 for(const entry of (metadataMatch?.[1]||'').split(',').map(s=>s.trim()).filter(Boolean)){
  const m=/^pl:mixin:([^:]+):([^:]+):(.+?) from mod (\S+)$/.exec(entry);
  if(m)mixins.push({phase:m[1],config:m[2],className:m[3],modId:m[4]});else metadata.push(entry);
 }
 const frame:Frame={className:call.slice(0,dot),method:call.slice(dot+1),module:slash<0?'':match[1].slice(0,slash),source:match[2],mixins,metadata,origin:(metadataMatch?suffix.slice(0,metadataMatch.index):suffix).trim()};
 result.frame=frame;result.display=`${frame.className.split('.').pop()}.${frame.method}() · ${frame.source}`;
 return result;
}
