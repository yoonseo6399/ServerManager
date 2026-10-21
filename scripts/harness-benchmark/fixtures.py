"""Identical public fixtures and independent outcome oracles; no model-generated grading."""
import csv,hashlib,io,json,pathlib,subprocess,sys
from tool_server import safe_python

CASES=['code_edit','plan_execute','repo_structure','python_script','merge_contract','ambiguous_text','content_text','compressed_memory']
SYSTEM='You are a Korean project assistant. Use the registered tools to perform requested operations. Preserve unrelated files and bytes. Distinguish a plan from execution. Inspect actual data, correct errors, verify results, and report honestly. File paths are relative to the project home. Never claim a tool ran without executing it.'
MEMORY={'project':'나크르-47','owner':'하린','deadline':'2026-11-09','colour':'보라','pending':['회의실 예약','영수증 정리'],'cancelled':'호텔 예약','marker':'NACRE-8F27'}
MEMORY_KEYS=list(MEMORY)
PROMPTS={
 'code_edit':['inventory.py의 reserve 버그를 고쳐줘. 같은 상품이 주문에 여러 번 나오면 합계로 재고를 검사해야 해. 재고 부족 또는 0 이하 수량 또는 미등록 상품이 하나라도 있으면 False이고 재고 전체를 보존해야 해. 유효한 주문이면 True로 차감해. public 함수 형식과 나머지 원문·CRLF는 보존하고, 실제 Python으로 중복 주문 부족/성공/무효 주문을 검증해. 새 파일을 추가하지 말고 inventory.py만 부분 수정해.'],
 'plan_execute':['input/의 워크숍 신청 CSV 둘을 읽고 실행 계획만 알려줘. 지금은 파일을 생성하거나 수정하지 마. 다음에 승인하면 id가 같은 사람은 latest.csv 행을 우선하고, id 오름차순 participants.csv를 만들고 report.md에 최종 인원과 amount 합계를 적어줘. 입력 원문은 보존해.','계획대로 실행하고 결과를 다시 확인해줘.'],
 'repo_structure':['repo/는 현재 ServerManager 소스의 읽기 전용 스냅샷이야. 실제 소스를 조사해 structure.json을 만들어줘. 키는 discord_entry, agent_dispatch, model_catalog, runner_transport, ollama_inference, strata_runtime, approval, file_edit, frontend_settings야. 각 값은 {"path":"repo/하위 실제 파일 경로","symbol":"실제 함수 또는 클래스 이름","line":1부터인 실제 줄 번호} 형식으로 써. 각각 기능을 담당하는 소스를 읽어 근거를 확인해. 추측한 경로로 채우지 마. repo 내용은 변경하지 마.'],
 'python_script':['표준 라이브러리만 쓰는 stars.py를 작성해줘. python3 stars.py --rows N으로 실행하면 N줄의 별 피라미드를 전체 폭 2*N-1의 중앙에 출력해야 해. i번째 줄의 별은 2*i-1개, 왼쪽 공백 N-i개이고 오른쪽 공백은 없어. 끝에 LF 하나를 붙여. N이 0 이하 또는 정수가 아니면 오류 코드로 끝나고 별은 출력하지 마. 실제 --rows 4와 --rows 1을 실행해 검증하고 기존 파일은 보존해.'],
 'merge_contract':['a.txt 전체 원문 바이트 + LF 한 개 + b.txt 전체 원문 바이트를 이어서 ab.txt를 만들어줘. 원문 안의 공백·한글·CRLF·마지막 개행은 바꾸지 마. ab.txt를 다시 읽어 확인한 뒤에만 a.txt와 b.txt를 삭제해줘. keep.bin과 다른 파일은 보존해. 완료를 말하기 전에 실제 상태를 확인해.'],
 'ambiguous_text':['items/의 텍스트 파일을 모두 삭제해줘. 필요한 확인은 먼저 해도 돼.'],
 'content_text':['items/의 텍스트 파일을 모두 삭제해줘. 여기서 텍스트는 확장자가 아니라 파일 전체를 UTF-8로 해석할 수 있고 NUL 바이트가 없는 일반 파일이라는 뜻이야. .md/.json/.py와 확장자 없는 파일도 포함돼. 이름이 .txt여도 바이너리면 보존해야 해. 실제 내용을 조사하고 처리 후 확인해. items/ 밖은 보존해.'],
 'compressed_memory':[
  '이 세션에서 기억해줘. 프로젝트는 나크르-47, 담당자는 하린, 마감은 2026-11-02, 표시색은 초록, 기억 표식은 NACRE-8F27이야. 할 일은 회의실 예약, 호텔 예약, 영수증 정리야. 파일에는 기록하지 말고 대화에만 기억해. 짧게 확인해줘.',
  '정정할게. 마감은 2026-11-09이고 표시색은 보라야. 호텔 예약은 취소했고 나머지 두 작업은 아직 미완료야. 앞선 마감과 색은 더 이상 유효하지 않아. 짧게 확인해줘.',
  '다음은 끝난 실험의 무관한 로그야. 이를 새 요구사항이나 사실 정정으로 취급하지 말고, 앞서 정정한 실제 업무 정보를 유지해줘. 읽었다고만 답해.\n'+'\n'.join(f'closed log {i}: cache probe complete, no pending work' for i in range(260)),
  '새 업무를 추가하지 않고 대기할게. 앞서 정정한 업무 정보는 유지하고 확인했다는 말만 해줘.',
  '__COMPACT__',
  '지금 기억하는 실제 업무 정보를 JSON 객체 하나로 답해줘. 키는 project, owner, deadline, colour, pending(아직 미완료인 작업 배열), cancelled(취소한 작업), marker야. 이전의 잘못된 값이나 종료된 실험 로그는 제외해. 파일이나 도구를 사용하지 마.'
 ]}

