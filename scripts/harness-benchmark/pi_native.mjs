import fs from 'node:fs';
import path from 'node:path';
import {createAgentSession,createExtensionRuntime,ModelRuntime,SessionManager,SettingsManager} from '@earendil-works/pi-coding-agent';
const spec=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const agentDir=path.join(spec.output,'pi');fs.mkdirSync(agentDir,{recursive:true});
const modelsPath=path.join(agentDir,'models.json');
fs.writeFileSync(modelsPath,JSON.stringify({providers:{local:{baseUrl:spec.api,api:'openai-completions',apiKey:'ollama',models:[{id:spec.model,name:spec.model,contextWindow:spec.window,maxTokens:2048,reasoning:false,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0}}]}}}));
const runtime=await ModelRuntime.create({modelsPath,authPath:path.join(agentDir,'auth.json'),modelsStorePath:path.join(agentDir,'catalog.json'),allowModelNetwork:false,refreshOnCreate:false});
const tools=[];
// Upstream prompt remains; only automatic external resources are disabled in this controlled integration.
const loader={getExtensions:()=>({extensions:[],errors:[],runtime:createExtensionRuntime()}),getSkills:()=>({skills:[],diagnostics:[]}),getPrompts:()=>({prompts:[],diagnostics:[]}),getThemes:()=>({themes:[],diagnostics:[]}),getAgentsFiles:()=>({agentsFiles:[]}),getSystemPrompt:()=>undefined,getSystemPromptSource:()=>undefined,getAppendSystemPrompt:()=>[spec.system],getAppendSystemPromptSources:()=>[],extendResources:()=>{},reload:async()=>{}};
const options={cwd:spec.home,agentDir,model:runtime.getModel('local',spec.model),modelRuntime:runtime,thinkingLevel:'off',resourceLoader:loader,tools:['read','bash','edit','write'],settingsManager:SettingsManager.inMemory({compaction:{enabled:true,reserveTokens:2048,keepRecentTokens:128},retry:{enabled:false}})};
let {session}=await createAgentSession({...options,sessionManager:SessionManager.create(spec.home,path.join(agentDir,'sessions'))});
function attach(s){s.subscribe(e=>{if(e.type==='tool_execution_start'||e.type==='tool_execution_end')fs.appendFileSync(path.join(spec.output,'native-events.jsonl'),JSON.stringify({at:Date.now()/1000,...e})+'\n')})};attach(session);
const result={framework:'pi',answers:[],compression:[],errors:[],sessionFile:null};
async function save(){fs.writeFileSync(path.join(spec.output,'adapter.json'),JSON.stringify(result,null,2))}
try{
 for(const prompt of spec.prompts){
  if(prompt==='__COMPACT__'){
   const before=JSON.stringify(session.messages);const c=await session.compact('Preserve current human goals, exact identifiers, corrections, constraints and unfinished work. Do not invent facts.');const after=JSON.stringify(session.messages);result.compression.push({changed:before!==after,beforeChars:before.length,afterChars:after.length,summary:c.summary});
   fs.writeFileSync(path.join(spec.output,'before-compaction.json'),before);fs.writeFileSync(path.join(spec.output,'after-compaction.json'),after);
   // Recreate upstream AgentSession from its own persisted file, not an adapter-created summary.
   const file=session.sessionFile;result.sessionFile=file;session.dispose();({session}=await createAgentSession({...options,sessionManager:SessionManager.open(file)}));result.reloaded=true;attach(session);
  }else{
   await session.prompt(prompt);result.answers.push(session.getLastAssistantText()||'');await fetch(spec.control+'/phase',{method:'POST'});
  }
  await save();
 }
 result.messages=session.messages;
}catch(e){result.errors.push(String(e));process.exitCode=1}finally{result.sessionFile=session.sessionFile;session.dispose();await save()}
