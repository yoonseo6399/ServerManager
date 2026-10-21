import json,os,pathlib,sys,urllib.request
spec=json.loads(pathlib.Path(sys.argv[1]).read_text());home=pathlib.Path(spec['output'])/'hermes';home.mkdir(exist_ok=True);os.environ['HERMES_HOME']=str(home);os.environ['HERMES_IGNORE_RULES']='1'
config={'model':{'default':spec['model'],'provider':'custom','base_url':spec['api'],'context_length':spec['window']},'compression':{'enabled':True,'threshold':0.85,'threshold_tokens':None,'target_ratio':0.2,'protect_first_n':0,'protect_last_n':2,'max_attempts':2,'in_place':True},'auxiliary':{'compression':{'provider':'custom','model':spec['model'],'base_url':spec['api'],'api_key':'ollama','context_length':spec['window'],'timeout':180,'reasoning_effort':'none'},'title_generation':{'enabled':False,'model_upgrade_enabled':False}},'skills':{'auto_load':[]},'plugins':[]}
config['tools']={'tool_search':{'enabled':'off'}}  # Official eager-schema mode, no source modifications.
(home/'config.yaml').write_text(json.dumps(config))
sys.path.insert(0,spec['hermes_source'])
from run_agent import AIAgent
from tools.registry import registry
from toolsets import create_custom_toolset
def request(url,value=None):
 data=None if value is None else json.dumps(value).encode()
 return json.load(urllib.request.urlopen(urllib.request.Request(url,data=data,headers={'Content-Type':'application/json'}),timeout=40))
definitions=request(spec['tools']+'/tools')
for d in definitions:
 def handler(args,name=d['name']):return json.dumps(request(spec['tools']+'/call',{'name':name,'arguments':args}),ensure_ascii=False)
 registry.register(name=d['name'],toolset='benchmark',schema=d,handler=handler)
create_custom_toolset('benchmark','Controlled shared benchmark instruments',[d['name'] for d in definitions])
agent=AIAgent(base_url=spec['api'],api_key='ollama',provider='custom',api_mode='chat_completions',model=spec['model'],enabled_toolsets=['benchmark'],max_iterations=32,max_tokens=2048,reasoning_config={'enabled':False},quiet_mode=True,skip_context_files=True,load_soul_identity=False,skip_memory=True,skip_background_review=True,cwd=spec['home'],run_budget_seconds=600,request_overrides={'reasoning_effort':'none'})
assert agent.valid_tool_names=={d['name'] for d in definitions},agent.valid_tool_names
result={'framework':'hermes','answers':[],'compression':[],'errors':[],'systemChars':len(agent._build_system_prompt(spec['system']))};history=[]
def save():(pathlib.Path(spec['output'])/'adapter.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str))
try:
 for prompt in spec['prompts']:
  if prompt=='__COMPACT__':
   before=json.dumps(history,ensure_ascii=False);history,rendered=agent._compress_context(history,spec['system'],force=True,focus_topic='Preserve current human goals, exact identifiers, corrections, constraints and unfinished work. Do not invent facts.');after=json.dumps(history,ensure_ascii=False);result['compression'].append({'changed':before!=after,'beforeChars':len(before),'afterChars':len(after),'history':history});(pathlib.Path(spec['output'])/'before-compaction.json').write_text(before);(pathlib.Path(spec['output'])/'after-compaction.json').write_text(after)
  else:
   response=agent.run_conversation(prompt,system_message=spec['system'],conversation_history=history);history=response['messages'];result['answers'].append(response.get('final_response',''));result.setdefault('turn_errors',[]).append(response.get('error'));request(spec['control']+'/phase',{})
  save()
 result['messages']=history
except Exception as e:
 result['errors'].append(repr(e));raise
finally:agent.close();save()
