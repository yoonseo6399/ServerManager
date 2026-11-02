import {test,expect,type Page} from '@playwright/test';
import {randomUUID} from 'node:crypto';
async function fixture(page:Page){
 const jobs=new Map<string,any>(),cancelled:string[]=[],chunks:number[]=[];
 const seed=(phase:string,clientId=randomUUID())=>{const s={token:randomUUID(),instanceId:'test',filename:'example.jar',path:'mods',size:2500000,clientId,phase,received:2500000,transferred:1000000,message:phase==='APPLYING'?'파일 반영 중 · 이 단계는 취소할 수 없습니다':'실행기로 파일 전송 중',chunkSize:1048576,cancellable:phase!=='APPLYING',revision:1};jobs.set(s.token,s);return s;};
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname,method=route.request().method();let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(p==='/api/instances')body=[{config:{id:'test',name:'Uploads',loader:'Vanilla',minecraft:'1.21.1',port:25565,memoryMb:512},state:'STOPPED',directory:'/fixture/server',liveFiles:true}];
  else if(p==='/api/runners')body=[];
  else if(p==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(p==='/api/game-addresses')body={enabled:false};
  else if(p.includes('/file-uploads')){
   const tail=p.split('/file-uploads')[1].split('/').filter(Boolean),token=tail[0],s=jobs.get(token);
   if(!token&&method==='GET')body=[...jobs.values()];
   else if(!token){const data=route.request().postDataJSON();body=seed('RECEIVING',data.clientId);Object.assign(body,data,{received:0,transferred:0,message:'파일을 받는 중'});}
   else if(method==='DELETE'){jobs.delete(token);body={};}
   else if(tail[1]==='content'){const bytes=route.request().postDataBuffer()!.length;chunks.push(bytes);s.received+=bytes;s.revision++;body=s;}
   else if(tail[1]==='complete'){Object.assign(s,{phase:'TRANSFERRING',message:'실행기로 파일 전송 중',revision:s.revision+1});body=s;}
   else if(tail[1]==='cancel'){cancelled.push(token);Object.assign(s,{phase:'CANCELLED',message:'업로드 취소됨 · 기존 파일 보존',cancellable:false,revision:s.revision+1});body=s;}
   else body=s;
  }
  else if(p.endsWith('/files'))body=[];
  else if(p.endsWith('/log-files'))body=[];
  else if(p.endsWith('/log-window'))body={lines:[],total:0,first:0,last:0,next:null};
  else if(p.startsWith('/api/log-filters/'))body={revision:0,config:{enabled:false,rules:{keywords:[],mods:[],onlyDebug:false},presets:[],activeId:null}};
  await route.fulfill({json:body});
 });
 return {jobs,seed,cancelled,chunks};
}
test('bounded chunks show runner progress and cancel keeps an explicit cancelled result',async({page})=>{
 const f=await fixture(page);await page.goto('/');await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await page.getByLabel('업로드할 파일',{exact:true}).setInputFiles({name:'mod.jar',mimeType:'application/java-archive',buffer:Buffer.alloc(2500000,1)});
 const card=page.locator('.managed-upload');await expect(card).toContainText('실행기로 파일 전송 중');expect(f.chunks).toEqual([1048576,1048576,402848]);
 await card.getByRole('button',{name:'취소',exact:true}).click();await expect(card).toContainText('취소됨');expect(f.cancelled).toHaveLength(1);
 await page.reload();await expect(page.locator('.managed-upload')).toContainText('취소됨');
});
test('refresh restores accepted background work and completion without resending the file',async({page})=>{
 const f=await fixture(page),s=f.seed('TRANSFERRING');await page.goto('/');await expect(page.locator('.managed-upload')).toContainText('실행기로 파일 전송 중');
 await page.reload();await expect(page.locator('.managed-upload')).toContainText('example.jar');expect(f.cancelled).toEqual([]);expect(f.chunks).toEqual([]);
 Object.assign(s,{phase:'COMPLETE',transferred:s.size,message:'업로드 완료',cancellable:false,revision:s.revision+1});await expect(page.locator('.managed-upload')).toContainText('업로드 완료');
});
test('refresh cancels this tabs unfinished reception and leaves another tabs upload intact',async({page})=>{
 const f=await fixture(page);await page.goto('/');
 await expect.poll(()=>page.evaluate(()=>sessionStorage.getItem('sm.upload-tab'))).toBeTruthy();
 const client=await page.evaluate(()=>sessionStorage.getItem('sm.upload-tab'));
 expect(client).toBeTruthy();const own=f.seed('RECEIVING',client!),other=f.seed('RECEIVING');
 await page.reload();await expect.poll(()=>f.cancelled).toContain(own.token);expect(f.cancelled).not.toContain(other.token);expect(other.phase).toBe('RECEIVING');
});
test('atomic applying stage has no cancel button and progress fits a mobile viewport',async({page})=>{
 await page.setViewportSize({width:390,height:844});const f=await fixture(page);f.seed('APPLYING');await page.goto('/');
 const card=page.locator('.managed-upload');await expect(card).toContainText('취소할 수 없습니다');await expect(card.getByRole('button',{name:'취소',exact:true})).toHaveCount(0);await expect(card).toBeInViewport({ratio:1});
});

