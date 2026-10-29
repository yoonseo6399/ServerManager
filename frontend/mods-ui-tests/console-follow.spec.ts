import {test,expect,type Page} from '@playwright/test';

async function fixture(page:Page){
 let total=600,reads=0;
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'Scroll fixture',loader:'Vanilla',minecraft:'1.21.1',port:25565,memoryMb:1024,runnerId:'runner'},state:'RUNNING',pid:123}];
  else if(path==='/api/runners')body=[{id:'runner',name:'Runner',online:true,allowedInstanceIds:['test']}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/file-uploads'))body=[];
  else if(path.endsWith('/log-files'))body=[];
  else if(path==='/api/log-filters/test')body={revision:0,config:{enabled:false,rules:{keywords:[],mods:[],onlyDebug:false},presets:[],activeId:null}};
  else if(path.endsWith('/log-window')){
   reads++;
   const from=Number(url.searchParams.get('from')),limit=Number(url.searchParams.get('limit'));
   const lines=Array.from({length:Math.min(limit,Math.max(0,total-from+1))},(_,n)=>({no:from+n,kind:'output',text:`[12:00:00] [Server thread/INFO] [TestMod]: line-${from+n}`}));
   const last=lines.at(-1)?.no||0;
   body={lines,total,first:lines[0]?.no||0,last,next:last&&last<total?last+1:null};
  }
  await route.fulfill({json:body});
 });
 return {append:(n:number)=>{total+=n;},reads:()=>reads};
}
async function position(page:Page){return page.getByTestId('log-viewport').evaluate(v=>({top:v.scrollTop,bottom:v.scrollHeight-v.clientHeight-v.scrollTop}));}
async function scroll(page:Page,where:'up'|'bottom'){
 await page.getByTestId('log-viewport').evaluate((v,where)=>{v.scrollTop=where==='up'?v.scrollTop-24:v.scrollHeight;},where);
}
for(const raw of [false,true])for(const mobile of [false,true])test(`${raw?'raw':'grouped'} ${mobile?'mobile':'desktop'} pauses on small upward scroll and resumes at bottom`,async({page})=>{
 if(mobile)await page.setViewportSize({width:390,height:844});
 const f=await fixture(page);await page.goto('/');
 if(raw)await page.getByRole('button',{name:'원문 줄 보기',exact:true}).click();
 await page.getByTestId('log-viewport').scrollIntoViewIfNeeded();
 await expect.poll(()=>page.getByTestId('log-viewport').evaluate(v=>v.scrollHeight-v.clientHeight)).toBeGreaterThan(1000);
 await expect(page.locator('[data-event-seq="600"]')).toBeVisible();
 await expect.poll(async()=>(await position(page)).bottom).toBeLessThanOrEqual(2);
 await scroll(page,'up');
 if(raw)await expect(page.getByLabel('자동 스크롤',{exact:true})).not.toBeChecked();
 else await expect(page.getByText('자동 스크롤 일시 정지',{exact:true})).toBeVisible();
 const before=await position(page),reads=f.reads();
 f.append(350);
 await expect(page.getByTestId('log-count')).toContainText('950줄');
 await expect.poll(f.reads).toBeGreaterThan(reads+2);
 expect(Math.abs((await position(page)).top-before.top)).toBeLessThanOrEqual(2);
 if(!raw)await expect(page.locator('[data-event-seq="301"]')).toHaveCount(1);
 // Returning to the bottom restores live tail, including a burst larger than its 300-line window.
 await scroll(page,'bottom');
 await expect(page.locator('[data-event-seq="950"]')).toBeVisible();
 await expect.poll(async()=>(await position(page)).bottom).toBeLessThanOrEqual(2);
 f.append(1);await expect(page.locator('[data-event-seq="951"]')).toBeVisible();
 await scroll(page,'up');
 f.append(10);
 await page.getByRole('button',{name:'아래로 이동',exact:true}).click();
 await expect(page.locator('[data-event-seq="961"]')).toBeVisible();
 await expect.poll(async()=>(await position(page)).bottom).toBeLessThanOrEqual(2);
});

test('historical grouped page stays fixed until the latest button is used',async({page})=>{
 const f=await fixture(page);await page.goto('/');
 await expect(page.locator('[data-event-seq="600"]')).toBeVisible();
 await page.getByRole('button',{name:'이전 구간',exact:true}).click();
 await expect(page.getByTestId('log-count')).toContainText('#1–300');
 await scroll(page,'bottom');f.append(400);
 await expect(page.getByText('자동 스크롤 일시 정지',{exact:true})).toBeVisible();
 await expect(page.locator('[data-event-seq="1"]')).toHaveCount(1);
 await page.getByRole('button',{name:'아래로 이동',exact:true}).click();
 await expect(page.locator('[data-event-seq="1000"]')).toBeVisible();
});
