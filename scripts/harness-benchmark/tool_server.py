"""Fixed benchmark instruments shared by the three upstream agent loops. Not a harness."""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import argparse,fnmatch,json,os,pathlib,secrets,subprocess,threading,time
S={'type':'string'}
def obj(props,required=()):return {'type':'object','properties':props,'required':list(required),'additionalProperties':False}
DEFINITIONS=[
 {'name':'bench_list_files','description':'List files/directories relative to project home; recursive=true lists files recursively, glob optionally filters names. No file contents.','parameters':obj({'path':S,'recursive':{'type':'boolean'},'glob':S})},
 {'name':'bench_read_file','description':'Read UTF-8 original contents, with line range if requested. Newlines are preserved in the text field. Binary detection uses actual bytes, not extension. Large output is explicitly partial.','parameters':obj({'path':S,'start_line':{'type':'integer','minimum':1},'lines':{'type':'integer','minimum':1,'maximum':160}},['path'])},
 {'name':'bench_edit_file','description':'Replace one exact occurrence in an existing file while preserving all unrelated bytes. Read the current file first.','parameters':obj({'path':S,'find':S,'replace':S},['path','find','replace'])},
 {'name':'bench_write_file','description':'Create a new UTF-8 file, preserving the exact supplied text/newlines. Existing files require edit_file.','parameters':obj({'path':S,'content':S},['path','content'])},
 {'name':'bench_delete_path','description':'Delete one regular file inside the project. Repository snapshot is read only.','parameters':obj({'path':S},['path'])},
 {'name':'bench_search','description':'Find literal text in source files, returns path:line:source. glob filters paths. Bounded original matches, no summaries.','parameters':obj({'text':S,'path':S,'glob':S},['text'])},
 {'name':'bench_run_command','description':'Execute Python directly in the project (no shell). args is the argument array, e.g. ["python3","stars.py","--rows","4"] or ["python3","-c","..."] for checks. Returns actual exit/stdout/stderr. No network or home files.','parameters':obj({'args':{'type':'array','items':S,'minItems':2,'maxItems':30}},['args'])}
]

def safe_python(home,repo,python,args,timeout=25):
 allowed=['/System','/usr','/Library','/dev','/private/var/db/dyld','/private/preboot','/private/etc','/Users/mini/.cache/codex-runtimes/codex-primary-runtime/dependencies/python']
 exceptions=''.join('(require-not (subpath '+json.dumps(x)+'))' for x in [str(home),str(repo),'/Users/mini/.cache/codex-runtimes/codex-primary-runtime/dependencies/python'])
 profile='(version 1)(allow default)(deny network*)'+''.join('(deny file-read-data (require-all (subpath '+json.dumps(x)+')'+exceptions+'))' for x in ['/Users','/private/tmp','/private/var/folders','/Volumes','/opt'])+'(deny file-write* (require-not (require-any (subpath '+json.dumps(str(home))+')(literal "/dev/null"))))(deny file-write* (subpath '+json.dumps(str(repo))+'))'
 env={'PATH':'/usr/bin:/bin','PYTHONDONTWRITEBYTECODE':'1','PYTHONNOUSERSITE':'1','TMPDIR':str(home),'LANG':'en_US.UTF-8'}
 return subprocess.run(['/usr/bin/sandbox-exec','-p',profile,python,'-B',*args],cwd=home,env=env,capture_output=True,timeout=timeout)

