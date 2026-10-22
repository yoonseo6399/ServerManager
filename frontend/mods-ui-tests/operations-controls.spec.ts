import {test,expect,Page} from '@playwright/test';
async function fixture(page:Page,state='RUNNING',owner=true){
 let crash=false,edited='',toggles:any[]=[];
 const lines=[
  '[2310월2026 23:11:34.860] [Server thread/INFO] [Create/]: first',
  '[2310월2026 23:11:34.861] [Server thread/INFO] [Other/]: other',
  '[2310월2026 23:11:34.862] [Server thread/INFO] [Create/]: second',
  '[2310월2026 23:11:35.001] [Server thread/ERROR] [Test/]: java.lang.Error: bad',
  '\tat TRANSFORMER/mod@1/net.Example.run(Example.java:12) ~[mod.jar] {pl:mixin:APP:sample.mixins.json:MixinExample from mod sample}',
  'Caused by: java.lang.IllegalStateException: nested',
  '\tat net.Cause.start(Cause.java:22)',
  '[Info] [23:11:36] [LongLogger]: '+ 'long '.repeat(400)
 ];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname,method=route.request().method();let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'user',name:'User',owner,permissions:{test:owner?'ADMIN':'READ'}},csrf:'fixture',origin:url.origin,setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'원격 서버',loader:'Fabric',loaderVersion:'0.16.14',minecraft:'1.21.1',port:25565,memoryMb:512,runnerId:'runner',fileSyncEnabled:true},state,pid:42,liveFiles:true,backgroundSync:true}];
  else if(path==='/api/runners')body=[{id:'runner',name:'Runner',online:true,allowedInstanceIds:['test']}];
  else if(path==='/api/memory')body={totalMb:24000,freeMb:12000,reservedMb:2000,maxHeapMb:22000,startableHeapMb:22000};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path==='/api/log-filters/test')body={revision:0,config:{enabled:false,rules:{keywords:[],mods:[],onlyDebug:true},presets:[],activeId:null}};
  else if(path.endsWith('/log-files'))body=crash?[{path:'server/crash-reports/crash-new-server.txt',name:'server/crash-reports/crash-new-server.txt',size:100}]:[];
  else if(path.endsWith('/log-window')){const from=Number(url.searchParams.get('from')),limit=Number(url.searchParams.get('limit'));const rows=lines.map((text,n)=>({no:n+1,text,kind:'output'})).filter(row=>row.no>=from).slice(0,limit);body={lines:rows,total:lines.length,first:rows[0]?.no||0,last:rows.at(-1)?.no||0,next:null};}
  else if(path.endsWith('/files'))body=[{name:'config.txt',path:'config.txt',directory:false,size:10}];
  else if(path.endsWith('/text')){if(method==='PUT')edited=route.request().postDataJSON().content;body={content:edited||'original\n',revision:'revision'};}
  else if(path.endsWith('/mods'))body={remote:true,editable:true,inventoryRevision:'r',inventory:url.searchParams.has('inventory')?{revision:'r',mods:[{filename:'sample.jar',enabled:true,size:100,sha256:'abc',source:null}],warnings:[],dependencyFiles:[],dependencyReasons:{},missing:{}}:null,job:null,diagnostic:null,next:null,active:false};
  else if(path.endsWith('/toggle-preview')){const req=route.request().postDataJSON();body={...req,selected:req.files,affected:req.files,warnings:[]};}
  else if(path.endsWith('/toggle'))toggles.push(route.request().postDataJSON());
  await route.fulfill({json:body});
 });
 await page.goto('/');return {crash:()=>{crash=true;},edited:()=>edited,toggles};
}
for(const state of ['RUNNING','SYNCING'])test(`runner files and mods remain editable while ${state}`,async({page})=>{
 const f=await fixture(page,state);await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await expect(page.getByRole('button',{name:'파일',exact:true})).toBeEnabled();
 await page.getByRole('button',{name:'config.txt',exact:true}).click();
 await expect(page.locator('.file-editor textarea')).toBeEnabled();
 await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.getByRole('switch',{name:'sample.jar 비활성화',exact:true})).toBeEnabled();
 await page.getByRole('switch',{name:'sample.jar 비활성화',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'모드 변경 확인'});await expect(dialog).toBeVisible();
 await dialog.getByRole('button',{name:'확인하고 변경',exact:true}).click();
 await expect.poll(()=>f.toggles.length).toBe(1);
});
test('viewer retains read only controls even when runner supports live edits',async({page})=>{
 await fixture(page,'RUNNING',false);await page.getByRole('button',{name:'파일 관리자',exact:true}).click();await expect(page.getByRole('button',{name:'파일',exact:true})).toBeDisabled();
 await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();await expect(page.getByRole('switch',{name:'sample.jar 비활성화',exact:true})).toBeDisabled();
});
test('log blocks preserve frame mixins horizontal scroll and discover new crash reports',async({page})=>{
 const f=await fixture(page);const viewport=page.getByTestId('log-viewport');await expect(viewport).toContainText('second');
 await expect(page.locator('.log-element').first().getByText('Create',{exact:true})).toHaveCount(1);
 await expect(page.locator('.log-element').first()).toContainText('18/20 시간 구간');
 const error=page.locator('.log-record').filter({hasText:'java.lang.Error: bad'});await expect(error).toContainText('Caused by:');await expect(error).toContainText('Example.run()');await error.click();
 const detail=page.getByRole('dialog',{name:'로그 블록 상세'});await expect(detail).toContainText('sample.mixins.json');await expect(detail).toContainText('Cause.start()');await detail.getByRole('button',{name:'닫기',exact:true}).click();
 await page.setViewportSize({width:390,height:844});await expect.poll(()=>viewport.evaluate(el=>el.scrollWidth>el.clientWidth+100)).toBe(true);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 f.crash();await expect(page.getByRole('button',{name:'리포트 열기'})).toBeVisible({timeout:8000});await expect(page.getByRole('link',{name:'파일 다운로드'})).toHaveAttribute('href',/crash-reports%2Fcrash-new-server.txt/);
 await page.screenshot({path:'test-results/live-log-mobile.png',fullPage:true});
});
test('parser differentiates second precision and inherits trace time without inventing tick ids',async({page})=>{
 await fixture(page);
 const value=await page.evaluate(async()=>{
  // Vite transforms the same source modules used by the worker.
  const {parseLog}=await import('/src/logParser.ts' as string);const {logRecords,logElements}=await import('/src/logElements.ts' as string);
  const text=['[Info] [23:11:34] [Mod]: a','[Info] [23:11:34] : b','[Error] [23:11:35] [Crash]: broken','\tat net.Error.run(File.java:1)','Caused by: nested'];
  const records=logRecords(text.map((text,seq)=>parseLog({text,seq:seq+1,time:null,kind:'output'})));return {records,elements:logElements(records)};
 });
 expect(value.elements[0].slot).toBeNull();expect(value.elements[0].sources.map((s:any)=>s.source)).toEqual(['Mod','출처 미확인']);expect(value.records.at(-1).rows).toHaveLength(3);expect(value.records.at(-1).time).toBe('23:11:35');
});
