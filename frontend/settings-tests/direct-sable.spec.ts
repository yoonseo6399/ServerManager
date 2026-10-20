import {test,expect} from '@playwright/test';
test('direct UDP opt in saves separately from common DNS and legacy route; port protocols and live status are clear',async({page})=>{
 let cfg:any={enabled:false,relayRunnerId:null,candidateRunnerId:null,securePort:6400,agentHost:'relay.example.com',publicHost:'relay.example.com',graceSeconds:600,retiring:{},relayHosts:{},sableUdpEnabled:false};
 let revision=1,capable=false,blockers=['Game PC'];const writes:any[]=[],otherWrites:string[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/direct-relay'){
   if(route.request().method()==='PUT'){const req=route.request().postDataJSON();writes.push(req);cfg=req.config;revision++;}
   body={revision,config:cfg,phase:cfg.enabled?'ACTIVE':'OFF',message:'상태',gamePort:6700,sableUdpBlockers:blockers,relays:[{runnerId:'mini',name:'Mini',connected:true,capable:true,consentAllowed:true,active:cfg.enabled,ready:cfg.enabled,streams:1,impactIfCut:'1',sableUdpCapable:capable,sableUdp:cfg.sableUdpEnabled?{state:'CONNECTED',connections:1,dropped:0,message:'Sable UDP 왕복 통신을 확인했습니다.'}:null,router:[{label:'게임',protocol:'TCP',externalPort:6700,internalAddress:'192.168.0.2',internalPort:6700},...(cfg.sableUdpEnabled?[{label:'Sable',protocol:'UDP',externalPort:6700,internalAddress:'192.168.0.2',internalPort:6700}]:[]),{label:'실행기',protocol:'TCP',externalPort:6400,internalAddress:'192.168.0.2',internalPort:6400}],diagnostics:{}}],agents:[]};
  }else if(p==='/api/admin/game-network'){
   if(route.request().method()!=='GET')otherWrites.push(p);
   body={settings:{revision:4,tokenConfigured:false,config:{enabled:true,relayRunnerId:null,port:6700,sableUdpEnabled:false,baseDomain:'server.example.com',targetHost:'relay.example.com',defaultInstanceId:null,defaultHostname:'',labels:{},cloudflareEnabled:false,zoneId:''}},dnsMessage:'DNS 대기'};
  }
  await route.fulfill({json:body});
 });
 await page.goto('/#network');
 const common=page.getByRole('region',{name:'공통 접속 주소·포트·DNS'});
 await expect(common.getByLabel('공유 게임 포트',{exact:true})).toBeVisible();
 const legacy=common.getByLabel('기존 방식 TCP 중계 컴퓨터 (관리 Mac 경유)',{exact:true});
 await expect(legacy).toBeHidden();
 await page.getByLabel('최초 중계 실행기').selectOption('mini');
 const udp=page.getByLabel('직접 중계에 Sable UDP 추가',{exact:true});await expect(udp).toBeDisabled();
 capable=true;blockers=[];await expect(udp).toBeEnabled();await udp.check();await page.getByLabel('직접 중계 사용',{exact:true}).check();
 await page.getByRole('button',{name:'변경 사항 저장',exact:true}).click();
 await expect(page.getByText('Sable UDP: CONNECTED',{exact:false})).toContainText('확인된 접속 1개');
 await expect(page.getByRole('row').filter({has:page.getByRole('cell',{name:'UDP',exact:true})})).toContainText('6700');
 expect(writes).toHaveLength(1);expect(writes[0].config).toMatchObject({relayRunnerId:'mini',sableUdpEnabled:true,enabled:true,securePort:6400});expect(otherWrites).toEqual([]);
 await expect(page.getByText('중계↔게임 실행기 구간은 암호화된 TCP 터널을 사용합니다.',{exact:false})).toBeVisible();
 await common.getByText('기존 관리 Mac 경유 중계 · 고급 설정',{exact:true}).click();await expect(legacy).toBeVisible();
 await expect(common.getByLabel('Sable UDP 중계 사용',{exact:true})).not.toBeChecked();
 await page.screenshot({path:'test-results/direct-sable-settings.png',fullPage:true});
 // A lost capability must not prevent switching UDP back off.
 capable=false;blockers=['Game PC'];await expect(page.getByText('직접 UDP 업데이트·연결 확인 필요:',{exact:false})).toBeVisible();await expect(udp).toBeEnabled();await udp.uncheck();
 await page.getByRole('button',{name:'변경 사항 저장',exact:true}).click();expect(writes[1].config.sableUdpEnabled).toBe(false);
});
