import {test,expect,type Page} from '@playwright/test';
async function fixture(page:Page,state='STOPPED'){
 const requests:any[]=[];let current={minecraft:'1.21.1',loaderVersion:'21.1.264'},job:any=null,polls=0;
 await page.route('**/api/**',async route=>{
  const r=route.request(),url=new URL(r.url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'Version fixture',loader:'NeoForge',...current,port:25565,memoryMb:1024,javaPath:'/java',runnerId:'runner'},state,pid:state==='RUNNING'?123:null}];
  else if(path==='/api/runners')body=[{id:'runner',name:'Runner',online:true,version:'0.7.0-beta.32',allowedInstanceIds:['test']}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/file-uploads')||path.endsWith('/log-files'))body=[];
  else if(path==='/api/log-filters/test')body={revision:0,config:{enabled:false,rules:{keywords:[],mods:[],onlyDebug:false},presets:[],activeId:null}};
  else if(path.endsWith('/log-window'))body={lines:[],total:0,first:0,last:0,next:null};
  else if(path==='/api/minecraft-versions')body={versions:['1.21.1','1.21.2']};
  else if(path==='/api/version-catalog')body={versions:url.searchParams.get('minecraft')==='1.21.2'?['21.2.1']:['21.1.264','21.1.265']};
  else if(path.endsWith('/runtime-update')){
   if(r.method()==='POST'){requests.push(r.postDataJSON());job={state:'PREPARING',target:r.postDataJSON(),logs:['preparing runtime']};body=job;}
   else if(!job)body=null;
   else{polls++;if(polls>=2){current=job.target;job={...job,state:'COMPLETE',logs:['official runtime installed'],backup:'/fixture/runtime-backup'};}else job={...job,state:'INSTALLING',logs:['downloading official runtime']};body=job;}
  }
  await route.fulfill({json:body});
 });return {requests};
}
test('loader version change requires reviewing a centered plan and then reports progress and backup',async({page})=>{
 const f=await fixture(page);await page.goto('/');await page.getByRole('button',{name:'설정',exact:true}).click();
 await page.getByLabel('변경할 로더 버전').selectOption('21.1.265');await page.getByRole('button',{name:'버전 변경 계획 확인'}).click();
 const dialog=page.getByRole('dialog',{name:'서버 버전 변경 확인'});await expect(dialog).toBeVisible();await expect(dialog).toContainText('월드·모드·config·KubeJS는 보존');expect(f.requests).toHaveLength(0);
 await dialog.getByRole('button',{name:'확인하고 버전 변경'}).click();await expect(page.getByText('버전 변경 완료',{exact:true})).toBeVisible();expect(f.requests).toEqual([{minecraft:'1.21.1',loaderVersion:'21.1.265'}]);await expect(page.getByText('기존 실행 파일 백업:')).toBeVisible();
});
test('Minecraft upgrade uses the matching loader catalog and can be cancelled before sending',async({page})=>{
 const f=await fixture(page);await page.goto('/');await page.getByRole('button',{name:'설정',exact:true}).click();await page.getByLabel('변경할 Minecraft 버전').selectOption('1.21.2');await expect(page.getByLabel('변경할 로더 버전')).toHaveValue('21.2.1');
 await page.getByRole('button',{name:'버전 변경 계획 확인'}).click();await expect(page.getByRole('dialog')).toContainText('Minecraft 1.21.1 → 1.21.2');await page.getByRole('dialog').getByRole('button',{name:'취소',exact:true}).click();expect(f.requests).toHaveLength(0);
});
test('runtime changes are disabled while Minecraft is running',async({page})=>{
 await fixture(page,'RUNNING');await page.goto('/');await page.getByRole('button',{name:'설정',exact:true}).click();await expect(page.getByLabel('변경할 Minecraft 버전')).toBeDisabled();await expect(page.getByRole('button',{name:'버전 변경 계획 확인'})).toBeDisabled();
});
