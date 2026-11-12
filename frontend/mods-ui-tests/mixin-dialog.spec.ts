import {test,expect,type Page} from '@playwright/test';

const frame=(n:number)=>`\tat TRANSFORMER/minecraft@1.21.1/example.Stack.call${n}(Stack.java:${n}) {${Array.from({length:150},(_,i)=>`pl:mixin:APP:mod${i%3}.mixins.json:example.VeryLongMixin${i} from mod mod${i%3}`).join(',')}}`;
const initial=[{no:1,kind:'error',text:'[12:00:00] [Server thread/ERROR] [TestMod]: Large stack'},...Array.from({length:120},(_,i)=>({no:i+2,kind:'error',text:frame(i+1)}))];
async function fixture(page:Page,{hold=false}={}){
 let release=()=>{},started=false,reads=0;
 const pending=new Promise<void>(resolve=>{release=resolve;});
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[{config:{id:'test',name:'Mixin fixture',loader:'Vanilla',minecraft:'1.21.1',port:25565,memoryMb:1024,runnerId:'runner'},state:'STOPPED',pid:null}];
  else if(path==='/api/runners')body=[{id:'runner',name:'Runner',online:true,allowedInstanceIds:['test']}];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path.endsWith('/file-uploads')||path.endsWith('/log-files'))body=[];
  else if(path==='/api/log-filters/test')body={revision:0,config:{enabled:false,rules:{keywords:[],mods:[],onlyDebug:false},presets:[],activeId:null}};
  else if(path.endsWith('/log-window')){
   const from=Number(url.searchParams.get('from')),limit=Number(url.searchParams.get('limit'));
   if(limit===500){started=true;reads++;if(hold)await pending;}
   const data=limit===500?[...initial,...Array.from({length:800},(_,i)=>({no:initial.length+i+1,kind:'error',text:frame(i+121)}))]:initial;
   const lines=data.slice(from-1,from-1+limit),last=lines.at(-1)?.no||0;
   body={lines,total:data.length,first:lines[0]?.no||0,last,next:last&&last<data.length?last+1:null};
  }
  await route.fulfill({json:body}).catch(()=>{});
 });
 return {release,started:()=>started,reads:()=>reads};
}
async function open(page:Page,raw=false){
 await page.goto('/');
 if(raw)await page.getByRole('button',{name:'원문 줄 보기',exact:true}).click();
 if(raw){await page.locator('[data-event-seq="2"]').scrollIntoViewIfNeeded();await page.locator('[data-event-seq="2"]').click();}
 else {await page.locator('.log-record').first().scrollIntoViewIfNeeded();await page.locator('.log-record').first().click();}
 return page.getByRole('dialog',{name:raw?'로그 상세':'로그 블록 상세',exact:true});
}
for(const raw of [false,true])for(const mobile of [false,true])test(`large ${raw?'frame':'block'} ${mobile?'mobile':'desktop'} report always has an on-screen close button`,async({page})=>{
 if(mobile)await page.setViewportSize({width:390,height:500});else await page.setViewportSize({width:1100,height:600});
 await fixture(page);const dialog=await open(page,raw),close=dialog.getByRole('button',{name:'닫기',exact:true});
 await expect(close).toBeInViewport({ratio:1});
 expect(await dialog.locator('.mixin-groups li').count()).toBeLessThanOrEqual(raw?100:1000);
 await dialog.getByText('원문 보기',{exact:true}).click();
 await expect(dialog.locator('pre')).toBeVisible();
 expect(await dialog.locator('pre').innerText()).toHaveLength(Math.min(32768,raw?frame(1).length:initial.map(x=>x.text).join('\n').length));
 if(!raw){
  await dialog.getByRole('button',{name:'원문 다음',exact:true}).click();
  await expect(dialog.locator('pre')).toHaveText(initial.map(x=>x.text).join('\n').slice(32768,65536));
  await dialog.getByRole('button',{name:'프레임 다음',exact:true}).click();
  await expect(dialog.locator('summary').filter({hasText:'#12 Stack.call11()'})).toHaveCount(1);
 }else{
  await dialog.getByRole('button',{name:'Mixin 다음',exact:true}).click();
  await expect(dialog.locator('.mixin-groups li')).toHaveCount(50);
 }
 await dialog.locator('.log-detail-body').evaluate(e=>{e.scrollTop=e.scrollHeight;});
 await expect(close).toBeInViewport({ratio:1});
 await close.click({timeout:1500});await expect(dialog).toHaveCount(0);
 // Focus/history cleanup also permits reopening, then immediate Escape dismissal.
 await open(page,raw);await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);
});

for(const method of ['button','escape','back','backdrop'])test(`closing with ${method} cancels an in-flight report and never reopens it`,async({page})=>{
 const f=await fixture(page,{hold:true}),dialog=await open(page);
 await dialog.getByRole('button',{name:/주변 원문과 스택 더 읽기/}).click();
 await expect.poll(f.started).toBe(true);await expect(dialog.getByRole('button',{name:'이어 읽는 중…',exact:true})).toBeDisabled();
 if(method==='button')await dialog.getByRole('button',{name:'닫기',exact:true}).click({timeout:1500});
 else if(method==='escape')await page.keyboard.press('Escape');
 else if(method==='back')await page.goBack();
 else await page.locator('.log-detail-backdrop').click({position:{x:2,y:2}});
 await expect(dialog).toHaveCount(0);f.release();
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 await expect(dialog).toHaveCount(0);expect(f.reads()).toBe(1);
 // A fresh dialog must not inherit the old spinner/disabled state.
 await page.locator('.log-record').first().click();
 await expect(page.getByRole('dialog').getByRole('button',{name:/주변 원문과 스택 더 읽기/})).toBeEnabled();
});

test('closing while the worker parses ignores its late result and stops further pages',async({page})=>{
 await page.addInitScript(()=>{
  const w=window as any,Native=window.Worker;
  w.detailParseStarted=false;w.detailParseFinished=false;
  window.Worker=class extends Native{
   constructor(url:URL|string,options?:WorkerOptions){super(url,options);this.addEventListener('message',e=>{if(e.data.request===w.detailRequest&&e.data.events)w.detailParseFinished=true;});}
   postMessage(message:any,options?:any){
    if(message.events?.length===500){w.detailRequest=message.request;w.detailParseStarted=true;w.releaseDetailParse=()=>super.postMessage(message,options);}
    else super.postMessage(message,options);
   }
  };
 });
 const f=await fixture(page),dialog=await open(page);
 await dialog.getByRole('button',{name:/주변 원문과 스택 더 읽기/}).click();
 await page.waitForFunction(()=>(window as any).detailParseStarted);
 await dialog.getByRole('button',{name:'닫기',exact:true}).click({timeout:1500});
 await page.evaluate(()=>(window as any).releaseDetailParse());
 await page.waitForFunction(()=>(window as any).detailParseFinished);
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 await expect(dialog).toHaveCount(0);expect(f.reads()).toBe(1);
});
