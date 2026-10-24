import {test,expect} from '@playwright/test';
test('two voice servers on one relay computer receive distinct public and private UDP ports',async({page})=>{
 let config:any={enabled:true,relayRunnerId:'mini',securePort:6400,agentHost:'relay.example.com',publicHost:'relay.example.com',graceSeconds:600,retiring:{},relayHosts:{},voicechat:[]};let revision=1;const saved:any[]=[];
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances'||p==='/api/runners')body=[];
  else if(p==='/api/admin/direct-relay'){
   if(route.request().method()==='PUT'){config=route.request().postDataJSON().config;saved.push(config);revision++;}
   body={config,revision,phase:'ACTIVE',message:'fixture',gamePort:6700,voicechatServers:['A','B'].map(id=>({instanceId:id,runnerId:'mini',name:id,capable:true})),relays:[{runnerId:'mini',name:'Mini',connected:true,capable:true,consentAllowed:true,active:true,ready:true,streams:0,sableUdpCapable:true,voicechatCapable:true,router:config.voicechat.map((v:any)=>({label:`음성 · ${v.instanceId}`,externalPort:v.publicPort,internalPort:v.publicPort,protocol:'UDP',internalAddress:'192.168.0.2'})),diagnostics:{}}],agents:[]};
  }
  await route.fulfill({json:body});
 });
 await page.goto('/#network');
 await page.getByLabel('A Simple Voice Chat 중계',{exact:true}).check();await page.getByLabel('B Simple Voice Chat 중계',{exact:true}).check();
 for(const [id,publicPort,localPort] of [['A','24454','34454'],['B','24455','34455']]){
  await expect(page.getByLabel(`${id} 공개 음성 UDP 포트`,{exact:true})).toHaveValue(publicPort);
  await expect(page.getByLabel(`${id} 내부 음성 UDP 포트`,{exact:true})).toHaveValue(localPort);
 }
 await page.getByRole('region',{name:'직접 중계 변경 사항'}).getByRole('button',{name:'변경 사항 저장',exact:true}).click();
 expect(saved[0].voicechat).toEqual([{instanceId:'A',publicPort:24454,localPort:34454},{instanceId:'B',publicPort:24455,localPort:34455}]);
 await page.reload();await expect(page.getByLabel('B 내부 음성 UDP 포트',{exact:true})).toHaveValue('34455');
 await expect(page.getByRole('row').filter({has:page.getByRole('cell',{name:'음성 · B',exact:true})})).toContainText('24455');
});
test('assigning a second server to the same computer displays the automatically allocated game port',async({page})=>{
 const config:any={id:'b',name:'Server B',loader:'NeoForge',minecraft:'1.21.1',loaderVersion:'21.1.219',port:25565,memoryMb:512,javaPath:'/java',runnerId:'two'};
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;let body:any={};
  if(p==='/api/auth/session')body={user:{id:'owner',name:'Owner',owner:true,permissions:{}},csrf:'x',origin:url.origin,setupRequired:false};
  else if(p==='/api/instances')body=[{config,state:'STOPPED',pid:null,resources:null,directory:'fixture',eulaAccepted:true}];
  else if(p==='/api/runners')body=['one','two'].map(id=>({id,name:id,online:true,enabled:true,version:'0.7.0',platform:'MAC',allowedInstanceIds:['b']}));
  else if(p==='/api/instances/b/runner'){config.runnerId=route.request().postDataJSON().runnerId;config.port=25566;body={config};}
  else if(p==='/api/game-addresses')body={enabled:true,labels:{},hosts:{}};
  else if(p==='/api/memory')body={totalMb:24576,freeMb:10000,reservedMb:2000,maxHeapMb:22576,committedHeapMb:0,startableHeapMb:22576};
  else if(p.includes('events'))body={events:[]};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByLabel('실행 위치',{exact:true}).selectOption('one');
 await expect(page.getByRole('status').filter({hasText:'내부 게임 포트: 25566'})).toBeVisible();
 await expect(page.getByText('한 실행기에 여러 서버를 배정할 수 있습니다.',{exact:false})).toBeVisible();
 await expect(page.getByText('공개 게임 주소·포트와 포워딩 대상은 공통 접속·직접 중계 설정에서 확인하세요.',{exact:false})).toBeVisible();
});
