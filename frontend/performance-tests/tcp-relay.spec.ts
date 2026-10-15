import {test,expect} from '@playwright/test';
test('owner selects a consenting relay and incompatible UDP is cleared before save',async({page})=>{
 let saved:any={config:{enabled:true,port:6399,sableUdpEnabled:true,baseDomain:'server.example.com',targetHost:'gateway.example.com',defaultInstanceId:null,defaultHostname:'',labels:{},cloudflareEnabled:false,zoneId:''},revision:0,tokenConfigured:false};
 let payload:any;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let result:any={};
  if(path==='/api/auth/session')result={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'test',origin:'http://127.0.0.1:8792',publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')result=[];
  else if(path==='/api/runners')result=[{id:'relay',name:'Relay Mac',online:true,tcpRelaySupported:true},{id:'old',name:'Old runner',online:true,tcpRelaySupported:false}];
  else if(path==='/api/admin/game-network'){
   if(route.request().method()==='PUT'){payload=route.request().postDataJSON();saved={...saved,config:payload.config,revision:1};result=saved;}
   else result={settings:saved,dnsMessage:'DNS off',relayMessage:'중앙 Mac 중계'};
  }
  await route.fulfill({json:result});
 });
 await page.goto('/#network');
 const select=page.getByRole('combobox',{name:'TCP 중계 컴퓨터'});
 await expect(select).toBeVisible();await expect(select.locator('option[value="old"]')).toHaveJSProperty('disabled',true);
 await select.selectOption('relay');await expect(page.getByLabel('Sable UDP 중계 사용')).not.toBeChecked();await expect(page.getByLabel('Sable UDP 중계 사용')).toBeDisabled();
 await page.getByRole('button',{name:'중계·DNS 설정 저장'}).click();
 await expect.poll(()=>payload?.config.relayRunnerId).toBe('relay');expect(payload.config.sableUdpEnabled).toBe(false);
 await expect(page.getByText('중계 실행기 → 관리 Mac → 게임 실행기', {exact:false})).toBeVisible();
});
