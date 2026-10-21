"""Standard stdio MCP transport for the common instruments, used by OpenCode."""
import json,sys,urllib.request
url=sys.argv[1]
def call(path,value=None):
 data=None if value is None else json.dumps(value).encode()
 return json.load(urllib.request.urlopen(urllib.request.Request(url+path,data=data,headers={'Content-Type':'application/json'}),timeout=40))
for line in sys.stdin:
 q=json.loads(line);i=q.get('id');method=q.get('method')
 if i is None:continue
 if method=='initialize':r={'protocolVersion':'2024-11-05','capabilities':{'tools':{}},'serverInfo':{'name':'shared-benchmark-instruments','version':'1'}}
 elif method=='tools/list':r={'tools':[{'name':d['name'],'description':d['description'],'inputSchema':d['parameters']} for d in call('/tools')]}
 elif method=='tools/call':
  v=call('/call',{'name':q['params']['name'],'arguments':q['params'].get('arguments',{})});r={'content':[{'type':'text','text':json.dumps(v,ensure_ascii=False)}],'isError':'error' in v}
 elif method=='ping':r={}
 else:print(json.dumps({'jsonrpc':'2.0','id':i,'error':{'code':-32601,'message':'unsupported method'}}),flush=True);continue
 print(json.dumps({'jsonrpc':'2.0','id':i,'result':r}),flush=True)
