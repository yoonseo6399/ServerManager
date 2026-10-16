import {test,expect,type Page} from '@playwright/test';
async function fixture(page:Page,legacy=false,beta=false){
 const calls:any[]=[];let phase='CURRENT';
 const target=beta?'0.6.2':'0.7.0-beta.2';
 await page.route('**/api/**',async route=>{const p=new URL(route.request().url()).pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'0.6.2',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:'http://127.0.0.1:5186',setupRequired:false};
  else if(p==='/api/instances')body=[];
  else if(p==='/api/runners')body=[{id:'runner',name:'Test runner',platform:'MAC',registeredBy:'owner',version:beta?'0.7.0-beta.2':legacy?'0.6.1':'0.6.2',enabled:true,allowedInstanceIds:[],online:true,remoteUpdateSupported:true,releaseSelectionSupported:!legacy,rollbackSupported:!legacy,update:{phase,latest:target,downloadedBytes:50,totalBytes:100}}];
  else if(p==='/api/runners/runner/releases')body=[{version:target,name:'Target release',prerelease:!beta,publishedAt:'2026-10-16T00:00:00Z',url:'https://github.com/yoonseo6399/ServerManager/releases',installable:true,reason:null,rollback:beta}];
  else if(p==='/api/runners/runner/update'){calls.push(route.request().postDataJSON());phase='DOWNLOADING';}
  else if(p==='/api/runners/runner/update-status')body={available:true,components:[{component:'RUNNER_CONTROL',current:'0.6.2',target,stage:'WAITING',waitReason:'TRANSFER_ACTIVE',error:null}],log:[{seq:1,time:'2026-10-16T00:00:00Z',component:'RUNNER_CONTROL',stage:'WAITING',message:'전송 완료를 기다립니다'}],hostMode:{mode:'SERVICE',reason:null}};
  await route.fulfill({json:body});
 });
 await page.goto('/#runners');return calls;
}
test('stable runner selects beta with consent and shows exact target progress and logs',async({page})=>{
 const calls=await fixture(page);const panel=page.getByLabel('Test runner 업데이트',{exact:true});
 await expect(page.getByText('버전 0.6.2 · STABLE 채널',{exact:false})).toBeVisible();
 await expect(page.getByText('전송 완료를 기다립니다',{exact:false})).toBeVisible();
 await panel.getByRole('button',{name:'릴리즈 목록 새로고침'}).click();await panel.getByLabel('설치할 릴리즈').selectOption('0.7.0-beta.2');
 const install=panel.getByRole('button',{name:'선택한 릴리즈 설치'});await expect(install).toBeDisabled();await panel.getByLabel('베타 설치 동의').check();
 page.once('dialog',d=>d.accept());await install.click();await expect.poll(()=>calls.length).toBe(1);
 expect(calls[0]).toEqual({pauseTransfer:false,version:'0.7.0-beta.2',allowPrerelease:true,allowRollback:false});
 await expect(panel.getByRole('progressbar')).toHaveAttribute('value','50');await expect(panel.getByText('50%',{exact:false})).toBeVisible();
});
test('beta runner requires explicit stable rollback consent',async({page})=>{
 const calls=await fixture(page,false,true);const panel=page.getByLabel('Test runner 업데이트',{exact:true});
 await panel.getByRole('button',{name:'릴리즈 목록 새로고침'}).click();await panel.getByLabel('설치할 릴리즈').selectOption('0.6.2');
 const install=panel.getByRole('button',{name:'선택한 릴리즈 설치'});await expect(install).toBeDisabled();await panel.getByLabel('안정판 롤백 동의').check();
 page.once('dialog',async d=>{expect(d.message()).toContain('롤백');await d.accept();});await install.click();await expect.poll(()=>calls.length).toBe(1);
 expect(calls[0]).toEqual({pauseTransfer:false,version:'0.6.2',allowPrerelease:false,allowRollback:true});
});
test('old runner must first upgrade its stable control layer',async({page})=>{
 const calls=await fixture(page,true);const panel=page.getByLabel('Test runner 업데이트',{exact:true});
 await panel.getByRole('button',{name:'릴리즈 목록 새로고침'}).click();await panel.getByLabel('설치할 릴리즈').selectOption('0.7.0-beta.2');await panel.getByLabel('베타 설치 동의').check();
 await expect(panel.getByRole('button',{name:'선택한 릴리즈 설치'})).toBeDisabled();await expect(panel.getByText('먼저 위의 원격 업데이트 요청으로 실행기를 최신 안정판으로 업데이트하세요.',{exact:false})).toBeVisible();
 await panel.getByRole('button',{name:'원격 업데이트 요청',exact:true}).click();await expect.poll(()=>calls.length).toBe(1);expect(calls[0]).toEqual({pauseTransfer:false});
});
