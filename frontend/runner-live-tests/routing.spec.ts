import {test,expect} from '@playwright/test';
import {readFile,writeFile,access,open,cp,mkdir,rename,rm,readdir,realpath,stat} from 'node:fs/promises';
import {spawn,execFile, type ChildProcess} from 'node:child_process';
import net from 'node:net';
import {promisify} from 'node:util';
import {createHash,createPrivateKey,sign,randomBytes} from 'node:crypto';
import tls from 'node:tls';

function varint(n:number){const bytes:number[]=[];do{let b=n&127;n>>>=7;if(n)b|=128;bytes.push(b);}while(n);return Buffer.from(bytes);}
async function freePort(){const socket=net.createServer();await new Promise<void>(r=>socket.listen(0,'127.0.0.1',r));const port=(socket.address() as net.AddressInfo).port;await new Promise<void>(r=>socket.close(()=>r()));return port;}
async function query(port:number,host:string){return new Promise<any>((resolve,reject)=>{
 const socket=net.connect(port,'127.0.0.1');let bytes=Buffer.alloc(0);let done=false;
 const finish=(error?:Error,value?:any)=>{if(done)return;done=true;socket.destroy();error?reject(error):resolve(value);};
 socket.setTimeout(10000,()=>finish(Error('Status query timeout: '+host)));socket.on('error',e=>finish(Error(`Status ${host}:${port}: ${e.message}`)));socket.on('end',()=>{if(!done)finish(Error('Route rejected: '+host));});
 socket.on('connect',()=>{const h=Buffer.from(host);const p=Buffer.concat([varint(0),varint(767),varint(h.length),h,Buffer.from([port>>8,port&255]),varint(1)]);socket.write(Buffer.concat([varint(p.length),p,Buffer.from([1,0])]));});
 socket.on('data',chunk=>{bytes=Buffer.concat([bytes,chunk]);let offset=0;function read(){let n=0;for(let i=0;i<5;i++){if(offset>=bytes.length)throw Error('partial');const b=bytes[offset++];n|=(b&127)<<(7*i);if(!(b&128))return n;}throw Error('bad varint');}try{const length=read();if(bytes.length-offset<length)return;expect(read()).toBe(0);const size=read();finish(undefined,JSON.parse(bytes.subarray(offset,offset+size).toString()));}catch(e){if((e as Error).message!=='partial')finish(e as Error);}});
 });}
