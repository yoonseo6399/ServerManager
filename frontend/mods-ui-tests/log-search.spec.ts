import {test,expect,type Page} from '@playwright/test';
async function fixture(page:Page){
 const starts:any[]=[],cancelled:string[]=[],reads:string[]=[];let n=0;
 await page.route('**/api/**',async route=>{
  const request=route.request(),url=new URL(request.url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'Search fixture',loader:'Vanilla',minecraft:'1.21.1',port:25565,memoryMb:1024,runnerId:'runner'},state:'RUNNING',pid:123}];
  else if(path==='/api/runners')body=[{id:'runner',name:'Runner',online:true,allowedInstanceIds:['test']}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,startableHeapMb:14400};
  else if(path.endsWith('/file-uploads')||path.endsWith('/log-files'))body=[];
  else if(path==='/api/log-filters/test')body={revision:0,config:{enabled:true,rules:{keywords:['discard'],mods:['Hidden'],onlyDebug:false},presets:[],activeId:null}};
  else if(path.endsWith('/log-window')){reads.push(url.searchParams.get('path')||'');body={lines:[{no:1,kind:'output',text:'[12:00:00] [Server thread/INFO] [Visible]: current session'}],total:1,first:1,last:1,next:null};}
  else if(path.endsWith('/log-search')&&request.method()==='POST'){const value=request.postDataJSON();starts.push(value);body={id:'job'+(++n),state:'QUEUED',scanned:0,bytes:0,totalBytes:100,matches:0,retained:0,offset:0,next:null,rows:[]};}
  else if(path.includes('/log-search/')){
   const job=path.split('/').at(-1)!;
   if(request.method()==='DELETE'){cancelled.push(job);body={status:'CANCELLED'};}
   else{const offset=Number(url.searchParams.get('offset')||0);body={id:job,state:'COMPLETE',scanned:1200000,bytes:100,totalBytes:100,matches:101,retained:101,offset,next:offset===0?100:null,rows:[{no:offset===0?1199999:1200000,kind:'output',text:offset===0?'needle from unopened far-away page':'second search page',level:'INFO',logger:'Visible'}]};}
  }
  await route.fulfill({json:body});
 });return {starts,cancelled,reads};
}
for(const raw of [false,true])test(`${raw?'raw':'grouped'} searches the complete selected file with filters and pages results without scrolling`,async({page})=>{
 const f=await fixture(page);await page.goto('/');if(raw)await page.getByRole('button',{name:'원문 줄 보기',exact:true}).click();
 await page.getByLabel(raw?'로그 검색':'블록 검색',{exact:true}).fill('needle');
 await expect(page.getByTestId('log-search-results')).toContainText('unopened far-away page');
 await expect(page.getByTestId('log-search-progress')).toContainText('1,200,000줄');
 expect(f.starts[0].path).toBe('live.log');expect(f.starts[0].filters.rules.mods).toEqual(['Hidden']);expect(f.starts[0].filters.rules.keywords).toEqual(['discard']);
 await page.getByRole('button',{name:'다음 검색 결과',exact:true}).click();await expect(page.getByTestId('log-search-results')).toContainText('second search page');
 await page.getByLabel(raw?'로그 검색':'블록 검색',{exact:true}).fill('next');await expect.poll(()=>f.starts.length).toBe(2);await expect.poll(()=>f.cancelled.length).toBeGreaterThan(0);
 expect(f.reads.every(p=>p==='live.log')).toBeTruthy();
});
