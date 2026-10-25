import {test,expect,type Page} from '@playwright/test';
async function fixture(page:Page,{unknown=false,failChunk=false,member=false}={}){
 let received=0,failed=false,cancelled=false;const requests:{offset:number;bytes:number;type:string}[]=[],imports:any[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname,m=route.request().method();let body:any={};
  if(p==='/api/auth/session')body={appVersion:'test',user:{id:'owner',name:'Owner',owner:!member,permissions:{}},csrf:'csrf-test',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/memory')body={totalMb:24576,freeMb:15000,reservedMb:2000,maxHeapMb:22576,startableHeapMb:22576};
  else if(p==='/api/health')body={javaPath:'/test/java'};
  else if(p==='/api/server-archives/uploads'&&m==='POST'){received=0;body={token:'fixture',size:100,received,chunkSize:32};}
  else if(p==='/api/server-archives/uploads/fixture'&&m==='PUT'){
   const bytes=route.request().postDataBuffer()?.length||0;requests.push({offset:+url.searchParams.get('offset')!,bytes,type:route.request().headers()['content-type']});
   if(failChunk&&!failed&&received===32){failed=true;await route.fulfill({status:503,json:{message:'연결이 잠시 끊겼습니다'}});return;}
   expect(+url.searchParams.get('offset')!).toBe(received);received+=bytes;body={token:'fixture',size:100,received,chunkSize:32};
  }
  else if(p==='/api/server-archives/uploads/fixture'&&m==='GET')body={token:'fixture',size:100,received,chunkSize:32};
  else if(p==='/api/server-archives/uploads/fixture'&&m==='DELETE'){cancelled=true;}
  else if(p==='/api/server-archives/uploads/fixture/preview')body={token:'fixture',plan:{name:'Survival',root:'Survival/',files:400,bytes:1024*1024,loader:unknown?null:'NeoForge',minecraft:unknown?null:'1.21.1',loaderVersion:unknown?null:'21.1.264',entryJar:unknown?'custom.jar':null,requiredJava:null,jars:['custom.jar'],warnings:['월드·모드·설정을 새 서버로 복사합니다. 서버는 자동 실행하지 않습니다.','인식하지 못한 서버 종류와 버전은 직접 지정하세요.']}};
  else if(p==='/api/server-archives/import'){imports.push(route.request().postDataJSON());body={config:{id:'new-server'}};}
  await route.fulfill({json:body});
 });
 return {requests,imports,cancelled:()=>cancelled};
}
async function open(page:Page){await page.goto('/');await page.getByRole('button',{name:'서버 ZIP 가져오기',exact:true}).click();await expect(page.getByRole('dialog',{name:'서버 ZIP 가져오기',exact:true})).toBeVisible();}
async function upload(page:Page){await page.getByLabel('서버 ZIP 파일',{exact:true}).setInputFiles({name:'server.zip',mimeType:'application/zip',buffer:Buffer.alloc(100,7)});}

test('ZIP upload uses binary chunks shows server root and imports without automatically running it',async({page})=>{
 const f=await fixture(page);await open(page);await upload(page);await expect(page.getByText('미리보기 완료',{exact:false})).toBeVisible();
 expect(f.requests.map(r=>r.bytes)).toEqual([32,32,32,4]);expect(f.requests.map(r=>r.offset)).toEqual([0,32,64,96]);expect(f.requests.every(r=>r.type==='application/octet-stream')).toBe(true);
 await expect(page.getByText('서버 폴더: Survival/',{exact:false})).toBeVisible();await expect(page.getByLabel('서버 종류',{exact:true})).toHaveValue('NeoForge');
 await page.getByRole('dialog').screenshot({path:'test-results/settings/server-archive-preview.png'});
 await page.getByRole('button',{name:'새 서버로 가져오기',exact:true}).click();await expect.poll(()=>f.imports.length).toBe(1);
 expect(f.imports[0]).toMatchObject({token:'fixture',name:'Survival',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.264',requiredJava:21,entryJar:null});await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('a failed chunk can resume at the confirmed offset and closing removes the staged ZIP',async({page})=>{
 const f=await fixture(page,{failChunk:true});await open(page);await upload(page);await expect(page.getByRole('alert')).toContainText('연결이 잠시');
 await page.getByRole('button',{name:'업로드 다시 시도 / 이어서 올리기',exact:true}).click();await expect(page.getByText('미리보기 완료',{exact:false})).toBeVisible();expect(f.requests.map(r=>r.offset)).toEqual([0,32,32,64,96]);
 await page.getByRole('button',{name:'닫기',exact:true}).click();await expect.poll(f.cancelled).toBe(true);await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('unknown server information requires explicit selection and mobile fields remain usable',async({page})=>{
 await page.setViewportSize({width:390,height:844});const f=await fixture(page,{unknown:true});await open(page);await upload(page);
 const submit=page.getByRole('button',{name:'새 서버로 가져오기',exact:true});await expect(submit).toBeDisabled();
 await page.getByLabel('서버 종류',{exact:true}).selectOption('Vanilla');await page.getByLabel('Minecraft 버전',{exact:true}).fill('1.21.1');await page.getByLabel('실행할 서버 JAR',{exact:true}).selectOption('custom.jar');await expect(submit).toBeEnabled();await submit.scrollIntoViewIfNeeded();await page.getByRole('dialog').screenshot({path:'test-results/settings/server-archive-mobile.png'});await submit.click();await expect.poll(()=>f.imports.length).toBe(1);expect(f.imports[0]).toMatchObject({loader:'Vanilla',loaderVersion:'1.21.1',entryJar:'custom.jar'});
});
test('unsupported archive types are rejected and normal members do not see creation controls',async({page})=>{
 await fixture(page);await open(page);await page.getByLabel('서버 ZIP 파일',{exact:true}).setInputFiles({name:'server.7z',mimeType:'application/octet-stream',buffer:Buffer.from('bad')});await expect(page.getByRole('alert')).toContainText('ZIP 파일을 선택');await page.getByRole('button',{name:'닫기',exact:true}).click();
 await page.unrouteAll();await fixture(page,{member:true});await page.reload();await expect(page.getByRole('button',{name:'서버 ZIP 가져오기',exact:true})).toHaveCount(0);
});
