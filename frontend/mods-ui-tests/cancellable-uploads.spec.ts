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