test('nested folder jobs restore as one card without individual filenames and dismiss together',async({page})=>{
 const f=await fixture(page),group={id:randomUUID(),name:'kubejs',files:40,bytes:100000000};
 for(let i=0;i<40;i++){const s=f.seed(i===39?'TRANSFERRING':'COMPLETE');Object.assign(s,{filename:`hidden-${i}.js`,path:'kubejs/server_scripts/deep',folderGroup:group,cancellable:i===39});}
 await page.goto('/');const card=page.locator('.folder-upload');await expect(card).toHaveCount(1);await expect(card).toContainText('폴더 업로드 · kubejs');await expect(card).toContainText('39/40개 완료');await expect(page.locator('.managed-upload:not(.folder-upload)')).toHaveCount(0);await expect(card).not.toContainText('hidden-');await expect(card).not.toContainText('server_scripts');
 await page.reload();await expect(card).toHaveCount(1);await expect(card).toContainText('39/40개 완료');
 for(const s of f.jobs.values())Object.assign(s,{phase:'COMPLETE',transferred:s.size,cancellable:false,revision:s.revision+1});
 await expect(card).toContainText('폴더 업로드 완료');await card.getByRole('button',{name:'업로드 알림 닫기'}).click();await expect(card).toHaveCount(0);expect(f.jobs.size).toBe(0);
});

test('dropping a folder creates one progress row and cancellation stops remaining files',async({page})=>{
 const f=await fixture(page);await page.goto('/');await page.getByRole('button',{name:'파일 관리자',exact:true}).click();await expect(page.getByLabel('파일 목록 새로고침')).toBeEnabled();
 await page.locator('.files-panel').evaluate(element=>{
  const children=Array.from({length:12},(_,i)=>({isFile:true,isDirectory:false,name:`script-${i}.js`,file:(done:(f:File)=>void)=>done(new File(['test'],`script-${i}.js`))}));
  let read=false;const entry={isFile:false,isDirectory:true,name:'kubejs',createReader:()=>({readEntries:(done:(items:unknown[])=>void)=>{done(read?[]:children);read=true;}})};
  const transfer=new DataTransfer();transfer.items.add(new File(['stub'],'stub'));Object.defineProperty(transfer,'items',{value:[{webkitGetAsEntry:()=>entry}]});
  element.dispatchEvent(new DragEvent('drop',{bubbles:true,dataTransfer:transfer}));
 });
 const card=page.locator('.folder-upload');await expect(card).toContainText('kubejs');await expect(card).toHaveCount(1);await expect(page.locator('.upload-progress')).toHaveCount(1);await expect(card).not.toContainText('script-');
 const first=[...f.jobs.values()][0];expect(first.folderGroup.files).toBe(12);expect(first.folderGroup.name).toBe('kubejs');
 await card.getByRole('button',{name:'취소',exact:true}).click();await expect(card).toContainText('남은 파일 전송 중단');await page.waitForTimeout(1700);expect(f.jobs.size).toBe(1);expect(f.cancelled).toEqual([first.token]);
});

test('distinct folders remain separate cards with no nested file rows on mobile',async({page})=>{
 const f=await fixture(page);for(const name of ['world','config'])for(let i=0;i<4;i++)Object.assign(f.seed('TRANSFERRING'),{folderGroup:{id:name==='world'?'11111111-1111-4111-8111-111111111111':'22222222-2222-4222-8222-222222222222',name,files:4,bytes:10000000}});
 await page.setViewportSize({width:390,height:844});await page.goto('/');await expect(page.locator('.folder-upload')).toHaveCount(2);await expect(page.locator('.folder-upload').first()).toBeInViewport({ratio:1});await expect(page.locator('.folder-upload').last()).toBeInViewport({ratio:1});
});
