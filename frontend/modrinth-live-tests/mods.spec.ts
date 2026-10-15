import {test,expect} from '@playwright/test';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
test('actual backend persists Modrinth constraints and toggles only selected consumers through the browser',async({page,context})=>{
 const home=process.env.SM_RUNNERS_TEST_HOME||'';expect(home.startsWith('/tmp/servermanager-runners.')).toBe(true);
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
 const session=await (await page.request.get('/api/auth/session')).json();
 async function api(path:string,method='GET',data?:any){const r=await page.request.fetch('/api'+path,{method,data,headers:{'X-CSRF-TOKEN':session.csrf}});expect(r.status(),await r.text()).toBeLessThan(300);return r.headers()['content-type']?.includes('json')?r.json():null;}
 const row=await api('/instances','POST',{name:'Modrinth fixture',loader:'Fabric',minecraft:'1.21.1',loaderVersion:'0.16.14',port:25591,memoryMb:512,javaPath:process.env.SM_RUNNERS_TEST_JAVA});
 const id=row.config.id,root=home+'/profiles/'+id;await mkdir(root+'/server/mods',{recursive:true});
 const sources:any={};
 for(const [name,deps] of [['main',['lib']],['lib',[]],['other',[]]] as [string,string[]][]){
  const bytes=Buffer.from('exact fixture bytes '+name);await writeFile(root+'/server/mods/'+name+'.jar',bytes);
  sources[createHash('sha512').update(bytes).digest('hex')]={projectId:name,slug:name,title:name,versionId:'v-'+name,versionNumber:'1',environment:'client_and_server',clientOnly:false,dependencies:deps.map(projectId=>({projectId,type:'required',versionId:'v-'+projectId}))};
 }
 await writeFile(root+'/mod-sources.json',JSON.stringify({sources,notFound:{},projects:{}}));
 await page.reload();await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
 await expect(page.getByRole('switch',{name:'main.jar 비활성화',exact:true})).toBeVisible();await expect(page.getByRole('switch',{name:'lib.jar 비활성화',exact:true})).toBeDisabled();
 await expect(page.locator('.mod-card').filter({hasText:'lib.jar'}).first()).toContainText('사용 중: main.jar');
 const before=await api('/instances/'+id+'/mods?inventory=true');
 const rejected=await page.request.post('/api/instances/'+id+'/mods/toggle-preview',{headers:{'X-CSRF-TOKEN':session.csrf},data:{revision:before.inventory.revision,files:['lib.jar'],enabled:false}});expect(rejected.ok()).toBe(false);
 await page.getByLabel('main.jar 선택',{exact:true}).check();await page.getByRole('button',{name:/^선택 비활성화/}).click();await page.getByRole('dialog',{name:'모드 변경 확인'}).getByRole('button',{name:'확인하고 변경'}).click();
 await expect(page.getByRole('switch',{name:'main.jar.disabled 활성화',exact:true})).toBeVisible();
 expect((await readFile(root+'/server/mods/main.jar.disabled')).toString()).toBe('exact fixture bytes main');expect((await readFile(root+'/server/mods/lib.jar')).toString()).toBe('exact fixture bytes lib');expect((await readFile(root+'/server/mods/other.jar')).toString()).toBe('exact fixture bytes other');
 await page.getByRole('switch',{name:'main.jar.disabled 활성화',exact:true}).click();await page.getByRole('dialog',{name:'모드 변경 확인'}).getByRole('button',{name:'확인하고 변경'}).click();await expect(page.getByRole('switch',{name:'main.jar 비활성화',exact:true})).toBeVisible();
 const persisted=JSON.parse(await readFile(root+'/mod-sources.json','utf8'));expect(Object.values(persisted.sources).some((s:any)=>s.dependencies.some((d:any)=>d.versionId==='v-lib'))).toBe(true);
 await page.screenshot({path:'test-results/modrinth-actual-backend.png'});
});
