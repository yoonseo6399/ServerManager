import {test,expect,type Page} from '@playwright/test';

const instance=(id:string,name:string)=>({config:{id,name,loader:'Fabric',minecraft:'1.21.1',loaderVersion:'0.19.5',port:25565,memoryMb:1024,javaPath:'java',runnerId:null},state:'STOPPED'});
async function mock(page:Page){
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body:any={};
  if(path==='/api/auth/session')body={appVersion:'0.4.12',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'fixture',origin:url.origin,publicOrigin:'',setupRequired:false};
  else if(path==='/api/instances')body=[instance('a','서버A'),instance('b','서버B')];
  else if(path==='/api/memory')body={totalMb:16000,freeMb:8000,reservedMb:1600,maxHeapMb:14400,committedHeapMb:0,startableHeapMb:14400};
  else if(path==='/api/game-addresses')body={enabled:false};
  else if(path==='/api/health')body={javaPath:'java'};
  else if(path==='/api/runners')body=[];
  else if(path.endsWith('/files'))body=url.searchParams.get('path')==='config'?[{name:'a.txt',path:'config/a.txt',directory:false,size:3}]:[{name:'config',path:'config',directory:true,size:0}];
  else if(path.endsWith('/text'))body={content:'hello',revision:'r1'};
  else if(path.endsWith('/event-page'))body={events:[],hasMore:false};
  else if(path.endsWith('/log-files'))body=[];
  else if(path.endsWith('/log-display'))body={revision:0,enabled:true,rules:[],presets:[]};
  await route.fulfill({json:body});
 });
}
const title=(page:Page)=>page.locator('.title-row h1');
const tab=(page:Page,name:string)=>page.locator('.tabs').getByRole('button',{name});

test('Back and Forward walk through tabs, servers, folders and the editor instead of leaving the app',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await mock(page);await page.goto('/');await page.goto('about:blank');await page.goBack();
 await expect(title(page)).toContainText('서버A');
 // tabs
 await tab(page,'파일 관리자').click();await expect(page.locator('.files-panel')).toBeVisible();
 await tab(page,'서버 설정').click();await expect(page.getByRole('heading',{name:'서버 설정'})).toBeVisible();
 await page.goBack();await expect(page.locator('.files-panel')).toBeVisible();
 await page.goBack();await expect(page.locator('.files-panel')).toHaveCount(0);await expect(title(page)).toContainText('서버A');
 await page.goForward();await expect(page.locator('.files-panel')).toBeVisible();
 // folder + editor
 await page.locator('.file-name').filter({hasText:'config'}).click();await expect(page.locator('.breadcrumb')).toContainText('config');
 await page.locator('.file-name').filter({hasText:'a.txt'}).click();await expect(page.getByLabel('파일 내용')).toHaveValue('hello');
 await page.goBack();await expect(page.getByLabel('파일 내용')).toHaveCount(0);await expect(page.locator('.breadcrumb')).toContainText('config');
 await page.goBack();await expect(page.locator('.breadcrumb')).not.toContainText('config');await expect(page.locator('.files-panel')).toBeVisible();
 await page.goForward();await expect(page.locator('.breadcrumb')).toContainText('config');await page.goForward();await expect(page.getByLabel('파일 내용')).toHaveValue('hello');
 // server switch
 await page.locator('aside nav button').filter({hasText:'서버B'}).click();await expect(title(page)).toContainText('서버B');
 await page.goBack();await expect(title(page)).toContainText('서버A');await expect(page.getByLabel('파일 내용')).toHaveValue('hello');
 await page.goForward();await expect(title(page)).toContainText('서버B');
 expect(errors).toEqual([]);
});

test('Back closes the create-server dialog and keeps the page',async({page})=>{
 await mock(page);await page.goto('/');await page.goto('about:blank');await page.goBack();await expect(title(page)).toContainText('서버A');
 const before=page.url();
 await page.getByRole('button',{name:/서버 만들기/}).first().click();await expect(page.locator('.modal-backdrop')).toBeVisible();
 await page.goBack();await expect(page.locator('.modal-backdrop')).toHaveCount(0);expect(page.url()).toBe(before);await expect(title(page)).toContainText('서버A');
 // closing with the UI button must not leave a dead entry behind
 await page.getByRole('button',{name:/서버 만들기/}).first().click();await expect(page.locator('.modal-backdrop')).toBeVisible();
 await page.locator('.modal-backdrop').getByRole('button',{name:/취소|닫기/}).first().click();await expect(page.locator('.modal-backdrop')).toHaveCount(0);
 await page.waitForTimeout(150);await page.goBack();await expect(page).toHaveURL('about:blank');
});

test('runners tab keeps its hash and Back returns to the previous tab',async({page})=>{
 await mock(page);await page.goto('/');await expect(title(page)).toContainText('서버A');
 await page.getByRole('button',{name:/서버 실행기/}).click();await expect(page).toHaveURL(/#runners$/);
 await page.goBack();await expect(page).not.toHaveURL(/#runners/);await expect(title(page)).toContainText('서버A');
 await page.goForward();await expect(page).toHaveURL(/#runners$/);
});
