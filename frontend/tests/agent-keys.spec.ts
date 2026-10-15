import {test,expect} from '@playwright/test';

test('owner manages key metadata without reading stored secrets',async({page})=>{
 let keys=['default','main','yoonseo'].map(id=>({id,project:id,rpm:5,tpm:250000,rpd:20,enabled:true}));
 let saved:any=null;
 let mainConfig={model:'gpt-6.1-sol',reasoning:'medium',parallelism:3,maxTurns:12,tokenBudget:120000};
 let mainSaved:any=null;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  let json:any={};
  if(path==='/api/auth/session')json={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8787',publicOrigin:'',setupRequired:false};
  else if(path==='/api/admin/main-agent'){
   if(route.request().method()==='PUT'){mainSaved=route.request().postDataJSON();mainConfig=mainSaved.config;json={saved:true};}
   else json={config:mainConfig,keyConfigured:false};
  }
  else if(path==='/api/admin/agent-keys'){
   if(route.request().method()==='PUT'){
    saved=route.request().postDataJSON();keys=keys.map(k=>k.id===saved.id?{...k,...saved,key:undefined}:k);json={saved:true};
   }else json={keys,usage:keys.map(k=>`${k.project}: RPM 0/5 · RPD 0/20`).join('\n'),localUsageOnly:true};
  }else if(['/api/instances','/api/auth/credentials','/api/admin/users','/api/admin/audit'].includes(path))json=[];
  else if(path==='/api/admin/discord')json={config:{enabled:false,grants:[],alerts:[],superAdmins:[]},revision:0,tokenConfigured:true};
  else if(path==='/api/admin/discord/status')json={state:'OFF',message:'',guilds:[]};
  else if(path==='/api/admin/webdav')json={config:{enabled:false,url:'',username:''},revision:0,passwordConfigured:false};
  else if(path==='/api/admin/storage')json={supported:false,path:'/test',pending:null};
  else if(path==='/api/admin/updates')json={supported:false,enabled:false,current:'test',latest:null,message:'',checking:false,repository:''};
  await route.fulfill({json});
 });
 await page.goto('/');
 await page.getByRole('button',{name:'계정과 보안',exact:true}).click();
 const panel=page.getByRole('region',{name:'루오노타르 Gemini API 키'});
 await expect(panel.getByText('main · main · 활성')).toBeVisible();
 await panel.getByRole('button',{name:'설정 편집'}).nth(1).click();
 await expect(panel.getByLabel('키 이름')).toHaveValue('main');
 await expect(panel.getByLabel('API 키',{exact:true})).toHaveValue('');
 await panel.getByLabel('RPM',{exact:true}).fill('4');
 await panel.getByRole('button',{name:'키 설정 저장'}).click();
 await expect(panel.getByRole('status')).toContainText('저장했습니다');
 expect(saved.key).toBeNull();expect(saved.rpm).toBe(4);
 await panel.screenshot({path:'test-results/agent-keys.png'});
 const mainPanel=page.getByRole('region',{name:'GPT 메인 에이전트'});
 await expect(mainPanel.getByLabel('OpenAI API 키',{exact:true})).toHaveValue('');
 await mainPanel.getByLabel('GPT 모델 ID').fill('gpt-test');
 await mainPanel.getByLabel('추론 강도').selectOption('low');
 await mainPanel.getByLabel('동시 서브 작업').fill('2');
 await mainPanel.getByRole('button',{name:'메인 에이전트 설정 저장'}).click();
 await expect(mainPanel.getByRole('status')).toContainText('저장했습니다');
 expect(mainSaved.key).toBeNull();expect(mainSaved.config.model).toBe('gpt-test');
 expect(mainSaved.config.parallelism).toBe(2);expect(mainSaved.config.reasoning).toBe('low');
 await mainPanel.screenshot({path:'test-results/main-agent.png'});
});
