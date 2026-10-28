import {test,expect,type Page} from '@playwright/test';
async function fixture(page:Page,viewer=false,failFirstUpload=false){
 const uploads:string[]=[],deletes:string[]=[],updates:any[]=[],exports:any[]=[];let pack:any=null;let downloadFinished=false;
 let mods=['first.jar','second.jar'];
 const plan={pack:{name:'New Pack',version:'2',minecraft:'1.21.1',loader:'Fabric',loaderVersion:'0.16.14'},currentMods:['mods/first.jar','mods/second.jar'],incomingMods:['mods/first.jar','mods/new.jar','mods/client.jar'],configFiles:['config/test.json'],excludedClientMods:['mods/client.jar'],ignoredFiles:['world/level.dat'],warnings:[]};
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname,method=route.request().method();let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'user',name:'User',owner:!viewer,permissions:{test:viewer?'VIEWER':'ADMIN'}},csrf:'fixture',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances')body=[{config:{id:'test',name:'서버',loader:'Fabric',minecraft:'1.21.1',loaderVersion:'0.16.14',port:25565,memoryMb:1024,javaPath:'java',runnerId:'runner',fileSyncEnabled:false},state:'STOPPED',pid:null,liveFiles:true,directory:'runner/server'}];
  else if(p==='/api/memory')body={totalMb:24000,freeMb:14000,reservedMb:2000,maxHeapMb:22000,startableHeapMb:22000};
  else if(p==='/api/game-addresses')body={enabled:false};
  else if(p.endsWith('/mods'))body={remote:true,editable:true,inventoryRevision:mods.join(),inventory:url.searchParams.has('inventory')?{revision:'r',mods:mods.map(filename=>({filename,enabled:true,size:10,sha256:'a'.repeat(64),source:null,warning:null})),warnings:[],dependencyFiles:[],dependencyReasons:{}}:null,job:null,diagnostic:null,next:null,active:false};
  else if(p.endsWith('/upload')){uploads.push(url.search);if(failFirstUpload&&uploads.length===1)return route.fulfill({status:503,json:{message:'fixture upload failure'}});await new Promise(resolve=>setTimeout(resolve,150));}
  else if(p.endsWith('/files')&&method==='DELETE'){deletes.push(url.searchParams.get('path')!);mods=mods.filter(name=>'mods/'+name!==url.searchParams.get('path'));}
  else if(p.endsWith('/files'))body=[{name:'world',path:'world',directory:true,size:0}];
  else if(p.endsWith('/pack-update/preview')){pack={token:'fixture',phase:'READY',message:'업데이트 방법을 선택하세요',plan,completed:0,total:0};body=pack;}
  else if(p.endsWith('/pack-update')&&method==='POST'){updates.push(route.request().postDataJSON());pack={...pack,phase:'COMPLETE',message:'업데이트 완료',completed:1,total:1};body=pack;}
  else if(p.endsWith('/pack-update'))body=pack;
  else if(p.endsWith('/folder-downloads')&&method==='POST'){exports.push(route.request().postDataJSON());downloadFinished=false;body={token:'zip',phase:'WORKING',message:'압축',completed:0,files:1,bytes:0,totalBytes:100};}
  else if(p.endsWith('/folder-downloads/zip'))body={token:'zip',phase:'READY',message:'준비 완료',completed:1,files:1,bytes:100,totalBytes:100,copiedAt:'2026-10-29T01:02:03Z',zipBytes:100,downloadedBytes:downloadFinished?100:0,downloadState:downloadFinished?'COMPLETE':'WAITING'};
  else if(p.endsWith('/folder-downloads/zip/download')){downloadFinished=true;return route.fulfill({headers:{'content-type':'application/zip','content-disposition':'attachment; filename=world.zip'},body:Buffer.from('zip-fixture')});}
  else if(p.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(p.endsWith('/log-files'))body=[];
  else if(p.endsWith('/log-display'))body={revision:0,enabled:true,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
 return {uploads,deletes,updates,exports};
}
async function openMods(page:Page){await page.goto('/');await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();await expect(page.getByRole('button',{name:'first.jar 삭제',exact:true})).toBeVisible();}

test('mod drag drop uses mods upload endpoint and delete confirms once except Shift',async({page})=>{
 const f=await fixture(page);await openMods(page);
 await page.locator('.mods-panel').evaluate(node=>{const data=new DataTransfer();data.items.add(new File(['jar1'],'a.jar'));data.items.add(new File(['jar2'],'b.jar'));node.dispatchEvent(new DragEvent('drop',{bubbles:true,dataTransfer:data}));});
 await expect.poll(()=>f.uploads.length).toBe(2);await expect(page.locator('.upload-progress')).toContainText('완료 2/2');expect(f.uploads).toEqual(['?path=mods','?path=mods']);
 let confirmations=0;page.on('dialog',async dialog=>{confirmations++;await dialog.accept();});
 await page.getByRole('button',{name:'first.jar 삭제',exact:true}).click();await expect.poll(()=>f.deletes.length).toBe(1);
 await page.getByRole('button',{name:'second.jar 삭제',exact:true}).click({modifiers:['Shift']});await expect.poll(()=>f.deletes.length).toBe(2);expect(confirmations).toBe(1);
});
test('pack update dialog previews all changes and sends explicit merge replace config and client choices',async({page})=>{
 const f=await fixture(page);await openMods(page);await page.getByRole('button',{name:'mrpack으로 서버 업데이트'}).click();
 const modal=page.getByRole('dialog',{name:'기존 서버 mrpack 업데이트'});await expect(modal).toBeVisible();
 await modal.getByLabel('업데이트 mrpack 파일').setInputFiles({name:'update.mrpack',mimeType:'application/zip',buffer:Buffer.from('fixture')});
 await expect(modal.getByLabel('모드 업데이트 방식')).toBeVisible();await expect(modal).toContainText('월드·플레이어 데이터·server.properties는 보존');
 await modal.getByLabel('모드 업데이트 방식').selectOption('replace');await modal.getByLabel('config 파일 추가·덮어쓰기',{exact:false}).check();await modal.getByLabel('클라이언트 전용 모드 제외',{exact:false}).uncheck();
 await expect(modal).toContainText('삭제 모드 · 1개');await expect(modal).toContainText('mods/second.jar');expect(f.updates).toEqual([]);
 await modal.evaluate(node=>node.scrollTop=0);await modal.screenshot({path:'frontend/test-results/pack-update-desktop.png'});
 await page.setViewportSize({width:390,height:844});await modal.evaluate(node=>node.scrollTop=0);await expect(modal).toBeInViewport({ratio:1});await modal.screenshot({path:'frontend/test-results/pack-update-mobile.png'});
 await modal.getByRole('button',{name:'이 방법으로 업데이트'}).click();await expect.poll(()=>f.updates.length).toBe(1);
 expect(f.updates[0]).toEqual({token:'fixture',mode:'replace',overwriteConfig:true,excludeClientOnly:false});
});
test('file tab uploads show floating progress and viewer may download current or latest central folder copy',async({page})=>{
 const f=await fixture(page,true);await page.goto('/');await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await expect(page.getByRole('button',{name:'업로드',exact:true})).toBeDisabled();
 const snapshotDownload=page.waitForEvent('download');await page.getByRole('button',{name:'world 최근 복사본 ZIP 다운로드',exact:true}).click();await expect.poll(()=>f.exports.length).toBe(1);expect(f.exports[0]).toEqual({path:'world',source:'snapshot'});expect(await (await snapshotDownload).failure()).toBeNull();
 await expect(page.locator('.upload-progress')).toContainText('중앙 복사본');await expect(page.locator('.upload-progress')).toContainText('ZIP 전송 완료');
 const currentDownload=page.waitForEvent('download');await page.getByRole('button',{name:'world ZIP 다운로드',exact:true}).click();await expect.poll(()=>f.exports.length).toBe(2);expect(f.exports[1]).toEqual({path:'world',source:'current'});expect(await (await currentDownload).failure()).toBeNull();
 await page.setViewportSize({width:390,height:844});await expect(page.locator('.upload-progress-stack')).toBeInViewport({ratio:1});await page.screenshot({path:'frontend/test-results/file-export-mobile.png'});
});
test('file tab keeps drag drop uploads and displays byte progress in the floating bar',async({page})=>{
 const f=await fixture(page);await page.goto('/');await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await page.getByLabel('업로드할 파일',{exact:true}).setInputFiles({name:'notes.txt',mimeType:'text/plain',buffer:Buffer.from('original content')});
 await expect.poll(()=>f.uploads.length).toBe(1);await expect(page.locator('.upload-progress')).toContainText('완료 1/1');expect(f.uploads[0]).toBe('?path=');
});

test('one failed mod upload shows failure and continues uploading remaining files',async({page})=>{
 const f=await fixture(page,false,true);await openMods(page);
 await page.getByLabel('업로드할 모드 파일').setInputFiles([{name:'a.jar',mimeType:'application/java-archive',buffer:Buffer.from('one')},{name:'b.jar',mimeType:'application/java-archive',buffer:Buffer.from('two')}]);
 await expect.poll(()=>f.uploads.length).toBe(2);await expect(page.locator('.upload-progress.failed')).toContainText('완료 1/2 · 실패 1');await expect(page.getByRole('alert')).toContainText('fixture upload failure');
});
