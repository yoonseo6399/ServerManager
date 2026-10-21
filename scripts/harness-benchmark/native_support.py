"""OS isolation and evidence conversion for the upstream-native-tools track.
Native tool names/inputs/results remain in raw files. Instrument-specific
requirements that cannot be inferred reliably are marked for manual audit.
"""
import json,os,pathlib,time

def isolated(command,out,repo,source,python,node,pi_package,hermes_source,hermes_python,opencode):
 out=out.resolve();repo=repo.resolve()
 for name in ['runtime-home','tmp']:(out/name).mkdir(exist_ok=True)
 reads=[out,repo,source,pathlib.Path(pi_package).resolve(),pathlib.Path(hermes_source).resolve(),pathlib.Path(opencode).resolve().parent,
        pathlib.Path('/private/tmp/sm-hermes-venv'),pathlib.Path('/private/tmp/sm-hermes-python'),pathlib.Path('/private/tmp/sm-hermes-uv-cache'),
        pathlib.Path('/Users/mini/.cache/codex-runtimes/codex-primary-runtime/dependencies')]
 exceptions=''.join('(require-not (subpath '+json.dumps(str(x))+'))' for x in reads)
 policy='(version 1)(allow default)(deny network*)'+'(allow network-outbound (remote ip "localhost:18084")(remote ip "localhost:18085")(remote ip "localhost:18086"))(allow network-inbound (local ip "localhost:18086"))(allow network-bind (local ip "localhost:18086"))'
 policy+=''.join('(deny file-read-data (require-all (subpath '+json.dumps(x)+')'+exceptions+'))' for x in ['/Users','/private/tmp','/private/var/folders','/Volumes','/opt'])
 policy+='(deny file-write* (require-not (require-any (subpath '+json.dumps(str(out))+')(literal "/dev/null"))))(deny file-write* (subpath '+json.dumps(str(repo))+'))'
 profile=out/'sandbox.sb';profile.write_text(policy)
 env={'HOME':str(out/'runtime-home'),'TMPDIR':str(out/'tmp')+'/', 'PATH':':'.join([str(pathlib.Path(python).parent),str(pathlib.Path(node).parent),'/Applications/ChatGPT.app/Contents/Resources/codex-cli/codex-path','/usr/bin','/bin','/usr/sbin','/sbin']),
      'LANG':'en_US.UTF-8','PYTHONNOUSERSITE':'1','PYTHONDONTWRITEBYTECODE':'1','TERM':'dumb','NO_COLOR':'1','TERMINAL_ENV':'local','TERMINAL_CWD':str(out/'project'),'SHELL':'/bin/bash'}
 if os.environ.get('SM_BENCH_PI_KEEP_RECENT'):env['SM_BENCH_PI_KEEP_RECENT']=os.environ['SM_BENCH_PI_KEEP_RECENT']
 return ['/usr/bin/sandbox-exec','-f',str(profile),*command],env

def events(out,framework,adapter,home):
 raw=[]
 if framework=='pi':
  p=out/'native-events.jsonl';starts={}
  for r in [json.loads(x) for x in p.read_text().splitlines()] if p.exists() else []:
   if r['type']=='tool_execution_start':starts[r['toolCallId']]=r.get('args',{})
   if r['type']=='tool_execution_end':r['args']=starts.get(r['toolCallId'],{});raw.append({'tool':r.get('toolName'),'arguments':r.get('args',{}),'ok':not r.get('isError',False),'result':r.get('result'),'at':r['at']})
 elif framework=='hermes':
  p=out/'native-events.jsonl'
  for r in [json.loads(x) for x in p.read_text().splitlines()] if p.exists() else []:
   if r['kind']=='end':
    call,name,args,result=r['args'];raw.append({'tool':name,'arguments':args,'ok':True,'result':result,'at':r['at']})
 else:
  for m in adapter.get('messages',[]):
   for p in m.get('parts',[]):
    if p.get('type')=='tool':
     s=p.get('state',{});raw.append({'tool':p['tool'],'arguments':s.get('input',{}),'ok':s.get('status')=='completed','result':s.get('output',s.get('error')),'at':s.get('time',{}).get('start')})
 (out/'native-tools.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in raw))
 rows=[]
 for r in raw:
  original=r['tool'];a=r['arguments'];r={**r,'native_tool':original}
  if original in ['read','read_file']:
   p=a.get('path',a.get('filePath',''))
   try:p=str(pathlib.Path(p).relative_to(home))
   except ValueError:pass
   r['tool']='bench_read_file';r['arguments']={**a,'path':p}
  elif original in ['bash','shell','terminal']:r['tool']='bench_run_command'
  rows.append(r)
 return rows

def grade(oracle,home,case,before,answer,tools,phase_checks,repo,python):
 result=oracle(home,case,before,answer,tools,phase_checks,repo,python)
 manual=[]
 # A single native shell can execute/read/verify/delete; do not fabricate seven-tool events.
 for k in {'plan_execute':['reread_outputs'],'merge_contract':['verified_before_deleting'],'python_script':['model_executed']}.get(case,[]):
  result['checks'].pop(k,None);manual.append(k)
 if case=='repo_structure':
  # Symbol/location and read evidence need source inspection; common oracle has a narrow dispatcher allowlist.
  manual.extend(['all_nine_source_locations_and_read_evidence'])
 result['manual_audit_required']=manual
 result['passed']=all(v is True for k,v in result['checks'].items() if k!='classification')
 result['pass_is_provisional']=bool(manual)
 return result
