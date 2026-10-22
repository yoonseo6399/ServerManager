import {test,expect,Page} from '@playwright/test';

async function fixture(page:Page,{state='STOPPED',canManage=true}={}) {
 const previews:any[]=[],toggles:any[]=[];let forbidden=false,revision='r';
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'user',name:'User',owner:canManage,permissions:{test:'READ'}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'모드 검증',loader:'NeoForge',minecraft:'1.21.1',port:25565,memoryMb:1024},state,pid:null}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/mods'))body={inventory:url.searchParams.has('inventory')?{revision,mods:['sodium.jar','iris.jar','other.jar'].map(filename=>({filename,enabled:true,size:100,sha256:'a'.repeat(64),source:null})),warnings:[],dependencyFiles:['sodium.jar'],dependencyReasons:{'sodium.jar':['iris.jar']},blockedReasons:{'sodium.jar':'필수 의존성입니다. 사용 중: iris.jar'}}:null,inventoryRevision:revision,job:null,diagnostic:null,next:null,active:false};
  else if(path.endsWith('/toggle-preview')){
   const req=route.request().postDataJSON();previews.push(req);
   if(forbidden)return route.fulfill({status:403,json:{message:'권한이 없습니다'}});
   if(!req.ignoreDependencies)return route.fulfill({status:400,json:{message:'필수 의존성입니다. 사용 중: iris.jar',code:'MOD_DEPENDENCY_BLOCKED'}});
   body={...req,selected:req.files,affected:req.files,warnings:['서버가 시작되지 않거나 충돌할 수 있습니다.','필수 의존성이 없어지는 모드: iris.jar']};
  }
  else if(path.endsWith('/toggle'))toggles.push(route.request().postDataJSON());
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-window'))body={lines:[],total:0,first:0,last:0,next:null};
  else if(path.endsWith('/log-display'))body={revision:0,enabled:false,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.getByRole('switch',{name:'sodium.jar 비활성화',exact:true})).toBeVisible();
 return {previews,toggles,forbid:()=>{forbidden=true;},changeRevision:()=>{revision='new';}};
}

test('dependency card offers orange force disable with warning and confirmation before exact rename request',async({page})=>{
 const f=await fixture(page);
 await expect(page.getByRole('switch',{name:'sodium.jar 비활성화',exact:true})).toBeDisabled();
 const force=page.getByRole('button',{name:'sodium.jar 강제로 끄기',exact:true});
 expect(await force.evaluate(el=>getComputedStyle(el).backgroundColor)).toBe('rgb(185, 87, 14)');
 await force.click();let dialog=page.getByRole('dialog',{name:'모드 변경 확인'});
 await expect(dialog).toContainText('강제 비활성화');await expect(dialog).toContainText('iris.jar');
 expect(f.toggles).toEqual([]);await dialog.getByRole('button',{name:'취소',exact:true}).click();expect(f.toggles).toEqual([]);
 await force.click();dialog=page.getByRole('dialog',{name:'모드 변경 확인'});
 await expect(dialog).toBeVisible();
 await page.screenshot({path:'test-results/mod-force-disable-desktop.png'});
 await dialog.getByRole('button',{name:'위험을 확인하고 강제로 끄기',exact:true}).click();
 expect(f.toggles).toEqual([{revision:'r',files:['sodium.jar'],enabled:false,ignoreDependencies:true}]);
 await page.setViewportSize({width:390,height:844});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'test-results/mod-force-disable-mobile.png',fullPage:true});
});

test('bulk disable includes protected selections and only dependency rejection offers the override',async({page})=>{
 const f=await fixture(page);
 await page.getByLabel('sodium.jar 선택',{exact:true}).check();await page.getByLabel('other.jar 선택',{exact:true}).check();
 await page.getByRole('button',{name:'선택 비활성화 (2)',exact:true}).click();
 const notice=page.getByRole('alert');await expect(notice).toContainText('비활성화 불가');
 await notice.getByRole('button',{name:'의존성 무시하고 강제로 끄기',exact:true}).click();
 expect(f.previews).toEqual([{revision:'r',files:['sodium.jar','other.jar'],enabled:false},{revision:'r',files:['sodium.jar','other.jar'],enabled:false,ignoreDependencies:true}]);
 await page.getByRole('dialog').getByRole('button',{name:'취소',exact:true}).click();
 f.forbid();await page.getByRole('button',{name:'선택 비활성화 (2)',exact:true}).click();
 await expect(notice).toContainText('권한이 없습니다');
 await expect(page.getByRole('button',{name:'의존성 무시하고 강제로 끄기',exact:true})).toHaveCount(0);
 expect(f.toggles).toEqual([]);
});

test('force disable remains locked during a running server or for a read-only viewer',async({page})=>{
 await fixture(page,{state:'RUNNING'});
 await expect(page.getByRole('button',{name:'sodium.jar 강제로 끄기',exact:true})).toBeDisabled();
 await page.unroute('**/api/**');await fixture(page,{canManage:false});
 await expect(page.getByRole('button',{name:'sodium.jar 강제로 끄기',exact:true})).toBeDisabled();
});

test('stale bulk override cannot be confirmed against a refreshed inventory',async({page})=>{
 const f=await fixture(page);
 await page.getByLabel('sodium.jar 선택',{exact:true}).check();await page.getByRole('button',{name:'선택 비활성화 (1)',exact:true}).click();
 f.changeRevision();
 await expect(page.getByRole('button',{name:'의존성 무시하고 강제로 끄기',exact:true})).toBeDisabled();
 expect(f.toggles).toEqual([]);
});
