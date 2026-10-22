// Thin host for pinned upstream Pi. No ServerManager actor loop, parser or compactor.
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import {Agent,setGlobalDispatcher} from 'undici';
import {createAgentSession,DefaultResourceLoader,ModelRuntime,SessionManager,SettingsManager} from '@earendil-works/pi-coding-agent';
const line=readline.createInterface({input:process.stdin});
let configResolve;const initial=new Promise(r=>configResolve=r);const waiting=new Map();let session;let sequence=0;let ended=false;const pendingSteers=[];let steering=Promise.resolve();
const send=v=>process.stdout.write(JSON.stringify(v)+'\n');
line.on('line',raw=>{try{const q=JSON.parse(raw);if(q.type==='config')configResolve(q);else if(q.type==='reply'){const p=waiting.get(q.id);if(p){waiting.delete(q.id);q.error?p.reject(Error(q.error)):p.resolve(q.value)}}else if(q.type==='abort')session?.abort();else if(q.type==='steer'){pendingSteers.push(q.text);drainSteers()}}catch(e){send({type:'error',message:'Invalid host protocol'})}});
function drainSteers(){steering=steering.then(async()=>{while(session&&pendingSteers.length){const text=pendingSteers.shift();await session.steer(text);send({type:'steer',text})}}).catch(e=>send({type:'error',message:'Steering failed: '+String(e)}));return steering}
line.on('close',()=>{if(!ended){session?.abort();process.exitCode=1;setTimeout(()=>process.exit(1),500).unref()}});
async function rpc(kind,payload){const id=String(++sequence);send({type:'rpc',id,kind,...payload});return await new Promise((resolve,reject)=>waiting.set(id,{resolve,reject}))}
try{
 const c=await initial;const transportMs=(2*c.requestTimeoutSeconds+630)*1000;setGlobalDispatcher(new Agent({headersTimeout:transportMs,bodyTimeout:transportMs}));const agentDir=c.state;fs.mkdirSync(agentDir,{recursive:true,mode:0o700});
 const modelsPath=path.join(agentDir,'models.json');
 fs.writeFileSync(modelsPath,JSON.stringify({providers:{managed:{baseUrl:c.api,api:'openai-completions',apiKey:c.token,models:[{id:c.model,name:c.model,contextWindow:c.window,maxTokens:c.output,reasoning:c.think,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0}}]}}}),{mode:0o600});
 const runtime=await ModelRuntime.create({modelsPath,authPath:path.join(agentDir,'auth.json'),modelsStorePath:path.join(agentDir,'catalog.json'),allowModelNetwork:false,refreshOnCreate:false});
 const settings=SettingsManager.inMemory({compaction:{enabled:true,reserveTokens:c.window-c.limit,keepRecentTokens:Math.min(512,Math.max(16,Math.floor(c.limit/8)))},httpIdleTimeoutMs:transportMs,retry:{enabled:false,provider:{maxRetries:0,timeoutMs:transportMs}}});
 const resultCache=new Map();
 const exactResults=new Set(['read','edit','write','read_file','edit_file','write_file','read_result','read_attachment','read_chat_history','ask_user']);
 async function boundResult(name,id,content,history=false,originalOutput){
  if(exactResults.has(name)||!content?.every(p=>p.type==='text'))return;
  const text=content.map(p=>p.text).join('\n');if(text.length<=c.toolOutputLimit)return;
  const key=JSON.stringify([name,id,text]);let value=resultCache.get(key);
  if(!value){try{value=await rpc('limit_output',{name,call:id,text,history,originalOutput});resultCache.set(key,value)}catch{value={text:'[출력 원문 보관 실패. 도구는 이미 실행됐을 수 있습니다. 재실행하지 말고 사용자에게 보고하세요.]\n'+text.slice(0,Math.max(0,c.toolOutputLimit-140))}}}
  return [{type:'text',text:value.text}];
 }
 const extension=pi=>{
  pi.on('tool_call',async e=>{try{if(e.toolName==='bash')e.input.timeout=Math.min(1800,Math.max(1,Number(e.input.timeout)||900));const v=await rpc('authorize',{call:e.toolCallId,name:e.toolName,args:e.input});if(!v.allowed)return{block:true,reason:v.reason||'EXECUTION_DENIED: Tool was NOT executed. Backend refused permission; do not bypass or claim success.'}}catch(error){return{block:true,reason:String(error?.message||error)}}});
  pi.on('tool_result',async e=>{const content=await boundResult(e.toolName,e.toolCallId,e.content,false,e.toolName==='bash'?e.details?.fullOutputPath:undefined);if(content)return {content,details:e.details,isError:e.isError}});
  // Bound old oversized results as well, without rewriting the private original session.
  pi.on('context',async e=>{const messages=[];for(const m of e.messages){if(m.role==='toolResult'){const content=await boundResult(m.toolName,m.toolCallId,m.content,true,m.toolName==='bash'?m.details?.fullOutputPath:undefined);messages.push(content?{...m,content,structuredContent:undefined}:m)}else messages.push(m)}return {messages}});
  for(const t of c.extraTools)pi.registerTool({...t,label:t.name,execute:async(id,args,signal,onUpdate)=>{const r=await rpc('execute',{call:id,name:t.name,args});return {content:[{type:'text',text:r.text}],details:{exitCode:r.exitCode,stored:r.stored}}}});
 };
 // Load only this application's inline extension, never project/global plugins or skills.
 const loader=new DefaultResourceLoader({cwd:agentDir,agentDir,settingsManager:settings,noExtensions:true,noSkills:true,noPromptTemplates:true,noThemes:true,noContextFiles:true,disabledBuiltinExtensions:['mcp','subagents','codemode'],extensionFactories:[extension],appendSystemPrompt:[c.system]});await loader.reload();
 const file=path.join(agentDir,'current-session.txt');let manager;
 if(fs.existsSync(file)){const saved=fs.readFileSync(file,'utf8');if(saved.startsWith(path.join(agentDir,'sessions')+path.sep)&&fs.existsSync(saved))manager=SessionManager.open(saved)}
 manager??=SessionManager.create(c.home,path.join(agentDir,'sessions'));
 ({session}=await createAgentSession({cwd:c.home,agentDir,modelRuntime:runtime,model:runtime.getModel('managed',c.model),thinkingLevel:c.think?'medium':'off',settingsManager:settings,resourceLoader:loader,sessionManager:manager,tools:c.toolNames}));
 fs.writeFileSync(file,session.sessionFile,{mode:0o600});
 session.subscribe(e=>{
  if(['tool_execution_start','tool_execution_update','tool_execution_end'].includes(e.type)){const slim={...e};for(const key of ['result','partialResult'])if(slim[key])slim[key]={exitCode:slim[key].structuredContent?.exit_code??slim[key].details?.exitCode??null,content:(slim[key].content||[]).filter(p=>p.type==='text').map(p=>({...p,text:p.text.slice(0,60000)}))};send({type:'event',event:slim})}
  if(['compaction_start','compaction_end','auto_compaction_start','auto_compaction_end'].includes(e.type))send({type:'compaction',event:e.type,error:e.errorMessage?.slice(0,500),aborted:e.aborted===true});
 });
 if(!fs.existsSync(path.join(agentDir,'imported'))){
  // One-time conversational migration only. Existing custom plans/summaries are not used as policy.
  if(c.history.length)await session.sendCustomMessage({customType:'previous-conversation',content:JSON.stringify(c.history),display:false},{triggerTurn:false});
  fs.writeFileSync(path.join(agentDir,'imported'),'1',{mode:0o600});
 }
 await drainSteers();
 let compacted=true;
 if(c.compactOnly){try{await session.compact('Preserve exact identifiers and original-language names, corrected facts, cancelled work and unfinished tasks. Do not revive old requests.')}catch(e){if(!String(e).includes('Nothing to compact')&&!String(e).includes('Already compacted'))throw e;compacted=false}}
 else await session.prompt(JSON.stringify({requester:c.requester,request:c.request}));
 const last=[...session.messages].reverse().find(m=>m.role==='assistant');if(last&&['error','aborted'].includes(last.stopReason))throw Error(last.errorMessage||'Pi request failed');
 fs.writeFileSync(path.join(agentDir,'usage.json'),JSON.stringify(session.getContextUsage()||{}),{mode:0o600});
 send({type:'done',text:c.compactOnly?(compacted?'Pi 문맥 압축을 마쳤습니다.':'Pi 문맥이 이미 압축되었거나 압축할 이전 대화가 없습니다.'):session.getLastAssistantText()||'응답이 비어 있습니다.',sessionFile:session.sessionFile});
}catch(e){send({type:'error',message:String(e?.message||e).slice(0,1500)});process.exitCode=1}
finally{ended=true;session?.dispose();for(const p of waiting.values())p.reject(Error('Host finished'));waiting.clear();line.close()}
