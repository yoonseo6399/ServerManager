import {test,expect} from '@playwright/test';

test('owner toggles modules, saves revision, sees conflicts and reloads persisted state',async({page})=>{
 let revision=0;let enabled=true;const saves:any[]=[];
 await page.route('**/api/**',async route=>{
  const p=new URL(route.request().url()).pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'test',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:'http://127.0.0.1:5186',setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/discord')body={revision:0,tokenConfigured:false,config:{enabled:false,grants:[],alerts:[],superAdmins:[]}};
  else if(p==='/api/admin/discord/status')body={state:'OFF',message:'test',inviteUrl:null,guilds:[]};
  else if(p==='/api/admin/discord/modules'){
   if(route.request().method()==='PUT'){
    const v=route.request().postDataJSON();saves.push(v);
    expect(route.request().headers()['x-csrf-token']).toBe('x');
    if(v.revision!==revision){await route.fulfill({status:409,json:{message:'설정 충돌'}});return;}
    revision++;enabled=!v.disabled.includes('music');
   }
   body={revision,warning:'',modules:[{id:'music',label:'음악',enabled},{id:'minecraft',label:'Minecraft 서버 제어',enabled:true}]};
  }
  await route.fulfill({json:body});
 });
 await page.goto('/#integrations');const section=page.getByRole('region',{name:'봇 기능 모듈'});
 await expect(section.getByLabel('음악',{exact:true})).toBeChecked();
 await section.getByLabel('음악',{exact:true}).uncheck();
 page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('button',{name:'시스템',exact:true}).click();
 await expect(section).toBeVisible();await expect(section.getByLabel('음악',{exact:true})).not.toBeChecked();
 await page.getByRole('button',{name:'모듈 설정 저장',exact:true}).click();
 await expect(section.getByRole('status')).toContainText('적용했습니다');expect(saves[0]).toEqual({revision:0,disabled:['music']});
 await expect(section.getByLabel('음악',{exact:true})).not.toBeChecked();
 revision++;await section.getByLabel('음악',{exact:true}).check();await page.getByRole('button',{name:'모듈 설정 저장',exact:true}).click();
 await expect(section.getByRole('alert')).toContainText('설정 충돌');
 await section.getByRole('button',{name:'다시 불러오기'}).click();await expect(section.getByLabel('음악',{exact:true})).not.toBeChecked();
});
