import {test,expect} from '@playwright/test';
const config=()=>({enabled:false,relayRunnerId:null as string|null,candidateRunnerId:null,securePort:6400,agentHost:'192.168.45.110',publicHost:'relay.example.com',graceSeconds:600,retiring:{},relayHosts:{}});
const relay=(id:string,connected:boolean,capable:boolean,allowed:boolean)=>({runnerId:id,name:id,connected,capable,consentAllowed:allowed,active:false,ready:false,streams:0,impactIfCut:'0',router:[{label:'플레이어',externalPort:6700,internalAddress:null,internalPort:6700}],diagnostics:{consent:{status:capable?'OK':'UNKNOWN',detail:'상태 보고'}}});

test('registered pending and offline runners remain visible; first selection is saved and activation does not call prepare',async({page})=>{
 let stored=config(),revision=1;const saved:any[]=[],actions:string[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/direct-relay'){
   if(route.request().method()==='PUT'){const req=route.request().postDataJSON();saved.push(req);stored=req.config;revision++;}
   body={revision,config:stored,phase:stored.enabled?'WAITING':'OFF',message:'상태 보고 대기',gamePort:6700,relays:[relay('Mac Mini',true,true,true),relay('Pending',true,false,false),relay('Offline',false,false,false),relay('Denied',true,true,false)],agents:[]};
  } else if(p.startsWith('/api/admin/direct-relay/'))actions.push(p);
  await route.fulfill({json:body});
 });
 await page.goto('/#network');
 const first=page.getByRole('combobox',{name:'최초 중계 실행기'});
 await expect(first).toBeVisible();await expect(first.getByRole('option',{name:'Pending · 상태 보고 대기 / 업데이트 확인'})).toHaveAttribute('disabled','');
 await expect(first.getByRole('option',{name:'Offline · 오프라인'})).toHaveAttribute('disabled','');await expect(first.getByRole('option',{name:'Denied · 로컬 허용 필요'})).toHaveAttribute('disabled','');
 await expect(page.getByLabel('공유 게임 접속 포트')).toContainText('6700');await expect(page.getByLabel('실행기 연결 포트',{exact:true})).toHaveValue('6400');
 await page.getByLabel('직접 중계 사용',{exact:true}).check();await expect(page.getByRole('button',{name:'변경 사항 저장',exact:true})).toBeDisabled();
 await first.selectOption('Mac Mini');await page.waitForTimeout(2200);await expect(first).toHaveValue('Mac Mini');
 await page.screenshot({path:'test-results/relay-first-selection.png',fullPage:true});
 await page.getByRole('button',{name:'변경 사항 저장',exact:true}).click();
 await expect(page.getByText('현재 중계 실행기:',{exact:false})).toContainText('Mac Mini');await expect(first).toHaveCount(0);
 expect(saved).toHaveLength(1);expect(saved[0].revision).toBe(1);expect(saved[0].config).toMatchObject({enabled:true,relayRunnerId:'Mac Mini',securePort:6400});expect(actions).toEqual([]);
 await page.reload();await expect(page.getByText('현재 중계 실행기:',{exact:false})).toContainText('Mac Mini');
});

test('revoked consent during initial draft disables activation without losing chosen target',async({page})=>{
 let allowed=true,writes=0;
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/direct-relay'){if(route.request().method()==='PUT')writes++;body={revision:1,config:config(),phase:'OFF',message:'꺼짐',gamePort:6700,relays:[relay('Mac Mini',true,true,allowed)],agents:[]};}
  await route.fulfill({json:body});
 });
 await page.goto('/#network');await page.getByLabel('최초 중계 실행기').selectOption('Mac Mini');await page.getByLabel('직접 중계 사용',{exact:true}).check();
 await expect(page.getByRole('button',{name:'변경 사항 저장',exact:true})).toBeEnabled();allowed=false;
 await expect(page.getByRole('button',{name:'변경 사항 저장',exact:true})).toBeDisabled();expect(writes).toBe(0);
 await expect(page.getByLabel('최초 중계 실행기')).toHaveValue('Mac Mini');
});

test('a late old poll cannot erase a successfully saved first relay',async({page})=>{
 let stored=config(),revision=1,hold=false,delayed:any=null;
 const view=()=>({revision,config:stored,phase:stored.enabled?'WAITING':'OFF',message:'상태',gamePort:6700,relays:[relay('Mac Mini',true,true,true)],agents:[]});
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/direct-relay'){
   if(route.request().method()==='PUT'){stored=route.request().postDataJSON().config;revision++;}
   else if(hold){hold=false;delayed={route,body:JSON.parse(JSON.stringify(view()))};return;}
   body=view();
  }
  await route.fulfill({json:body});
 });
 await page.goto('/#network');await page.getByLabel('최초 중계 실행기').selectOption('Mac Mini');await page.getByLabel('직접 중계 사용',{exact:true}).check();
 hold=true;await expect.poll(()=>!!delayed).toBe(true);
 await page.getByRole('button',{name:'변경 사항 저장',exact:true}).click();await expect(page.getByText('현재 중계 실행기:',{exact:false})).toContainText('Mac Mini');
 await delayed.route.fulfill({json:delayed.body});await page.waitForTimeout(100);
 await expect(page.getByText('현재 중계 실행기:',{exact:false})).toContainText('Mac Mini');await expect(page.getByLabel('직접 중계 사용',{exact:true})).toBeChecked();
 await expect(page.getByLabel('최초 중계 실행기')).toHaveCount(0);
});
