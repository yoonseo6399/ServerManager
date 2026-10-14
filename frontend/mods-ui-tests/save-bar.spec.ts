import {test,expect} from '@playwright/test';
test('unsaved bars stay visible at scroll positions, preserve failed drafts and guard navigation',async({page})=>{
 let name='원본 이름',fail=true,saves=0;
 await page.route('**/api/**',async route=>{
  const p=new URL(route.request().url()).pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'0.4.8',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',setupRequired:false};
  else if(p==='/api/instances')body=[{config:{id:'test',name,loader:'Fabric',minecraft:'1.21.1',loaderVersion:'0.16.14',port:25565,memoryMb:1024,javaPath:'java',runnerId:null},state:'STOPPED',pid:null,resources:null,directory:'/fixture',eulaAccepted:true,powerSaving:{config:{enabled:false,minutes:30},status:'OFF'},autoRestart:{config:{enabled:false,includeManual:false},status:'OFF'}}];
  else if(p==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,startableHeapMb:14400};
  else if(p==='/api/game-addresses')body={enabled:false,labels:{},hosts:{}};
  else if(p==='/api/runners'||p.endsWith('/log-files'))body=[];
  else if(p.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(p==='/api/instances/test'&&route.request().method()==='PUT'){saves++;if(fail){await route.fulfill({status:409,json:{message:'설정 충돌'}});return;}name=route.request().postDataJSON().name;}
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:'서버 설정',exact:true}).click();
 const input=page.getByLabel('서버 이름',{exact:true}),bar=page.getByRole('region',{name:'서버 설정 변경 사항',exact:true});await expect(bar).toHaveCount(0);
 await input.fill('수정된 이름');await expect(bar).toBeVisible();const y=(await bar.boundingBox())!.y;
 await page.evaluate(()=>window.scrollTo(0,document.body.scrollHeight));expect(Math.abs((await bar.boundingBox())!.y-y)).toBeLessThan(2);
 await bar.getByRole('button',{name:'설정 저장',exact:true}).click();await expect(bar).toContainText('설정 충돌');expect(saves).toBe(1);await expect(input).toHaveValue('수정된 이름');
 page.once('dialog',d=>d.dismiss());await page.getByRole('button',{name:'콘솔',exact:true}).click();await expect(input).toBeVisible();
 fail=false;await bar.getByRole('button',{name:'설정 저장',exact:true}).click();await expect(bar).toHaveCount(0);expect(name).toBe('수정된 이름');
 await input.fill('임시');await bar.getByRole('button',{name:'되돌리기',exact:true}).click();await expect(input).toHaveValue(name);await expect(bar).toHaveCount(0);
 await page.getByLabel('무인 자동 종료',{exact:true}).check();await expect(page.getByRole('region',{name:'무인 절전 설정 변경 사항'})).toBeVisible();
 await page.getByLabel('서버 자동 재시작',{exact:true}).check();await expect(page.locator('#unsaved-bars .save-bar')).toHaveCount(2);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'test-results/save-bars-mobile.png'});
 page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'콘솔',exact:true}).click();await expect(page.locator('#unsaved-bars .save-bar')).toHaveCount(0);
});
