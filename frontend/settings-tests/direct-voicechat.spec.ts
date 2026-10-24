import {test,expect} from '@playwright/test';
test('voice UDP ports save separately, require upgraded hosts and show forwarding without editing files',async({page})=>{
 let cfg:any={enabled:true,relayRunnerId:'mini',candidateRunnerId:null,securePort:6400,agentHost:'relay.example.com',publicHost:'relay.example.com',graceSeconds:600,retiring:{},relayHosts:{},sableUdpEnabled:false,voicechat:[]};
 let revision=1,capable=false;const writes:any[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={appVersion:'fixture',user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/direct-relay'){
   if(route.request().method()==='PUT'){writes.push(route.request().postDataJSON());cfg=writes.at(-1).config;revision++;}
   body={revision,config:cfg,phase:'ACTIVE',message:'직접 중계',gamePort:6700,sableUdpBlockers:[],voicechatServers:[{instanceId:'a',name:'야생!',capable,localStatus:cfg.voicechat.length?{state:'NEEDS_RESTART',message:'다음 정상 서버 시작 때 반영',connections:0,dropped:0}:null}],relays:[{runnerId:'mini',name:'Mini',connected:true,capable:true,consentAllowed:true,active:true,ready:true,streams:1,impactIfCut:'1',sableUdpCapable:true,voicechatCapable:capable,voicechat:cfg.voicechat.length?{a:{state:'LISTENING',connections:0,dropped:0,message:'UDP 수신 대기'}}:{},router:[{label:'게임',protocol:'TCP',externalPort:6700,internalAddress:'192.168.0.2',internalPort:6700},...cfg.voicechat.map((v:any)=>({label:'음성 · 야생!',protocol:'UDP',externalPort:v.publicPort,internalAddress:'192.168.0.2',internalPort:v.publicPort}))],diagnostics:{}}],agents:[]};
  }else if(p==='/api/admin/game-network')body={settings:{revision:1,tokenConfigured:false,config:{enabled:true,relayRunnerId:null,port:6700,sableUdpEnabled:false,baseDomain:'server.example.com',targetHost:'relay.example.com',defaultInstanceId:null,defaultHostname:'',labels:{},cloudflareEnabled:false,zoneId:''}},dnsMessage:''};
  await route.fulfill({json:body});
 });
 await page.goto('/#network');const voice=page.getByLabel('야생! Simple Voice Chat 중계',{exact:true});await expect(voice).toBeDisabled();
 capable=true;await expect(voice).toBeEnabled();await voice.check();await expect(page.getByLabel('야생! 공개 음성 UDP 포트',{exact:true})).toHaveValue('24454');
 await page.getByLabel('야생! 공개 음성 UDP 포트',{exact:true}).fill('24455');await page.getByLabel('야생! 내부 음성 UDP 포트',{exact:true}).fill('24456');
 await page.getByRole('region',{name:'직접 중계 변경 사항'}).getByRole('button',{name:'변경 사항 저장',exact:true}).click();
 expect(writes).toHaveLength(1);expect(writes[0].config.voicechat).toEqual([{instanceId:'a',publicPort:24455,localPort:24456}]);expect(writes[0].config.sableUdpEnabled).toBe(false);
 await expect(page.getByText('relay.example.com:24455',{exact:true})).toBeVisible();await expect(page.getByRole('row').filter({has:page.getByRole('cell',{name:'음성 · 야생!',exact:true})})).toContainText('24455');
 await expect(page.getByText('설정 적용: NEEDS_RESTART',{exact:false})).toBeVisible();
 capable=false;await expect(voice).toBeEnabled();await voice.uncheck();await page.getByRole('region',{name:'직접 중계 변경 사항'}).getByRole('button',{name:'변경 사항 저장',exact:true}).click();expect(writes[1].config.voicechat).toEqual([]);
});