async function rotateFixtureRunnerSession(home:string){
 const device=JSON.parse(await readFile(home+'/identity.json','utf8'));const time=Date.now(),nonce=randomBytes(24).toString('base64url');
 const key=createPrivateKey({key:Buffer.from(device.privateKey,'base64url'),format:'der',type:'pkcs8'});
 const signature=sign(null,Buffer.from(`sm-runner-v1\n${device.id}\n${time}\n${nonce}\n`),key).toString('base64url');
 await new Promise<void>((resolve,reject)=>{let result='';const socket=tls.connect({host:'127.0.0.1',servername:'localhost',port:8796,rejectUnauthorized:false},()=>{
  socket.write(`GET /api/runners/connect HTTP/1.1\r\nHost: localhost:8796\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ${randomBytes(16).toString('base64')}\r\nX-SM-Runner-Id: ${device.id}\r\nX-SM-Timestamp: ${time}\r\nX-SM-Nonce: ${nonce}\r\nX-SM-Signature: ${signature}\r\n\r\n`);
 });socket.setTimeout(10000,()=>{socket.destroy();reject(Error('fixture replacement WSS timeout'));});socket.on('error',reject);socket.on('data',chunk=>{result+=chunk.toString();if(result.includes('\r\n\r\n')){const ok=result.startsWith('HTTP/1.1 101');socket.destroy();ok?resolve():reject(Error('fixture replacement WSS was rejected'));}});});
}
test('real HTTPS manager, two signed runner sessions, shared TCP routing, safe failed start and stop handoff',async({page,context,browser})=>{
 const home=process.env.SM_RUNNERS_TEST_HOME||'';expect(home.startsWith('/tmp/servermanager-runners.')).toBe(true);
 const java=process.env.SM_RUNNERS_TEST_JAVA!,jar=process.env.SM_RUNNERS_TEST_JAR!;
 const legacy=process.env.SM_RUNNERS_TEST_LEGACY_JAR;
 if(legacy)expect(/\/dist\/server-manager-(0\.4\.0-beta\.8|0\.4\.8)\.jar$/.test(legacy)).toBe(true);
 const cdp=await context.newCDPSession(page);await cdp.send('WebAuthn.enable');await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
 await page.goto('/');await page.getByLabel('등록 코드').fill((await readFile(home+'/profiles/security/setup-code.txt','utf8')).trim());await page.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(page.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
 const session=await (await page.request.get('/api/auth/session')).json();
 async function api(path:string,method='GET',data?:any){const r=await page.request.fetch('/api'+path,{method,data,headers:{'X-CSRF-TOKEN':session.csrf}});expect(r.status(),`${method} ${path}: ${await r.text()}`).toBeLessThan(300);return r.headers()['content-type']?.includes('json')?r.json():null;}
 const shared=await freePort(),ids:string[]=[],runners:ChildProcess[]=[],runnerHomes:string[]=[],devices:string[]=[];
 const originalFolder=home+'/original Minecraft server';
 const trust=['-Djavax.net.ssl.trustStore='+home+'/tls.p12','-Djavax.net.ssl.trustStorePassword=fixture-only-password'];
 async function state(id:string){return (await api('/instances')).find((i:any)=>i.config.id===id)?.state;}
 async function waitState(id:string,wanted:string){await expect.poll(()=>state(id),{timeout:180000,intervals:[1000,2000]}).toBe(wanted);}
 try {
  await api('/admin/game-network','PUT',{revision:0,config:{enabled:true,port:shared,baseDomain:'server.example.com',targetHost:'gateway.example.com',defaultInstanceId:null,defaultHostname:'',labels:{},cloudflareEnabled:false,zoneId:''}});
  for(const [index,loader] of ['Vanilla','Fabric'].entries()){
   const seed=loader==='Fabric'?process.env.SM_RUNNERS_TEST_FABRIC_SEED:undefined;
   if(seed)expect(seed.startsWith('/tmp/servermanager-runners.')).toBe(true);
   const version=loader==='Vanilla'?'1.21.1':seed?JSON.parse(await readFile(seed+'/server-manager-runtime.json','utf8')).loaderVersion:(await api('/version-catalog?loader=Fabric&minecraft=1.21.1')).versions[0];
   const row=await api('/instances','POST',{name:'Fixture '+loader,loader,minecraft:'1.21.1',loaderVersion:version,port:await freePort(),memoryMb:1024,javaPath:java});const id=row.config.id;ids.push(id);
   if(seed){
    // Optional previously verified, disposable runtime fixture; the feature test does not depend on CDN availability.
    await cp(seed,home+'/profiles/'+id+'/server',{recursive:true});
    for(const name of ['world','world_nether','world_the_end','logs','crash-reports','mods'])await rm(home+'/profiles/'+id+'/server/'+name,{recursive:true,force:true});
    await writeFile(home+'/profiles/'+id+'/installed.ok',version);
   }else{await api('/instances/'+id+'/install','POST');await waitState(id,'STOPPED');}
   const content=`server-ip=127.0.0.1\nserver-port=${row.config.port}\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nonline-mode=true\nmotd=Fixture-${index}\n`;
   await api('/instances/'+id+'/text','PUT',{path:'server.properties',content});
   await api('/instances/'+id+'/game-domain','PUT',{label:index?'beta':'alpha'});
   const runnerHome=home+'/runner-'+index;runnerHomes.push(runnerHome);const env={...process.env,SERVER_MANAGER_RUNNER_HOME:runnerHome};
   if(index===0) {
    await cp(home+'/profiles/'+id+'/server',originalFolder,{recursive:true});
    await rename(originalFolder+'/server.jar',originalFolder+'/custom-vanilla.jar');
    await rm(originalFolder+'/server-manager-runtime.json');
    await writeFile(originalFolder+'/original-folder-proof.txt','must remain in the original folder\n');
    await writeFile(originalFolder+'/resume-proof.bin',Buffer.alloc(64*1024*1024,37));
    await mkdir(originalFolder+'/resume-many');
    for(let n=0;n<512;n++)await writeFile(originalFolder+`/resume-many/${n}.dat`,Buffer.alloc(65536,n%251));
    await mkdir(runnerHome+'/instances/'+id,{recursive:true});
    await writeFile(runnerHome+'/instances/'+id+'/server-folder.properties',`path=${await realpath(originalFolder)}\ninitial=true\njar=custom-vanilla.jar\nrequiredJava=21\n`);
   }
   const code=(await api('/runners/enroll','POST',{name:'Live fixture '+index})).code;
   const pair=spawn(java,[...trust,'-jar',jar,'--runner-pair','--manager-url=https://localhost:8796'],{env,stdio:['pipe','ignore','pipe']});pair.stdin!.end(code+'\n');
   await new Promise<void>((resolve,reject)=>{let error='';pair.stderr!.on('data',d=>error+=d);pair.once('exit',n=>n===0?resolve():reject(Error('Pairing failed: '+error)));pair.once('error',reject);});
   const device=JSON.parse(await readFile(runnerHome+'/identity.json','utf8')).id;
   devices.push(device);
   await api('/runners/'+device+'/allow/'+id,'PUT');await api('/instances/'+id+'/runner','PUT',{runnerId:device});
   const log=await open(runnerHome+'/test.log','w');const runner=spawn(java,[...trust,'-jar',legacy||jar,'--runner'],{env,stdio:['ignore',log.fd,log.fd]});runners.push(runner);await log.close();
   await expect.poll(async()=> (await api('/runners')).find((d:any)=>d.id===device)?.online,{timeout:30000}).toBe(true);
  }
  // Import an already-bound original folder before launching any Minecraft process.
  await api('/instances/'+ids[0]+'/import-folder','POST');
  const cacheRoot=home+'/profiles/'+ids[0]+'/runner-transfer';let resumedId='';
  await expect.poll(async()=>{try{const names=await readdir(cacheRoot);resumedId=names.find(n=>/^[a-f0-9-]{36}$/.test(n))||'';if(!resumedId)return false;const files=await readdir(cacheRoot+'/'+resumedId,{recursive:true});for(const file of files){const info=await stat(cacheRoot+'/'+resumedId+'/'+file);if(info.isFile()&&info.size>=4*1024*1024)return true;}return false;}catch{return false;}},{timeout:120000,intervals:[10,20,30]}).toBe(true);
  // Suspend only the disposable runner before any Minecraft JVM exists so that
  // authentication is replaced while its upload still has pending requests.
  runners[0].kill('SIGSTOP');
  try {await rotateFixtureRunnerSession(runnerHomes[0]);} finally {runners[0].kill('SIGCONT');}
  await waitState(ids[0],'STOPPED');
  await access(home+'/profiles/'+ids[0]+'/transfer-receipts/'+resumedId+'.json');
  const audit=(await readFile(home+'/profiles/security/audit.jsonl','utf8')).trim().split('\n').map(line=>JSON.parse(line));
  expect(audit.filter((e:any)=>e.action==='POST /api/runners/transfer/'+ids[0]+'/begin').length).toBeGreaterThanOrEqual(2);
  expect(createHash('sha256').update(await readFile(home+'/profiles/'+ids[0]+'/server/resume-proof.bin')).digest('hex')).toBe(createHash('sha256').update(Buffer.alloc(64*1024*1024,37)).digest('hex'));
  console.log(legacy?'PASS: real legacy runners import existing folder through new manager without unknown-field JSON errors.':'PASS: forced signed WSS authentication replacement during live upload preserves transfer ID, received chunks and exact bytes.');
  if(!legacy){
   expect(audit.filter((e:any)=>e.action==='PUT /api/runners/transfer/'+ids[0]+'/batch').length).toBeGreaterThan(0);
   expect(audit.filter((e:any)=>e.action==='PUT /api/runners/transfer/'+ids[0]+'/chunk').length).toBeLessThan(200);
   expect((await page.request.post('/api/runners/'+devices[0]+'/update',{data:{pauseTransfer:false}})).status()).toBe(403);
   await expect.poll(async()=> (await api('/runners')).find((r:any)=>r.id===devices[0])?.update?.phase,{timeout:20000}).toBe('UNSUPPORTED');
   expect((await page.request.post('/api/runners/'+devices[0]+'/update',{data:{pauseTransfer:false},headers:{'X-CSRF-TOKEN':session.csrf}})).status()).toBe(409);
  }
  const imported=(await api('/instances')).find((r:any)=>r.config.id===ids[0]);
  expect(imported.folderImport.phase).toBe('COMPLETE');expect(imported.pid).toBeNull();
  expect(await readFile(home+'/profiles/'+ids[0]+'/server/original-folder-proof.txt','utf8')).toContain('original folder');
  expect(await access(runnerHomes[0]+'/instances/'+ids[0]+'/console.log').then(()=>true,()=>false)).toBe(false);
  await access(home+'/profiles/'+ids[0]+'/server/custom-vanilla.jar');
  if(legacy)return;
  // A busy internal port must fail before a JVM starts and release only this instance's lease.
  const row=(await api('/instances')).find((r:any)=>r.config.id===ids[0]);const blocker=net.createServer();
  await new Promise<void>(r=>blocker.listen(row.config.port,'127.0.0.1',r));
  try{await api('/instances/'+ids[0]+'/start','POST');await waitState(ids[0],'FAILED');expect(await access(home+'/profiles/'+ids[0]+'/remote-lease').then(()=>true,()=>false)).toBe(false);}finally{await new Promise<void>(r=>blocker.close(()=>r()));}
  for(const id of ids){await api('/instances/'+id+'/start','POST');await waitState(id,'RUNNING');}
  await api('/instances/'+ids[0]+'/power-saving','PUT',{enabled:true,minutes:30});
  await expect.poll(async()=> (await api('/instances/'+ids[0]+'/power-saving')).status,{timeout:30000}).toBe('EMPTY');
  expect((await api('/instances/'+ids[0]+'/power-saving')).remainingSeconds).toBeGreaterThan(1700);
  const invitation=await api('/admin/invitations','POST',{name:'Power viewer fixture',permissions:{[ids[0]]:'VIEWER'}});
  const viewerContext=await browser.newContext({baseURL:'https://localhost:8796',ignoreHTTPSErrors:true});
  try {
   const viewer=await viewerContext.newPage();const viewerCdp=await viewerContext.newCDPSession(viewer);await viewerCdp.send('WebAuthn.enable');await viewerCdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
   await viewer.goto('/');await viewer.getByLabel('등록 코드').fill(invitation.token);await viewer.getByRole('button',{name:'패스키 등록',exact:true}).click();await expect(viewer.getByRole('button',{name:'서버 실행기',exact:true})).toBeVisible();
   const viewerSession=await (await viewer.request.get('/api/auth/session')).json();
   expect((await viewer.request.get('/api/instances/'+ids[0]+'/power-saving')).status()).toBe(200);
   expect((await viewer.request.get('/api/instances/'+ids[0]+'/mods')).status()).toBe(200);
   expect((await viewer.request.get('/api/instances/'+ids[1]+'/mods')).status()).toBe(403);
   for(const path of ['/refresh','/toggle','/diagnostic','/diagnostic/start','/diagnostic/verdict','/diagnostic/finish'])expect((await viewer.request.post('/api/instances/'+ids[0]+'/mods'+path,{data:{},headers:{'X-CSRF-TOKEN':viewerSession.csrf}})).status()).toBe(403);
   expect((await viewer.request.put('/api/instances/'+ids[0]+'/power-saving',{data:{enabled:false,minutes:30},headers:{'X-CSRF-TOKEN':viewerSession.csrf}})).status()).toBe(403);
   expect((await viewer.request.get('/api/instances/'+ids[1]+'/power-saving')).status()).toBe(403);
   expect((await viewer.request.post('/api/instances/'+ids[0]+'/import-folder',{headers:{'X-CSRF-TOKEN':viewerSession.csrf}})).status()).toBe(403);
  } finally {await viewerContext.close();}
  expect((await page.request.put('/api/instances/'+ids[0]+'/power-saving',{data:{enabled:false,minutes:30}})).status()).toBe(403);
  const invalid=await page.request.put('/api/instances/'+ids[0]+'/power-saving',{data:{enabled:true,minutes:1},headers:{'X-CSRF-TOKEN':session.csrf}});expect(invalid.status()).toBe(400);
  await api('/instances/'+ids[0]+'/power-saving','PUT',{enabled:false,minutes:30});
  for(const row of await api('/instances'))expect(JSON.stringify((await query(row.config.port,'localhost')).description)).toContain('Fixture-');
  console.log('Both Minecraft processes respond directly. Querying shared TCP '+shared);
  expect(JSON.stringify((await query(shared,'alpha.server.example.com')).description)).toContain('Fixture-0');
  expect(JSON.stringify((await query(shared,'beta.server.example.com')).description)).toContain('Fixture-1');
  await expect(query(shared,'unknown.server.example.com')).rejects.toThrow();
  await writeFile(originalFolder+'/handoff-proof.txt','changed only after remote start\n');
  await api('/instances/'+ids[0]+'/command','POST',{command:'list'});
  await api('/instances/'+ids[0]+'/stop','POST');await waitState(ids[0],'STOPPED');
  expect(await readFile(home+'/profiles/'+ids[0]+'/server/handoff-proof.txt','utf8')).toContain('changed only');
  await access(home+'/profiles/'+ids[0]+'/server/world/level.dat');
  expect(await access(home+'/profiles/'+ids[0]+'/remote-lease').then(()=>true,()=>false)).toBe(false);
  expect(await readFile(originalFolder+'/original-folder-proof.txt','utf8')).toContain('original folder');
  expect(await access(runnerHomes[0]+'/instances/'+ids[0]+'/server').then(()=>true,()=>false)).toBe(false);
  // The original path remains stable; later central changes pull into it with a sibling backup.
  await api('/instances/'+ids[0]+'/text','PUT',{path:'central-edit.txt',content:'central change after first upload'});
  await api('/instances/'+ids[0]+'/start','POST');await waitState(ids[0],'RUNNING');
  expect(await readFile(originalFolder+'/central-edit.txt','utf8')).toContain('central change');
  expect((await readdir(home+'/.servermanager-history-'+ids[0])).some(name=>name.startsWith('delta-'))).toBe(true);
  await api('/instances/'+ids[0]+'/stop','POST');await waitState(ids[0],'STOPPED');
  expect(JSON.stringify((await query(shared,'beta.server.example.com')).description)).toContain('Fixture-1');
  await expect(query(shared,'alpha.server.example.com')).rejects.toThrow();
  await api('/instances/'+ids[1]+'/stop','POST');await waitState(ids[1],'STOPPED');await access(home+'/profiles/'+ids[1]+'/server/world/level.dat');
  // Custom entryJar metadata follows the world to a normal second runner.
  await api('/runners/'+devices[1]+'/allow/'+ids[0],'PUT');await api('/instances/'+ids[0]+'/runner','PUT',{runnerId:devices[1]});
  await api('/instances/'+ids[0]+'/start','POST');await waitState(ids[0],'RUNNING');
  expect(JSON.stringify((await query(shared,'alpha.server.example.com')).description)).toContain('Fixture-0');
  await access(runnerHomes[1]+'/instances/'+ids[0]+'/server/custom-vanilla.jar');
  await api('/instances/'+ids[0]+'/stop','POST');await waitState(ids[0],'STOPPED');
  if(process.env.SM_RECOVERY_ONLY==='1'){console.log('PASS: async manifest, signed runners, normal saved handoff and original-folder restart/migration.');return;}
  // Dependency-safe manual mod search executes on the real remote Fabric runner in a separate world.
  const modId=ids[1],modBase='/instances/'+modId+'/mods',central=home+'/profiles/'+modId+'/server',remote=runnerHomes[1]+'/instances/'+modId+'/server';
  const hash=async(path:string)=>createHash('sha256').update(await readFile(path)).digest('hex');
  const worldBefore=[await hash(central+'/world/level.dat'),await hash(remote+'/world/level.dat')];
  const exec=promisify(execFile);
  for(const [name,depends] of [['library',{}],['suspect',{library:'*'}],['other',{}]] as const){
   const source=home+'/mod-fixture-'+name;await mkdir(source,{recursive:true});await writeFile(source+'/fabric.mod.json',JSON.stringify({schemaVersion:1,id:name,version:'1.0.0',name:'Fixture '+name,depends}));
   await mkdir(central+'/mods',{recursive:true});await exec(java.replace(/java$/, 'jar'),['--create','--file',central+'/mods/'+name+'.jar','-C',source,'fabric.mod.json']);
  }
  const modPage=()=>api(modBase+'?inventory=true');
  const waitMod=async()=>{await expect.poll(async()=> (await modPage()).job?.phase,{timeout:60000}).toBe('COMPLETE');};
  await api(modBase+'/refresh','POST');await waitMod();let inventory=(await modPage()).inventory;
  const preview=await api(modBase+'/toggle-preview','POST',{revision:inventory.revision,files:['suspect.jar'],enabled:false});expect(preview.affected).toEqual(['suspect.jar']);
  const blocked=await page.request.post('/api'+modBase+'/toggle-preview',{data:{revision:inventory.revision,files:['library.jar'],enabled:false},headers:{'X-CSRF-TOKEN':session.csrf}});expect(blocked.status()).toBe(400);expect(await blocked.text()).toContain('suspect.jar');
  expect((await page.request.post('/api'+modBase+'/toggle',{data:{revision:inventory.revision,files:['suspect.jar'],enabled:false}})).status()).toBe(403);
  await api(modBase+'/toggle','POST',{revision:inventory.revision,files:['suspect.jar'],enabled:false});await waitMod();inventory=(await modPage()).inventory;
  expect(inventory.mods.filter((m:any)=>!m.enabled).map((m:any)=>m.filename)).toEqual(['suspect.jar.disabled']);
  await api(modBase+'/toggle','POST',{revision:inventory.revision,files:['suspect.jar.disabled'],enabled:true});await waitMod();
  await page.getByRole('button',{name:/Fixture Fabric Fabric/}).click();await page.getByRole('button',{name:'모드 · 문제 찾기',exact:true}).click();
  await page.getByLabel('suspect.jar 의심 모드',{exact:true}).check();await page.getByLabel('other.jar 의심 모드',{exact:true}).check();await expect(page.getByLabel('library.jar 항상 유지',{exact:true})).toBeChecked();await expect(page.getByLabel('library.jar 의심 모드',{exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'선택한 2개에서 문제 모드 찾기',exact:true}).click();await waitMod();
  await expect(page.getByRole('button',{name:'이 조합으로 테스트 시작',exact:true})).toBeEnabled();
  const diag=(await modPage()).diagnostic.id,sandbox=home+'/profiles/'+modId+'/diagnostics/'+diag+'/server',runnerSandbox=runnerHomes[1]+'/instances/'+modId+'/diagnostics/'+diag+'/server';
  expect(await access(sandbox+'/world/level.dat').then(()=>true,()=>false)).toBe(false);
  expect((await page.request.post('/api/instances/'+modId+'/start',{headers:{'X-CSRF-TOKEN':session.csrf}})).status()).toBe(409);
  for(const verdict of ['FAIL','PASS','FAIL']) {
   await page.getByRole('button',{name:'이 조합으로 테스트 시작',exact:true}).click();await waitState(modId,'RUNNING');
   expect(JSON.stringify((await query(shared,'beta.server.example.com')).description)).toContain('Fixture-1');
   expect(await hash(central+'/world/level.dat')).toBe(worldBefore[0]);expect(await hash(remote+'/world/level.dat')).toBe(worldBefore[1]);
   expect(await access(runnerSandbox+'/world/level.dat').then(()=>true,()=>false)).toBe(false);
   await page.getByLabel('재현 상황 / 판정 메모').fill('Manual fixture '+verdict);
   await page.getByRole('button',{name:verdict==='FAIL'?'이번 시도 실패':'이번 시도 성공',exact:true}).click();await waitState(modId,'STOPPED');
   await expect.poll(async()=> (await modPage()).diagnostic.phase,{timeout:30000}).toBe('JUDGED');
   await expect(async()=>{await api(modBase+'/diagnostic/next','POST');}).toPass({timeout:20000,intervals:[1000]});await waitMod();
  }
  const result=await modPage();expect(result.next.status).toBe('FOUND');expect(result.next.groups).toHaveLength(1);
  expect(await access(sandbox+'/sm-debug-'+diag.slice(0,8)+'-3/level.dat').then(()=>true,()=>false)).toBe(true);
  expect((await api(modBase+'/diagnostic/trials/1/logs')).some((e:any)=>e.path==='server/logs/latest.log')).toBe(true);
  await expect(page.locator('.runner-assignment')).toHaveCount(1);
  await page.screenshot({path:'test-results/mod-diagnostic-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:'test-results/mod-diagnostic-mobile.png',fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.setViewportSize({width:1440,height:1000});
  await expect(async()=>{await api(modBase+'/diagnostic/finish','POST');}).toPass({timeout:20000,intervals:[1000]});await waitMod();
  expect((await modPage()).active).toBe(false);expect(await hash(central+'/world/level.dat')).toBe(worldBefore[0]);expect(await hash(remote+'/world/level.dat')).toBe(worldBefore[1]);
  console.log('PASS: real remote isolated manual mod search, dependency disable/restore, explicit verdicts, trial logs, original central and runner worlds unchanged, read-only/CSRF authorization.');
  const accounts=JSON.parse(await readFile(home+'/profiles/security/accounts.json','utf8'));expect(JSON.stringify(accounts)).not.toContain('fixture-only-password');
  console.log('PASS: original-folder direct execution and stable-path restart/backup, custom JAR migration, passkey enrollment, signed WSS runners, shared TCP isolation, busy-port recovery, console and normal save/file handoff.');
 } finally {
  let safe=true;
  for(const id of ids){try{const current=await state(id);if(['RUNNING','STARTING'].includes(current)){await api('/instances/'+id+'/stop','POST');await waitState(id,'STOPPED');}else if(['STOPPING','SYNCING','TRANSFERRING','UNKNOWN','SYNC_FAILED'].includes(current)){await waitState(id,'STOPPED');}}catch{safe=false;}}
  // Never kill a worker with a possibly active test world.
  if(safe)for(const runner of runners){runner.kill('SIGTERM');await new Promise<void>(resolve=>{if(runner.exitCode!==null)resolve();else runner.once('exit',()=>resolve());});}
  else console.error('Test cleanup needs inspection: workers kept alive to avoid forced world shutdown. '+home);
 }
});
