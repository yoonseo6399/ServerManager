"""Serial real-model comparison. No custom ServerManager agent loop is imported or run."""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import argparse,http.client,json,os,pathlib,re,signal,subprocess,sys,tarfile,threading,time,urllib.request
from fixtures import CASES,PROMPTS,SYSTEM,setup,snapshot,oracle
from tool_server import DEFINITIONS,Instruments
HERE=pathlib.Path(__file__).resolve().parent
def ollama(path,value=None,method=None):
 data=None if value is None else json.dumps(value).encode()
 raw=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:11434'+path,data=data,method=method,headers={'Content-Type':'application/json'}),timeout=50).read()
 return json.loads(raw) if raw else {}
def memory():
 result={}
 for name in ['kern.memorystatus_vm_pressure_level','vm.swapusage','iogpu.wired_limit_mb']:
  try:result[name]=subprocess.check_output(['/usr/sbin/sysctl','-n',name],text=True).strip()
  except Exception:pass
 return result

class State:
 instrument=None;output=None;phases=None;alias=None;window=None
 state=threading.RLock()
STATE=State()
class Control(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):self.send(DEFINITIONS if self.path=='/tools' else {'ready':True})
 def do_POST(self):
  if self.path=='/phase':STATE.phases.append(snapshot(STATE.instrument.home));self.send({'saved':True});return
  q=json.loads(self.rfile.read(int(self.headers['Content-Length'])));self.send(STATE.instrument.call(q['name'],q.get('arguments',{})))
 def send(self,x):
  data=json.dumps(x,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
class Proxy(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  data=json.dumps({'object':'list','data':[{'id':STATE.alias,'object':'model','owned_by':'local'}]}).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_POST(self):
  if self.path!='/v1/chat/completions':self.send_error(403);return
  q=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))));assert q['model']==STATE.alias,(q['model'],STATE.alias)
  q['reasoning_effort']='none';q['temperature']=0;q['max_tokens']=min(int(q.get('max_tokens') or q.get('max_completion_tokens') or 2048),2048);q.pop('max_completion_tokens',None)
  record={'at':time.time(),'model':q['model'],'max_tokens':q['max_tokens'],'messages':len(q.get('messages',[])),'tools':len(q.get('tools',[]))};out=STATE.output;number=len(list(out.glob('request-*.json')))+1
  (out/f'request-{number:03}.json').write_text(json.dumps(q,ensure_ascii=False))
  started=time.monotonic();first=None;metrics=None;usage=None;raw=(out/f'response-{number:03}.jsonl').open('wb');c=http.client.HTTPConnection('127.0.0.1',11434,timeout=240)
  try:
   c.request('POST',self.path,json.dumps(q).encode(),{'Content-Type':'application/json'});r=c.getresponse();record['status']=r.status;self.send_response(r.status);self.send_header('Content-Type',r.getheader('Content-Type','application/json'));self.send_header('Connection','close');self.end_headers()
   while True:
    line=r.readline()
    if not line:break
    raw.write(line)
    if line.startswith(b'data: {'):
     try:
      chunk=json.loads(line[6:]);usage=chunk.get('usage') or usage
      if first is None:
       first=time.monotonic();metrics={'models':ollama('/api/ps'),'memory':memory()}
     except (ValueError,OSError):pass
    self.wfile.write(line);self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError):record['cancelled']=True
  except Exception as e:record['error']=str(e)
  finally:
   record.update({'seconds':time.monotonic()-started,'first_chunk_seconds':None if first is None else first-started,'usage':usage,'host':metrics});raw.close();c.close();self.close_connection=True
   with STATE.state:
    with (out/'inference.jsonl').open('a') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--model',default='qwen3.8:27b-q4_K_M');p.add_argument('--window',type=int,default=8192);p.add_argument('--frameworks',default='hermes,pi,opencode');p.add_argument('--cases',default=','.join(CASES));p.add_argument('--python',default='/Users/mini/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3');p.add_argument('--node',default='/Users/mini/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node');p.add_argument('--hermes-python',default='/private/tmp/sm-hermes-venv/bin/python');p.add_argument('--hermes-source',default='/private/tmp/sm-hermes-source');p.add_argument('--opencode',default='/private/tmp/sm-harness-opencode-compare/opencode');p.add_argument('--pi-package',default='/private/tmp/sm-harness-pi-compare/node_modules');p.add_argument('--repo-ref',default='v0.7.0-beta.16');p.add_argument('--timeout',type=int,default=660);a=p.parse_args()
 root=pathlib.Path(a.output).resolve();root.mkdir(parents=True,exist_ok=True);repo=root/'repo-snapshot';repo.mkdir(exist_ok=True)
 if not any(repo.iterdir()):
  archive=root/'repo.tar';subprocess.run(['git','archive','--format=tar','-o',str(archive),a.repo_ref,'src/main','frontend/src','docs/architecture','build.gradle.kts','settings.gradle.kts','VERSION'],check=True)
  with tarfile.open(archive) as tf:tf.extractall(repo)
 ref=subprocess.check_output(['git','rev-parse',a.repo_ref],text=True).strip();alias='sm-oss-bench-'+re.sub('[^a-z0-9-]','',a.model.lower())+'-'+str(int(time.time()))+':latest';assert not ollama('/api/ps')['models'],'Another model is loaded; do not interrupt it.'
 ollama('/api/create',{'model':alias,'from':a.model,'parameters':{'num_ctx':a.window,'temperature':0,'num_predict':2048},'stream':False});STATE.alias=alias;STATE.window=a.window
 metadata={'model_source':a.model,'alias':alias,'window':a.window,'repo':ref,'thinking':'none','temperature':0,'output_cap':2048,'baseline_memory':memory(),'frameworks':a.frameworks.split(','),'cases':a.cases.split(','),'custom_harness_included':False}
 (root/'metadata.json').write_text(json.dumps(metadata,indent=2));servers=[ThreadingHTTPServer(('127.0.0.1',18085),Control),ThreadingHTTPServer(('127.0.0.1',18084),Proxy)]
 for server in servers:threading.Thread(target=server.serve_forever,daemon=True).start()
 results=[]
 try:
  for case in a.cases.split(','):
   for framework in a.frameworks.split(','):
    out=root/framework/case
    if (out/'result.json').exists():raise RuntimeError('Refusing to overwrite prior run: '+str(out))
    out.mkdir(parents=True);home=out/'project';before=setup(home,case,repo);STATE.output=out;STATE.phases=[];STATE.instrument=Instruments(home,repo,a.python,out/'tools.jsonl')
    spec={'home':str(home),'output':str(out),'case':case,'model':alias,'window':a.window,'api':'http://127.0.0.1:18084/v1','tools':'http://127.0.0.1:18085','control':'http://127.0.0.1:18085','prompts':PROMPTS[case],'system':SYSTEM,'hermes_source':a.hermes_source,'opencode':a.opencode}
    (out/'spec.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2));started=time.monotonic();timed_out=False
    print('START',framework,case,flush=True)
    if framework=='pi':
     package=out/'pi-runner';package.mkdir();(package/'adapter.mjs').write_bytes((HERE/'pi_adapter.mjs').read_bytes());(package/'node_modules').symlink_to(a.pi_package,target_is_directory=True);cmd=[a.node,str(package/'adapter.mjs'),str(out/'spec.json')]
    elif framework=='hermes':cmd=[a.hermes_python,str(HERE/'hermes_adapter.py'),str(out/'spec.json')]
    elif framework=='opencode':cmd=[a.python,str(HERE/'opencode_adapter.py'),str(out/'spec.json')]
    else:raise ValueError(framework)
    with (out/'process.log').open('w') as log:
     proc=subprocess.Popen(cmd,cwd=home,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
     try:exit_code=proc.wait(timeout=a.timeout)
     except subprocess.TimeoutExpired:
      timed_out=True;os.killpg(proc.pid,signal.SIGTERM)
      try:proc.wait(timeout=5)
      except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait()
      exit_code=proc.returncode
    adapter=json.loads((out/'adapter.json').read_text()) if (out/'adapter.json').exists() else {'answers':[],'errors':['adapter output missing'],'compression':[]}
    tools=[json.loads(x) for x in (out/'tools.jsonl').read_text().splitlines()] if (out/'tools.jsonl').exists() else []
    phase_checks=[STATE.phases[0]==before] if case=='plan_execute' and STATE.phases else []
    if case=='compressed_memory':phase_checks=[any(c.get('changed') for c in adapter.get('compression',[]))]
    graded=oracle(home,case,before,adapter.get('answers',[''])[-1] if adapter.get('answers') else '',tools,phase_checks,repo,a.python);graded.update({'framework':framework,'case':case,'seconds':time.monotonic()-started,'exit_code':exit_code,'timed_out':timed_out,'adapter_errors':adapter.get('errors',[]),'tool_calls':len(tools),'compression':adapter.get('compression',[]),'memory_after':memory()})
    graded['passed']=graded['passed'] and exit_code==0 and not timed_out and not graded['adapter_errors'];(out/'result.json').write_text(json.dumps(graded,ensure_ascii=False,indent=2));results.append(graded);(root/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print('RESULT',framework,case,graded['passed'],round(graded['seconds'],2),flush=True)
    ollama('/api/generate',{'model':alias,'keep_alive':0});time.sleep(1)
 finally:
  try:ollama('/api/generate',{'model':alias,'keep_alive':0});ollama('/api/delete',{'model':alias},'DELETE')
  finally:
   for server in servers:server.shutdown();server.server_close()
   metadata['final_memory']=memory();metadata['loaded_models_after']=ollama('/api/ps')['models'];(root/'metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
