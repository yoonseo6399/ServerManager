import json,os,pathlib,subprocess,sys,time,urllib.request
spec=json.loads(pathlib.Path(sys.argv[1]).read_text());root=pathlib.Path(spec['output'])/'opencode';root.mkdir(exist_ok=True);port=18086;api=f'http://127.0.0.1:{port}'
config={'$schema':'https://opencode.ai/config.json','model':'local/'+spec['model'],'small_model':'local/'+spec['model'],'enabled_providers':['local'],'autoupdate':False,'share':'disabled','lsp':False,'formatter':False,'permission':{'*':'deny',**{k:'allow' for k in ['read','edit','write','glob','grep','list','shell','bash']},'external_directory':{spec['repo']+'/**':'allow'}},'agent':{'benchmark':{'description':'Controlled benchmark','mode':'primary','temperature':0,'steps':32}},'provider':{'local':{'npm':'@ai-sdk/openai-compatible','name':'Local only','options':{'baseURL':spec['api']},'models':{spec['model']:{'name':spec['model'],'limit':{'context':spec['window'],'output':2048},'options':{'temperature':0}}}}},'compaction':{'auto':True,'prune':False,'reserved':2048}}
(root/'config.json').write_text(json.dumps(config));env=os.environ.copy();env.update({'XDG_CONFIG_HOME':str(root/'config'),'XDG_DATA_HOME':str(root/'data'),'XDG_CACHE_HOME':str(root/'cache'),'OPENCODE_CONFIG':str(root/'config.json'),'OPENCODE_CONFIG_DIR':str(root/'config-dir'),'OPENCODE_DISABLE_AUTOUPDATE':'true','OPENCODE_DISABLE_DEFAULT_PLUGINS':'true','OPENCODE_DISABLE_CLAUDE_CODE':'true','OPENCODE_DISABLE_MODELS_FETCH':'true','OPENCODE_DISABLE_LSP_DOWNLOAD':'true'})
def request(method,path,value=None,timeout=360):
 data=None if value is None else json.dumps(value).encode()
 return json.load(urllib.request.urlopen(urllib.request.Request(api+path,data=data,method=method,headers={'Content-Type':'application/json'}),timeout=timeout))
def phase():urllib.request.urlopen(urllib.request.Request(spec['control']+'/phase',data=b'{}'),timeout=5).close()
log=(root/'server.log').open('w');server=subprocess.Popen([spec['opencode'],'--pure','serve','--hostname','127.0.0.1','--port',str(port)],cwd=spec['home'],env=env,stdout=log,stderr=subprocess.STDOUT)
result={'framework':'opencode','answers':[],'compression':[],'errors':[]};session=None
def save():(pathlib.Path(spec['output'])/'adapter.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
try:
 for _ in range(150):
  try:request('GET','/global/health',timeout=1);break
  except Exception:
   if server.poll() is not None:raise RuntimeError('OpenCode server exited')
   time.sleep(.2)
 else:raise TimeoutError('OpenCode startup')
 session=request('POST','/session',{'title':'OSS benchmark '+spec['case']})['id'];model={'providerID':'local','modelID':spec['model']}
 for prompt in spec['prompts']:
  if prompt=='__COMPACT__':
   before=request('GET',f'/session/{session}/message');request('POST',f'/session/{session}/summarize',model);after=request('GET',f'/session/{session}/message');comp=[p for m in after for p in m['parts'] if p['type']=='compaction'];result['compression'].append({'changed':bool(comp),'beforeMessages':len(before),'afterMessages':len(after),'compactions':comp});(pathlib.Path(spec['output'])/'before-compaction.json').write_text(json.dumps(before,ensure_ascii=False));(pathlib.Path(spec['output'])/'after-compaction.json').write_text(json.dumps(after,ensure_ascii=False))
  else:
   r=request('POST',f'/session/{session}/message',{'model':model,'agent':'benchmark','system':spec['system'],'parts':[{'type':'text','text':prompt}]});result['answers'].append('\n'.join(p['text'] for p in r['parts'] if p['type']=='text'));result.setdefault('turn_errors',[]).append(r.get('info',{}).get('error'));phase()
  result['messages']=request('GET',f'/session/{session}/message');save()
 result['messages']=request('GET',f'/session/{session}/message')
except Exception as e:result['errors'].append(repr(e));raise
finally:
 if session:
  try:request('POST',f'/session/{session}/abort',{},timeout=3)
  except Exception:pass
 server.terminate()
 try:server.wait(timeout=5)
 except subprocess.TimeoutExpired:server.kill();server.wait()
 log.close();save()
