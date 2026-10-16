import {test,expect} from '@playwright/test';
test('owner selects a beta explicitly and installs the exact version without changing automatic updates',async({page})=>{
 const installed:any[]=[];
 const status={supported:true,enabled:false,current:'0.6.1',latest:null,message:'확인 대기',checking:false,repository:'https://github.com/yoonseo6399/ServerManager'};
 await page.route('**/api/**',async route=>{
  const p=new URL(route.request().url()).pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'0.6.1',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:'http://127.0.0.1:5186',setupRequired:false};
  else if(['/api/instances','/api/runners','/api/admin/audit'].includes(p))body=[];
  else if(p==='/api/admin/updates')body=status;
  else if(p==='/api/admin/storage')body={supported:true,path:'/test',pending:null};
  else if(p==='/api/admin/system-logs')body={events:[],nextCursor:0,oldestCursor:0,reset:false,sessionId:'a'};
  else if(p==='/api/admin/updates/releases')body=[{version:'0.7.0-beta.1',name:'Qwen beta',prerelease:true,publishedAt:'2026-10-16T00:00:00Z',url:'https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.1',installable:true,reason:null},{version:'0.6.1',name:'Current',prerelease:false,installable:false,reason:'현재 버전 이하이므로 설치할 수 없습니다'}];
  else if(p==='/api/admin/updates/install'){installed.push(route.request().postDataJSON());body={...status,message:'업데이트를 받았습니다. 안전 재시작 대기'};}
  await route.fulfill({json:body});
 });
 await page.goto('/#system');
 await page.getByRole('button',{name:'릴리즈 목록 새로고침'}).click();
 const select=page.getByLabel('설치할 릴리즈');await select.selectOption('0.7.0-beta.1');
 const install=page.getByRole('button',{name:'선택한 릴리즈 설치'});await expect(install).toBeDisabled();
 await expect(select.locator('option[value="0.6.1"]')).toHaveJSProperty('disabled',true);
 await page.getByLabel('베타 설치 동의').check();await expect(install).toBeEnabled();
 page.once('dialog',d=>d.dismiss());await install.click();expect(installed).toEqual([]);
 page.once('dialog',d=>d.accept());await install.click();
 await expect.poll(()=>installed.length).toBe(1);expect(installed[0]).toEqual({version:'0.7.0-beta.1',allowPrerelease:true,allowRollback:false});
 await expect(page.getByLabel('자동 업데이트',{exact:true})).not.toBeChecked();
 await expect(page.getByRole('status').filter({hasText:'안전 재시작 대기'})).toBeVisible();
});
