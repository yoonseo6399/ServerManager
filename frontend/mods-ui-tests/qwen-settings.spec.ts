import {test,expect} from '@playwright/test';

test('owner picks a runner and model, checks the remote Ollama environment and unloads it',async({page})=>{
 const saved:any[]=[],unloaded:any[]=[],installed:any[]=[];let strataInstalled=false;let loaded=['qwen3:30b'];let config:any={runnerId:null,model:'14b'};
 const probe=()=>({reachable:true,version:'0.12.0',models:[{name:'qwen3:14b',sizeBytes:9e9},{name:'qwen3:30b',sizeBytes:19e9}],loaded,tags:{'14b':'qwen3:14b','30b':'qwen3:30b'},installed:{'14b':true,'30b':true},message:'Ollama 실행 중',memory:{totalBytes:24*1073741824,usedBytes:18*1073741824,swapUsedBytes:1073741824,pressure:'정상',modelBytes:12*1073741824,modelMetric:'Ollama 전체 할당',wiredLimitMb:20480,sampledAt:Date.now()},strata:{supported:true,installed:strataInstalled,running:false,state:strataInstalled?'READY':'MISSING',message:strataInstalled?'설치 완료':'설치 대기'}});
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'0.5.0',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:'http://127.0.0.1:5175',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances'||path==='/api/runners'||path==='/api/auth/credentials'||path==='/api/admin/users'||path==='/api/admin/audit')body=[];
  else if(path==='/api/admin/discord')body={config:{enabled:false,grants:[],alerts:[],superAdmins:[]},revision:0,tokenConfigured:false};
  else if(path==='/api/admin/discord/status')body={state:'DISABLED',message:'',inviteUrl:null,guilds:[]};
  else if(path==='/api/admin/main-agent')body={keys:[],config:{provider:'OPENAI',credentialRef:'pool:*',model:'gpt-6.1-sol',reasoning:'medium',parallelism:3,maxTurns:12,tokenBudget:120000},keyConfigured:false};
  else if(path==='/api/admin/webdav')body={config:{enabled:false,url:'',username:''},revision:0,passwordConfigured:false};
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false,labels:{},hosts:{}};
  else if(path==='/api/admin/assistant')body={policy:{roots:[],executables:[],engine:'native'},tasks:[],aider:{available:false}};
  else if(path==='/api/admin/agent-keys')body={keys:[],projects:[]};
  else if(path==='/api/admin/storage')body={supported:false,path:'',pending:null};
  else if(path==='/api/admin/updates')body={supported:false,enabled:false,current:'0.5.0',latest:null,message:'',checking:false,repository:''};
  else if(path==='/api/admin/qwen'&&route.request().method()==='GET')body={config,models:[{id:'14b',label:'Qwen3 14B',supportsThinking:true},{id:'30b',label:'Qwen3 30B',supportsThinking:true},{id:'qwen3-coder-30b',label:'Qwen3-Coder 30B',supportsThinking:false},{id:'gemma4-12b',label:'Gemma 4 12B',supportsThinking:true},{id:'gemma4-31b',label:'Gemma 4 31B',supportsThinking:true},{id:'qwen38-27b',label:'Qwen3.8 27B (Q4_K_M)',supportsThinking:true},{id:'qwen38-flash-next',label:'Qwen3.8 Flash-Next (Strata Q2_0)',supportsThinking:true}],runners:[
   {id:'gpu',name:'GPU PC',online:true,capability:'ALLOWED'},{id:'laptop',name:'노트북',online:true,capability:'DENIED'}]};
  else if(path==='/api/admin/qwen'){config=route.request().postDataJSON();saved.push(config);body={saved:true,unloaded:null};}
  else if(path==='/api/admin/qwen/probe')body=probe();
  else if(path==='/api/admin/qwen/strata/install'){installed.push(route.request().postDataJSON());strataInstalled=true;body=probe().strata;}
  else if(path==='/api/admin/qwen/unload'){unloaded.push(route.request().postDataJSON());loaded=[];body=probe();}
  await route.fulfill({json:body});
 });


 await page.goto('/');await page.getByRole('button',{name:'AI 비서·에이전트',exact:true}).click();


 const section=page.getByRole('region',{name:'로컬 LLM'});
 await expect(section.getByRole('button',{name:'실행 환경 확인',exact:true})).toBeEnabled();
 await section.getByLabel('실행 컴퓨터').selectOption('laptop');
 await expect(section).toContainText('llm-enabled');await expect(section.getByRole('button',{name:'실행 환경 확인',exact:true})).toBeDisabled();
 await section.getByLabel('실행 컴퓨터').selectOption('gpu');
 await section.getByRole('button',{name:'실행 환경 확인',exact:true}).click();
 await expect(section).toContainText('Ollama 0.12.0 실행 중');await expect(section).toContainText('qwen3:30b');await expect(section).toContainText('메모리에 올라감');await expect(section).toContainText('18.0 GiB / 24.0 GiB');await expect(section).toContainText('20.0 GiB');await expect(section).toContainText('스왑 1.0 GiB');
 await section.getByRole('button',{name:'Flash-Next 설치 (약 66.4GB)',exact:true}).click();
 await expect.poll(()=>installed).toEqual([{runnerId:'gpu'}]);await expect(section).toContainText('설치 완료');await expect(section.getByRole('button',{name:'Flash-Next 설치 (약 66.4GB)',exact:true})).toBeDisabled();
 for(const id of ['qwen3-coder-30b','gemma4-12b','gemma4-31b','qwen38-27b','qwen38-flash-next']){
  await section.getByLabel('모델').selectOption(id);
  if(id==='qwen3-coder-30b')await expect(section).toContainText('Thinking 미지원');
  if(id==='gemma4-31b')await expect(section).toContainText('스왑');
  await section.getByRole('button',{name:'로컬 LLM 설정 저장',exact:true}).click();
  await expect.poll(()=>saved.at(-1)).toEqual({runnerId:'gpu',model:id});
 }
 await section.getByLabel('모델').selectOption('30b');
 await section.getByRole('button',{name:'로컬 LLM 설정 저장',exact:true}).click();
 await expect.poll(()=>saved.at(-1)).toEqual({runnerId:'gpu',model:'30b'});
 await section.getByRole('button',{name:'메모리에서 내리기',exact:true}).click();
 await expect.poll(()=>unloaded).toEqual([{runnerId:'gpu'}]);
 await expect(section.getByRole('button',{name:'메모리에서 내리기',exact:true})).toBeDisabled();
});
