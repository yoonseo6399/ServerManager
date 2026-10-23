import {test,expect,Page} from '@playwright/test';
async function fixture(page:Page,fail=false){let posts=0,boot='old',phase='IDLE',runnerSupported=false,runnerPhase='',runnerPosts=0;
 await page.route('**/api/**',async route=>{const url=new URL(route.request().url()),p=url.pathname;let body:any={};
 if(p==='/api/auth/session')body={bootId:boot,appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
 else if(p==='/api/instances')body=[];
 else if(p==='/api/runners')body=[{id:'runner',name:'Runner',registeredBy:'owner',canGrant:true,platform:'MAC',version:'0.7.0-beta.24',online:true,enabled:true,allowedInstanceIds:[],restartSupported:runnerSupported,...(runnerPhase?{restart:{stage:runnerPhase,message:'재시작 상태 시험'}}:{})}];
 else if(p==='/api/runners/runner/update-status')body={available:false,components:[],log:[]};
 else if(p==='/api/runners/runner/restart'){runnerPosts++;runnerPhase='RESTARTING';body={accepted:true};}
 else if(p==='/api/admin/service-restart'){
  if(route.request().method()==='POST'){posts++;phase='REQUESTED';body={supported:true,bootId:'old',stage:phase,canRestart:true};if(!fail)boot='new';}
  else body={supported:true,bootId:boot,stage:fail&&posts?'FAILED':phase,canRestart:true,message:fail&&posts?'새 작업이 시작돼 재시작이 취소됐습니다.':'재시작할 수 있습니다'};
 }
 else if(p==='/api/admin/storage')body={supported:false,path:'',pending:null};
 else if(p==='/api/admin/updates')body={supported:false,current:'fixture',enabled:false,message:'확인 대기'};
 else if(p==='/api/admin/system-logs')body={events:[],nextCursor:0,oldestCursor:0,reset:false,sessionId:'fixture'};
 else if(p==='/api/game-addresses')body={enabled:false};
 await route.fulfill({json:body});});
 return {posts:()=>posts,enableRunner:()=>{runnerSupported=true;},runnerPosts:()=>runnerPosts,completeRunner:()=>{runnerPhase='COMPLETE';}};
}
test('manager restart respects cancel and reports completion only after new boot',async({page})=>{
 const f=await fixture(page);await page.goto('/#system');const button=page.getByRole('button',{name:'관리 앱 재시작',exact:true});await expect(button).toBeEnabled();
 page.once('dialog',d=>d.dismiss());await button.click();expect(f.posts()).toBe(0);
 page.once('dialog',d=>d.accept());await button.click();await expect.poll(()=>f.posts()).toBe(1);await expect(page.getByText(/관리 앱이 새 프로세스로 다시 연결됐습니다/)).toBeVisible();
});
test('manager restart cancellation is visible without waiting for boot timeout',async({page})=>{
 await fixture(page,true);await page.goto('/#system');const button=page.getByRole('button',{name:'관리 앱 재시작',exact:true});await expect(button).toBeEnabled();page.once('dialog',d=>d.accept());await button.click();await expect(page.getByRole('alert').filter({hasText:'새 작업이 시작돼 재시작이 취소됐습니다.'})).toBeVisible({timeout:6000});await expect(button).toBeEnabled();
});
test('runner restart shows support gate request and reconnect status',async({page})=>{
 const f=await fixture(page);await page.goto('/#runners');const button=page.getByRole('button',{name:'실행기 재시작',exact:true});await expect(button).toBeDisabled();f.enableRunner();await expect(button).toBeEnabled();page.once('dialog',d=>d.accept());await button.click();await expect.poll(()=>f.runnerPosts()).toBe(1);await expect(button).toBeDisabled();await expect(page.getByLabel('Runner 재시작 상태')).toContainText('RESTARTING');f.completeRunner();await expect(page.getByLabel('Runner 재시작 상태')).toContainText('COMPLETE');await expect(button).toBeEnabled();
});
