import {test,expect} from '@playwright/test';

test('an immediate startup crash follows old install output in live console without reload or file selection',async({page})=>{
 const lines=['The server installed successfully'];let state='STOPPED',reads=0;
 const crash='Exception in thread "main" java.lang.NoClassDefFoundError: org/lwjgl/Version';
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'Startup crash',loader:'NeoForge',minecraft:'1.21.1',port:25565,memoryMb:1024,runnerId:'runner'},state,pid:null}];
  else if(path==='/api/runners')body=[{id:'runner',name:'Runner',online:true,allowedInstanceIds:['test']}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-display'))body={revision:0,enabled:false,rules:[],presets:[]};
  else if(path.endsWith('/log-window')){
   reads++;expect(url.searchParams.get('path')).toBe('console.log');
   const from=Number(url.searchParams.get('from')),limit=Number(url.searchParams.get('limit'));
   const rows=lines.map((text,n)=>({no:n+1,text,kind:text.startsWith('Exception')||text.startsWith('Caused by:')?'error':'output'})).filter(row=>row.no>=from).slice(0,limit);
   body={lines:rows,total:lines.length,first:rows[0]?.no||0,last:rows.at(-1)?.no||0,next:null};
  }
  else if(path.endsWith('/start')){
   state='FAILED';lines.push(crash,'\tat sodium_service/net.caffeinemc.mods.sodium.PreLaunchChecks.checkEnvironment(PreLaunchChecks.java:25)','Caused by: java.lang.ClassNotFoundException: org.lwjgl.Version');
  }
  await route.fulfill({json:body});
 });
 await page.goto('/');
 await expect(page.getByTestId('log-viewport')).toContainText('The server installed successfully');
 const initialReads=reads;
 await page.getByRole('button',{name:'시작',exact:true}).click();
 await expect(page.getByTestId('log-viewport')).toContainText(crash);
 await expect(page.getByTestId('log-viewport')).toContainText('Caused by: java.lang.ClassNotFoundException: org.lwjgl.Version');
 await expect(page.getByTestId('log-count')).toContainText('4줄');
 expect(reads).toBeGreaterThan(initialReads);
 await page.screenshot({path:'test-results/remote-live-crash.png'});
});