class Instruments:
 def __init__(self,home,repo,python,log):self.home=home.resolve();self.repo=repo.resolve();self.python=python;self.log=log;self.reads={};self.lock=threading.RLock();self.count=0
 def target(self,name,write=False):
  raw=pathlib.Path(name)
  if raw.is_absolute() or '..' in raw.parts:raise ValueError('use project-relative path without ..')
  if raw.parts and raw.parts[0]=='repo':
   if write:raise ValueError('repository snapshot is read only')
   p=(self.repo/pathlib.Path(*raw.parts[1:])).resolve()
   if p!=self.repo and self.repo not in p.parents:raise ValueError('outside snapshot denied')
   return p
  p=self.home/raw
  for parent in [p,*p.parents]:
   if parent==self.home:break
   if parent.is_symlink():raise ValueError('symlink denied')
  p=p.resolve()
  if p!=self.home and self.home not in p.parents:raise ValueError('outside project denied')
  return p
 def logical(self,p):
  return 'repo/'+str(p.relative_to(self.repo)) if p==self.repo or self.repo in p.parents else str(p.relative_to(self.home))
 def call(self,name,a):
  with self.lock:
   self.count+=1;row={'index':self.count,'tool':name,'arguments':a,'at':time.time()};result=None
   try:
    if self.count>64:raise ValueError('64 tool-call case limit')
    if name=='bench_list_files':
     p=self.target(a.get('path','.'));it=p.rglob('*') if a.get('recursive') else p.iterdir();items=[]
     for f in sorted(it):
      if f.is_symlink():continue
      rel=self.logical(f)
      if not a.get('glob') or fnmatch.fnmatch(rel,a['glob']) or fnmatch.fnmatch(f.name,a['glob']):items.append(rel+('/' if f.is_dir() else ''))
      if len(items)>=350:break
     result={'entries':items,'partial':len(items)>=350}
    elif name=='bench_read_file':
     p=self.target(a['path']);data=p.read_bytes();self.reads[str(p)]=data
     try:
      text=data.decode('utf8');binary='\x00' in text
     except UnicodeDecodeError:text=None;binary=True
     if binary:result={'path':a['path'],'binary':True,'bytes':len(data),'preview_hex':data[:32].hex()}
     else:
      lines=text.splitlines(keepends=True);start=max(0,a.get('start_line',1)-1);end=start+a.get('lines',120);selected=''.join(lines[start:end]);partial=start>0 or end<len(lines) or len(selected)>12000
      result={'path':a['path'],'binary':False,'text':selected[:12000],'start_line':start+1,'total_lines':len(lines),'partial':partial,'bytes':len(data),'newline':'CRLF' if b'\r\n' in data else 'LF'}
    elif name=='bench_edit_file':
     p=self.target(a['path'],True);data=p.read_bytes();find=a['find'].encode();replace=a['replace'].encode()
     if self.reads.get(str(p))!=data:raise ValueError('read current file before editing')
     if not find or data.count(find)!=1:raise ValueError('find must match exactly once')
     p.write_bytes(data.replace(find,replace));result={'saved':a['path'],'verify':'reread to verify'}
    elif name=='bench_write_file':
     p=self.target(a['path'],True)
     if p.exists():raise ValueError('existing file: read and edit instead')
     p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(a['content'].encode());result={'saved':a['path'],'verify':'reread to verify'}
    elif name=='bench_delete_path':
     p=self.target(a['path'],True)
     if not p.is_file():raise ValueError('regular files only')
     p.unlink();result={'deleted':a['path']}
    elif name=='bench_search':
     root=self.target(a.get('path','.'));matches=[]
     for f in sorted(root.rglob('*')):
      if not f.is_file() or f.is_symlink():continue
      rel=self.logical(f)
      if a.get('glob') and not (fnmatch.fnmatch(rel,a['glob']) or fnmatch.fnmatch(f.name,a['glob'])):continue
      try:lines=f.read_text().splitlines()
      except (UnicodeError,OSError):continue
      for n,line in enumerate(lines,1):
       if a['text'] in line:matches.append(f'{rel}:{n}:{line[:500]}')
       if len(matches)>=60:break
      if len(matches)>=60:break
     result={'matches':matches,'partial':len(matches)>=60}
    elif name=='bench_run_command':
     args=a['args'];assert args[0] in ('python','python3'), 'Python only, no shell'
     r=safe_python(self.home,self.repo,self.python,args[1:])
     result={'exit_code':r.returncode,'stdout':r.stdout.decode(errors='replace')[:14000],'stderr':r.stderr.decode(errors='replace')[:4000]}
    else:raise ValueError('unknown benchmark tool')
    row['ok']=True
   except Exception as e:result={'error':str(e)};row['ok']=False
   row['result']=result
   with self.log.open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
   return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--home',required=True);p.add_argument('--repo',required=True);p.add_argument('--python',required=True);p.add_argument('--log',required=True);p.add_argument('--port',type=int,default=18085);a=p.parse_args();inst=Instruments(pathlib.Path(a.home),pathlib.Path(a.repo),a.python,pathlib.Path(a.log))
 class Handler(BaseHTTPRequestHandler):
  def log_message(self,*a):pass
  def do_GET(self):self.send(DEFINITIONS if self.path=='/tools' else {'ready':True})
  def do_POST(self):
   q=json.loads(self.rfile.read(int(self.headers['Content-Length'])));self.send(inst.call(q['name'],q.get('arguments',{})))
  def send(self,x):
   data=json.dumps(x,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 ThreadingHTTPServer(('127.0.0.1',a.port),Handler).serve_forever()
if __name__=='__main__':main()
