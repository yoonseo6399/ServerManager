import {test,expect} from '@playwright/test';

test('dependency refusal offers orange force enable beside reason and confirms exact override payload',async({page})=>{
 const previews:any[]=[],toggles:any[]=[];let forbidden=false;
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'모드 검증',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.252',port:25565,memoryMb:1024,javaPath:'java',runnerId:null},state:'STOPPED',pid:null}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,committedHeapMb:0,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/mods'))body={inventory:url.searchParams.has('inventory')?{revision:'r',mods:[{filename:'custom.jar.disabled',enabled:false,size:100,sha256:'a'.repeat(64),source:null,warning:'Modrinth 조회 실패'}],warnings:['일부 조회 실패: 파일 목록은 유지됩니다'],dependencyFiles:[],dependencyReasons:{}}:null,inventoryRevision:'token',job:null,diagnostic:null,next:null,active:false};
  else if(path.endsWith('/toggle-preview')){
   const req=route.request().postDataJSON();previews.push(req);
   if(forbidden)return route.fulfill({status:403,json:{message:'권한이 없습니다'}});
   if(!req.ignoreDependencies)return route.fulfill({status:400,json:{message:'필수 모드 missing이 없습니다',code:'MOD_DEPENDENCY_BLOCKED'}});
   body={...req,selected:req.files,affected:req.files,warnings:['의존성 검사를 무시합니다. 서버가 시작되지 않거나 충돌할 수 있습니다.']};
  }
  else if(path.endsWith('/toggle'))toggles.push(route.request().postDataJSON());
  else if(path.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-display'))body={revision:0,enabled:true,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.locator('.mod-card')).toContainText('custom.jar.disabled');
 await page.getByRole('switch',{name:'custom.jar.disabled 활성화',exact:true}).click();
 const notice=page.getByRole('alert');await expect(notice).toContainText('활성화 불가');
 const force=notice.getByRole('button',{name:'의존성 무시하고 그냥 켜기'});await expect(force).toBeVisible();
 expect(await force.evaluate(el=>getComputedStyle(el).backgroundColor)).toBe('rgb(185, 87, 14)');
 await page.screenshot({path:'test-results/mod-force-enable-desktop.png'});
 await force.click();const dialog=page.getByRole('dialog',{name:'모드 변경 확인'});
 await expect(dialog).toContainText('강제 활성화');await expect(dialog).toContainText('충돌할 수 있습니다');expect(toggles).toEqual([]);
 await dialog.getByRole('button',{name:'위험을 확인하고 그냥 켜기'}).click();
 expect(previews).toEqual([{revision:'r',files:['custom.jar.disabled'],enabled:true},{revision:'r',files:['custom.jar.disabled'],enabled:true,ignoreDependencies:true}]);
 expect(toggles).toEqual([{revision:'r',files:['custom.jar.disabled'],enabled:true,ignoreDependencies:true}]);
 forbidden=true;await page.getByRole('switch',{name:'custom.jar.disabled 활성화',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('권한이 없습니다');await expect(page.getByRole('button',{name:'의존성 무시하고 그냥 켜기'})).toHaveCount(0);
});

test('a failed initial inventory is retryable and no longer spins forever',async({page})=>{
 let refreshes=0;
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'모드 검증',loader:'Fabric',minecraft:'1.21.1',port:25565,memoryMb:1024},state:'STOPPED',pid:null}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,committedHeapMb:0,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/mods/refresh'))refreshes++;
  else if(path.endsWith('/mods'))body={inventory:null,inventoryRevision:null,job:refreshes?{phase:'FAILED',message:'일시적인 파일 조회 실패',files:0,bytes:0}:null,diagnostic:null,next:null,active:false};
  else if(path.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-display'))body={revision:0,enabled:true,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.getByText('일시적인 파일 조회 실패')).toBeVisible();
 await expect(page.locator('.mods-empty .mod-spinner')).toHaveCount(0);
 await page.getByRole('button',{name:'모드 목록 다시 불러오기'}).click();expect(refreshes).toBe(2);
});
