import {test,expect,type Page} from '@playwright/test';
const row=(id:string)=>({config:{id,name:'서버 '+id,loader:'Vanilla',minecraft:'1.21.1',loaderVersion:'1.21.1',port:25565,memoryMb:2048,javaPath:'java',runnerId:null},state:'STOPPED',pid:null,directory:'/fixture/'+id,eulaAccepted:true,resources:null});
async function fixture(page:Page){
 await page.route('**/api/**',async route=>{const u=new URL(route.request().url()),p=u.pathname;
  let body:unknown={};
  if(p==='/api/auth/session')body={appVersion:'test',user:{id:'owner',name:'소유자',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:5187',setupRequired:false};
  else if(p==='/api/instances')body=[row('A'),row('B')];
  else if(p==='/api/memory')body={totalMb:16384,freeMb:8192,reservedMb:2048,maxHeapMb:8192,startableHeapMb:8192};
  else if(p==='/api/health')body={javaPath:'java'};
  else if(p==='/api/game-addresses')body={enabled:false,hosts:{}};
  else if(p==='/api/minecraft-versions')body={versions:['1.21.1']};
  else if(p==='/api/version-catalog')body={versions:['21.1.264']};
  else if(p.endsWith('/files'))body=[{name:'config.txt',path:'config.txt',directory:false,size:8},{name:'sub',path:'sub',directory:true,size:0}];
  else if(p.endsWith('/text'))body={content:'original',revision:'r1'};
  else if(p.endsWith('/log-window'))body={lines:[],total:0,from:1,next:null};
  else if(p.endsWith('/log-files')||p.includes('/logs'))body=[];
  else if(p==='/api/runners')body=[];
  else if(p.endsWith('/runner-options'))body={runners:[],selected:null};
  await route.fulfill({json:body});
 });
 await page.goto('/');await expect(page.getByRole('heading',{name:/서버 A/})).toBeVisible();
}
async function files(page:Page){await fixture(page);await page.getByRole('button',{name:'파일 관리자',exact:true}).click();await expect(page.getByRole('button',{name:'config.txt',exact:true})).toBeVisible();}
async function edit(page:Page){await files(page);await page.getByRole('button',{name:'config.txt',exact:true}).click();await page.getByRole('textbox',{name:'파일 내용'}).fill('draft');}

test('late server response cannot replace selected server files',async({page})=>{
 let release!:()=>void;const gate=new Promise<void>(r=>release=r);
 await fixture(page);
 await page.route('**/api/instances/A/files?*',async route=>{await gate;await route.fulfill({json:[{name:'A-only',path:'A-only',directory:false,size:1}]});});
 await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await expect(page.getByText('파일 목록을 불러오는 중…')).toBeVisible();
 await page.getByRole('button',{name:/서버 B Vanilla/}).click();
 await expect(page.getByRole('button',{name:'config.txt',exact:true})).toBeVisible();release();
 await page.waitForTimeout(100);await expect(page.getByRole('button',{name:'A-only',exact:true})).toHaveCount(0);
});

test('late folder response is ignored and loading is never empty state',async({page})=>{
 await files(page);let release!:()=>void;const gate=new Promise<void>(r=>release=r);
 await page.route('**/files?path=sub',async route=>{await gate;await route.fulfill({json:[{name:'sub-only',path:'sub/sub-only',directory:false,size:1}]});});
 await page.getByRole('button',{name:'sub',exact:true}).click();await expect(page.getByText('파일 목록을 불러오는 중…')).toBeVisible();
 await expect(page.getByText('비어 있는 폴더입니다.')).toHaveCount(0);
 await page.getByRole('button',{name:'상위 폴더'}).click();await expect(page.getByRole('button',{name:'config.txt',exact:true})).toBeVisible();release();
 await page.waitForTimeout(100);await expect(page.getByRole('button',{name:'sub-only',exact:true})).toHaveCount(0);
});

for(const destination of ['닫기','서버 B','서버 설정','폴더'])test('dirty draft protected: '+destination,async({page})=>{
 await edit(page);let prompts=0;page.on('dialog',async d=>{prompts++;await d.dismiss();});
 if(destination==='닫기')await page.locator('.editor').getByRole('button',{name:'닫기',exact:true}).click();
 else if(destination==='서버 B')await page.getByRole('button',{name:/서버 B Vanilla/}).click();
 else if(destination==='폴더')await page.getByRole('button',{name:'sub',exact:true}).click();
 else await page.getByRole('button',{name:'서버 설정',exact:true}).click();
 expect(prompts).toBe(1);await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('draft');
});

test('browser back cancel preserves draft and history',async({page})=>{
 await edit(page);page.on('dialog',d=>d.dismiss());await page.goBack();
 await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('draft');
});

test('save conflict keeps draft and offers actionable error',async({page})=>{
 await edit(page);await page.route('**/instances/A/text',r=>r.fulfill({status:409,json:{message:'다른 작업이 파일을 변경했습니다. 원본을 확인하세요.'}}));
 await page.locator('.editor').getByRole('button',{name:'저장',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('다른 작업');await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('draft');
});

test('successful save clears dirty and retains exact text',async({page})=>{
 await edit(page);let saved='original',revision='r1';await page.route('**/instances/A/text*',async route=>{if(route.request().method()==='PUT'){saved=route.request().postDataJSON().content;revision='r2';await route.fulfill({body:''});}else await route.fulfill({json:{content:saved,revision}});});
 await page.locator('.editor').getByRole('button',{name:'저장',exact:true}).click();await expect(page.getByText('파일을 저장했습니다.',{exact:true})).toBeVisible();
 await expect(page.locator('.save-bar')).toHaveCount(0);await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('draft');
});

test('new blank file can be created without fetching missing file',async({page})=>{
 await files(page);let reads=0;await page.route('**/text?path=new.txt',async r=>{reads++;await r.fulfill({status:404,json:{message:'missing'}});});
 page.once('dialog',d=>d.accept('new.txt'));await page.getByRole('button',{name:'파일',exact:true}).click();
 await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('');expect(reads).toBe(0);await expect(page.locator('.editor').getByRole('button',{name:'저장',exact:true})).toBeEnabled();
});

test('file error offers retry without pretending empty',async({page})=>{
 await fixture(page);let fail=true;await page.route('**/files?*',r=>r.fulfill(fail?{status:503,json:{message:'실행기 연결 실패'}}:{json:[]}));
 await page.getByRole('button',{name:'파일 관리자',exact:true}).click();await expect(page.getByRole('alert')).toContainText('실행기 연결 실패');await expect(page.getByText('비어 있는 폴더입니다.')).toHaveCount(0);
 fail=false;await page.getByRole('button',{name:'파일 목록 다시 시도'}).click();await expect(page.getByText('비어 있는 폴더입니다.')).toBeVisible();
});

test('modal traps keyboard focus and restores trigger on Escape',async({page})=>{
 await fixture(page);const trigger=page.getByRole('button',{name:'서버 만들기',exact:true});await trigger.click();const dialog=page.getByRole('dialog',{name:'새로운 서버 만들기'});await expect(dialog).toBeVisible();
 for(let i=0;i<22;i++){await page.keyboard.press('Shift+Tab');expect(await dialog.evaluate(el=>el.contains(document.activeElement))).toBe(true);}
 await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(trigger).toBeFocused();
});

test('invalid creation port is blocked before request',async({page})=>{
 await fixture(page);let creates=0;await page.route('**/api/instances',async r=>{if(r.request().method()==='POST')creates++;await r.fulfill({json:[row('A'),row('B')]});});
 await page.getByRole('button',{name:'서버 만들기',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.getByLabel('내부 게임 포트').fill('99999');
 await dialog.getByRole('button',{name:'서버 만들기',exact:true}).click();expect(creates).toBe(0);await expect(dialog).toBeVisible();
});

test('optional metadata failure does not hide servers; state failure is visible',async({page})=>{
 await fixture(page);await page.route('**/api/memory',r=>r.fulfill({status:500,json:{message:'memory failed'}}));
 await page.waitForTimeout(2200);await expect(page.getByRole('heading',{name:/서버 A/})).toBeVisible();
 await page.route('**/api/instances',r=>r.fulfill({status:503,json:{message:'status offline'}}));await expect(page.getByRole('alert').filter({hasText:'최신이 아닐 수 있습니다'})).toBeVisible();
});

test('mobile file layout has no page overflow',async({page})=>{
 await page.setViewportSize({width:390,height:844});await files(page);await page.getByRole('button',{name:'config.txt',exact:true}).click();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});

test('saving blocks server navigation until the result is known',async({page})=>{
 await edit(page);let release!:()=>void;const gate=new Promise<void>(r=>release=r);
 await page.route('**/instances/A/text*',async route=>{if(route.request().method()==='PUT')await gate;await route.fulfill({json:{content:'draft',revision:'r2'}});});
 await page.locator('.editor').getByRole('button',{name:'저장',exact:true}).click();await expect(page.getByRole('textbox',{name:'파일 내용'})).toBeDisabled();
 await page.getByRole('button',{name:/서버 B Vanilla/}).click();await expect(page.getByRole('heading',{name:/서버 A/})).toBeVisible();
 release();await expect(page.getByText('파일을 저장했습니다.',{exact:true})).toBeVisible();await page.getByRole('button',{name:/서버 B Vanilla/}).click();await expect(page.getByRole('heading',{name:/서버 B/})).toBeVisible();
});
test('late file read cannot open an editor in another folder',async({page})=>{
 await files(page);let release!:()=>void;const gate=new Promise<void>(r=>release=r);
 await page.route('**/text?path=config.txt',async r=>{await gate;await r.fulfill({json:{content:'stale',revision:'old'}});});
 await page.getByRole('button',{name:'config.txt',exact:true}).click();await expect(page.getByText('파일을 읽는 중…')).toBeVisible();await page.getByRole('button',{name:'sub',exact:true}).click();release();
 await page.waitForTimeout(100);await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveCount(0);
});
test('periodic config refresh preserves settings draft',async({page})=>{
 await fixture(page);await page.getByRole('button',{name:'서버 설정',exact:true}).click();await page.getByLabel('서버 이름',{exact:true}).fill('내 초안');
 await page.route('**/api/instances',r=>r.fulfill({json:[{...row('A'),config:{...row('A').config,port:25570}},row('B')]}));
 await page.waitForTimeout(2300);await expect(page.getByLabel('서버 이름',{exact:true})).toHaveValue('내 초안');
});
for(const cancel of [false,true])test('delete reauthentication '+(cancel?'cancel preserves server':'retries original target once'),async({page})=>{
 await page.addInitScript(({cancel})=>{(window as any).PublicKeyCredential=function(){};Object.defineProperty(navigator,'credentials',{value:{get:async()=>{if(cancel)throw new DOMException('사용자가 인증을 취소했습니다.','NotAllowedError');return {id:'key',rawId:new Uint8Array([1]).buffer,type:'public-key',getClientExtensionResults:()=>({}),response:{clientDataJSON:new Uint8Array([1]).buffer,authenticatorData:new Uint8Array([1]).buffer,signature:new Uint8Array([1]).buffer,userHandle:null}};}}});},{cancel});
 await fixture(page);const targets:string[]=[];await page.route('**/api/instances/*',async r=>{if(r.request().method()!=='DELETE')return r.fallback();targets.push(new URL(r.request().url()).pathname);await r.fulfill(targets.length===1?{status:428,json:{message:'패스키 확인 필요'}}:{body:''});});
 await page.route('**/auth/login/options',r=>r.fulfill({json:{publicKey:{challenge:'AQ',allowCredentials:[]}}}));
 await page.getByRole('button',{name:'서버 설정',exact:true}).click();page.once('dialog',d=>d.accept('서버 A'));await page.getByRole('button',{name:'서버 삭제',exact:true}).click();
 if(cancel){await expect(page.getByRole('alert').filter({hasText:'인증을 취소'})).toBeVisible();expect(targets).toEqual(['/api/instances/A']);}
 else {await expect(page.getByText('서버를 삭제하고 데이터를 복구용 보관함으로 옮겼습니다.')).toBeVisible();expect(targets).toEqual(['/api/instances/A','/api/instances/A']);}
});
test('busy create dialog stays open on Escape and browser back',async({page})=>{
 await fixture(page);let release!:()=>void;const gate=new Promise<void>(r=>release=r);
 await page.route('**/api/instances',async r=>{if(r.request().method()!=='POST')return r.fallback();await gate;await r.fulfill({json:row('C')});});
 await page.getByRole('button',{name:'서버 만들기',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.getByRole('button',{name:'서버 만들기',exact:true}).click();await expect(dialog).toHaveAttribute('aria-busy','true');
 await page.keyboard.press('Escape');await expect(dialog).toBeVisible();await page.goBack();await expect(dialog).toBeVisible();release();await expect(dialog).toHaveCount(0);
});

test('discarding draft enters folder with exactly one confirmation',async({page})=>{
 await edit(page);let count=0;page.on('dialog',async d=>{count++;await d.accept();});await page.getByRole('button',{name:'sub',exact:true}).click();
 await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveCount(0);expect(count).toBe(1);
});

test('returning through history restores the original server editor',async({page})=>{
 await files(page);await page.getByRole('button',{name:'config.txt',exact:true}).click();await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('original');
 await page.getByRole('button',{name:/서버 B Vanilla/}).click();await expect(page.getByRole('heading',{name:/서버 B/})).toBeVisible();await expect.poll(()=>page.evaluate(()=>history.state?.view?.selected)).toBe('B');await page.goBack();
 await expect(page.getByRole('heading',{name:/서버 A/})).toBeVisible();await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('original');
});

test('text decoding rejection offers original download and retry',async({page})=>{
 await files(page);await page.route('**/text?*',r=>r.fulfill({status:400,json:{message:'UTF-8 텍스트로 읽을 수 없어 편집을 중단했습니다.'}}));await page.getByRole('button',{name:'config.txt',exact:true}).click();
 await expect(page.getByRole('link',{name:'원본 다운로드'})).toHaveAttribute('href','/api/instances/A/download?path=config.txt');await expect(page.getByRole('button',{name:'파일 다시 읽기'})).toBeVisible();
});

test('partial upload reports exact completed count',async({page})=>{
 await files(page);let count=0;await page.route('**/upload?*',r=>{count++;return r.fulfill(count===1?{body:''}:{status:500,json:{message:'disk full'}});});
 await page.getByLabel('업로드할 파일').setInputFiles([{name:'one.txt',mimeType:'text/plain',buffer:Buffer.from('1')},{name:'two.txt',mimeType:'text/plain',buffer:Buffer.from('2')}]);
 await expect(page.getByRole('alert')).toContainText('1/2개 업로드 완료');await expect(page.getByRole('button',{name:'업로드',exact:true})).toBeEnabled();
});

test('desktop and mobile edit layout evidence',async({page})=>{
 await edit(page);await page.screenshot({path:'test-results/review-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.locator('.editor').scrollIntoViewIfNeeded();await page.screenshot({path:'test-results/review-mobile.png',fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

 test('mobile management menu is collapsible and keyboard reachable',async({page})=>{await page.setViewportSize({width:390,height:844});await fixture(page);await expect(page.getByRole('button',{name:'내 계정',exact:true})).toBeHidden();await page.getByRole('button',{name:'관리 메뉴 펼치기'}).click();await expect(page.getByRole('button',{name:'내 계정',exact:true})).toBeVisible();await page.getByRole('button',{name:'관리 메뉴 접기'}).click();await expect(page.getByRole('button',{name:'내 계정',exact:true})).toBeHidden();});