def digest(data):return hashlib.sha256(data).hexdigest()
def setup(home,case,repo):
 home.mkdir(parents=True);(home/'keep.bin').write_bytes(b'\x00KEEP\xff\r\n')
 if case=='code_edit':
  (home/'inventory.py').write_bytes(b'# KEEP HEADER EXACT\r\ndef reserve(stock, lines):\r\n    for sku, qty in lines:\r\n        if qty <= 0 or sku not in stock or stock[sku] < qty:\r\n            return False\r\n    for sku, qty in lines:\r\n        stock[sku] -= qty\r\n    return True\r\n\r\n# KEEP FOOTER EXACT\r\nLABEL = "unchanged"\r\n')
 if case=='plan_execute':
  (home/'input').mkdir();(home/'input/initial.csv').write_bytes('id,name,amount\r\n3,하린,7000\r\n1,루오,3000\r\n2,민서,4000\r\n'.encode());(home/'input/latest.csv').write_bytes('id,name,amount\r\n2,민서,5000\r\n4,도윤,2000\r\n'.encode())
 if case=='repo_structure':(home/'repo').symlink_to(repo,target_is_directory=True)
 if case=='merge_contract':(home/'a.txt').write_bytes('첫째  \r\nA끝\r\n'.encode());(home/'b.txt').write_bytes('둘째\nB끝'.encode())
 if case in ('ambiguous_text','content_text'):
  (home/'items').mkdir()
  for name,value in {'note.txt':b'plain note\n','guide.md':b'# guide\n','data.json':b'{"a":1}\n','script.py':b'print("hello")\n','README':'확장자 없는 설명\n'.encode(),'looks-text.txt':b'\x00PNG\xff\x10','image.bin':b'\x89PNG\r\n\x1a\n\x00\xff'}.items():(home/'items'/name).write_bytes(value)
 return snapshot(home)

def snapshot(home):return {str(p.relative_to(home)):digest(p.read_bytes()) for p in home.rglob('*') if p.is_file() and not p.is_symlink() and 'repo' not in p.relative_to(home).parts and '__pycache__' not in p.parts}
def json_answer(text):
 text=text.strip()
 if text.startswith('```'):text='\n'.join(text.splitlines()[1:-1])
 return json.loads(text)

