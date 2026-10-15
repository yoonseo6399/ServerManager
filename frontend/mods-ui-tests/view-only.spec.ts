import {test,expect} from '@playwright/test';

const mod=(n:number)=>({filename:`mod-${n}.jar`,enabled:true,size:100,sha256:'a'.repeat(64),warning:null,source:{projectId:`p${n}`,slug:`mod-${n}`,title:`Mod ${n}`,description:'',iconUrl:null,versionNumber:'1.0',environment:'client_and_server',clientOnly:false,dependencies:[]}});

test('running server shows the mod list read-only and the list loads on its own without a button',async({page})=>{
 let refreshes=0,listed=false;
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'0.4.12',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'실행 중',loader:'Fabric',minecraft:'1.21.1',loaderVersion:'0.19.5',port:25565,memoryMb:1024,javaPath:'java',runnerId:null},state:'RUNNING',pid:1}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,committedHeapMb:0,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path==='/api/health')body={javaPath:'java'};
  else if(path.endsWith('/mods/refresh')){refreshes++;listed=true;}
  else if(path.endsWith('/mods'))body={inventory:listed&&url.searchParams.has('inventory')?{revision:'r',mods:[mod(1),mod(2)],warnings:[],dependencyFiles:[],dependencyReasons:{},missing:{},projects:{}}:null,inventoryRevision:listed?'token':null,job:null,diagnostic:null,next:null,active:false};
  else if(path.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-display'))body={revision:0,enabled:true,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.getByLabel('mod-1.jar 선택',{exact:true})).toBeVisible();
 expect(refreshes).toBe(1);
 await expect(page.getByRole('button',{name:'모드 목록 불러오기'})).toHaveCount(0);
 await expect(page.getByText('서버가 실행 중이라 모드를 볼 수만 있습니다')).toBeVisible();
 await expect(page.getByRole('switch',{name:'mod-1.jar 비활성화',exact:true})).toBeDisabled();
 await expect(page.getByLabel('mod-1.jar 선택',{exact:true})).toBeDisabled();
});