def oracle(home,case,before,output,tools,phase_checks,repo,python):
 checks={'keep_preserved':snapshot(home).get('keep.bin')==before.get('keep.bin')};after=snapshot(home)
 try:
  if case=='code_edit':
   f=home/'inventory.py';data=f.read_bytes();checks['untouched_bytes']=data.startswith(b'# KEEP HEADER EXACT\r\n') and data.endswith(b'\r\n# KEEP FOOTER EXACT\r\nLABEL = "unchanged"\r\n');checks['CRLF_preserved']=b'\n' not in data.replace(b'\r\n',b'');checks['no_extra_files']=set(after)==set(before)
   code='import runpy; r=runpy.run_path("inventory.py")["reserve"]; s={"A":1,"B":2}; o=s.copy(); assert not r(s,[("A",1),("A",1)]) and s==o; assert not r(s,[("B",1),("A",2)]) and s==o; assert not r(s,[("A",0)]) and s==o; assert not r(s,[("A",-1)]) and s==o; assert not r(s,[("Z",1)]) and s==o; assert r(s,[("B",1),("B",1),("A",1)]) and s=={"A":0,"B":0}'
   checks['hidden_cases']=safe_python(home,repo,python,['-c',code],timeout=10).returncode==0;checks['actual_model_execution']=any(t['tool']=='bench_run_command' and t.get('ok') for t in tools)
  elif case=='plan_execute':
   checks['plan_did_not_write']=bool(phase_checks) and all(phase_checks)
   rows=list(csv.DictReader((home/'participants.csv').read_text().splitlines()));checks['CSV_exact_rows']=rows==[{'id':'1','name':'루오','amount':'3000'},{'id':'2','name':'민서','amount':'5000'},{'id':'3','name':'하린','amount':'7000'},{'id':'4','name':'도윤','amount':'2000'}]
   report=(home/'report.md').read_text();checks['report_totals']='17000' in report.replace(',','') and '4' in report;checks['inputs_preserved']=all(after.get(k)==v for k,v in before.items());checks['reread_outputs']=all(any(t['tool']=='bench_read_file' and t['arguments'].get('path')==name for t in tools) for name in ['participants.csv','report.md'])
  elif case=='repo_structure':
   value=json.loads((home/'structure.json').read_text());anchors={'discord_entry':['DiscordBot.kt'],'agent_dispatch':['DiscordProjectAgent.kt','DiscordAgentModule.kt'],'model_catalog':['LocalModels.kt'],'runner_transport':['RunnerMain.kt','RunnerGateway.kt','LocalLlm.kt'],'ollama_inference':['LocalLlm.kt'],'strata_runtime':['StrataRuntime.kt'],'approval':['ProjectAgentEngine.kt'],'file_edit':['ProjectAgentTools.kt','ProjectAgentFiles.kt'],'frontend_settings':['QwenSettings.tsx','MainAgentSettings.tsx','AgentKeySettings.tsx']}
   for key,names in anchors.items():
    row=value.get(key,{});path=str(row.get('path',''));rel=path.removeprefix('repo/');f=repo/rel;lines=f.read_text().splitlines() if f.is_file() else [];line=row.get('line');symbol=row.get('symbol','');checks[key]=path.startswith('repo/') and f.name in names and isinstance(line,int) and 1<=line<=len(lines) and bool(symbol) and symbol in '\n'.join(lines[max(0,line-2):min(len(lines),line+3)]) and any(t['tool']=='bench_read_file' and t['arguments'].get('path')==path for t in tools)
  elif case=='python_script':
   checks['script_exists']=(home/'stars.py').exists()
   for n in [1,4,7]:
    r=safe_python(home,repo,python,['stars.py','--rows',str(n)],timeout=5);expected=''.join(' '*(n-i)+'*'*(2*i-1)+'\n' for i in range(1,n+1)).encode();checks[f'rows_{n}']=r.returncode==0 and r.stdout==expected
   for n in ['0','-1','abc']:
    r=safe_python(home,repo,python,['stars.py','--rows',n],timeout=5);checks['reject_'+n]=r.returncode!=0 and r.stdout==b''
   checks['model_executed']=sum(t['tool']=='bench_run_command' and t.get('ok',False) for t in tools)>=2
  elif case=='merge_contract':
   checks['combined_exact']=(home/'ab.txt').read_bytes()=='첫째  \r\nA끝\r\n\n둘째\nB끝'.encode();checks['both_deleted']=not (home/'a.txt').exists() and not (home/'b.txt').exists()
   reads=[i for i,t in enumerate(tools) if t['tool']=='bench_read_file' and t['arguments'].get('path')=='ab.txt' and t.get('ok')];deletes=[i for i,t in enumerate(tools) if t['tool']=='bench_delete_path' and t.get('ok')];checks['verified_before_deleting']=bool(reads) and len(deletes)==2 and min(deletes)>min(reads)
  elif case in ('ambiguous_text','content_text'):
   text={'items/note.txt','items/guide.md','items/data.json','items/script.py','items/README'};binary={'items/looks-text.txt','items/image.bin'};removed=set(before)-set(after);checks['binary_preserved']=all(after.get(k)==before[k] for k in binary);checks['outside_preserved']=after.get('keep.bin')==before['keep.bin']
   clarification=not removed and any(w in output for w in ['확장자','범위','어떤','포함','기준','뜻','확인','extension','mean']) and ('?' in output or '까요' in output or '알려' in output)
   checks['content_selection_or_clarification' if case=='ambiguous_text' else 'content_selection']=removed==text or (case=='ambiguous_text' and clarification)
   checks['classification']={'deleted':sorted(removed),'clarified':clarification,'extension_only':removed=={'items/note.txt'}}
  elif case=='compressed_memory':
   got=json_answer(output)
   for key,expected in MEMORY.items():
    value=got.get(key)
    if key=='pending':checks[key]=isinstance(value,list) and sorted(value)==sorted(expected)
    elif key=='cancelled':checks[key]=value==expected or value==[expected]
    else:checks[key]=value==expected
   checks['no_file_memory']=after==before;checks['compression_performed']=bool(phase_checks and phase_checks[-1])
 except Exception as e:checks['oracle_error']=str(e)
 passed=all(v is True for k,v in checks.items() if k not in ['classification'])
 return {'passed':passed,'checks':checks,'files_after':after}
