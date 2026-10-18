#!/usr/bin/env python3
"""Opt-in temporary-fixture Qwen test with raw memory samples; no production deployment.
Usage: python3 scripts/test-agent-command-live.py 8192 [30b] [native]
Requires local Ollama, JDK 21 and cached Gradle dependencies. Does not change Metal limits.
"""
import json
import hashlib
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

window = int(sys.argv[1]) if len(sys.argv) > 1 else 8192
model = sys.argv[2] if len(sys.argv) > 2 else '30b'
mode = sys.argv[3] if len(sys.argv) > 3 else 'native'
if mode != 'native':
    raise SystemExit('This command probe tests the product official-tools path only.')
if window != 8192 or model not in ('14b','30b','qwen3-coder-30b'):
    raise SystemExit('This command probe is scoped to 8K and installed Qwen models.')
root = Path(__file__).resolve().parent.parent
out = root / 'build/bot-followup-evidence'
out.mkdir(parents=True, exist_ok=True)

def loaded():
    with urllib.request.urlopen('http://127.0.0.1:11434/api/ps', timeout=3) as response:
        return json.load(response)

if loaded().get('models'):
    raise SystemExit('A model is already loaded; leave existing inference untouched and retry later.')

def sample():
    def command(*args):
        return subprocess.check_output(args, text=True, timeout=3).strip()
    pressure = int(command('/usr/sbin/sysctl', '-n', 'kern.memorystatus_vm_pressure_level'))
    return dict(time=time.time(), pressure=pressure,
                swap=command('/usr/sbin/sysctl', 'vm.swapusage'),
                vm_stat=command('/usr/bin/vm_stat'), models=loaded())

first = sample()
if first['pressure'] != 1:
    raise SystemExit('Memory pressure is not normal; do not start the model.')
# Never replace prior benchmark evidence, including failed attempts.
previous = [p for p in out.iterdir() if p.is_file() and
            any(p.name.startswith(prefix) for prefix in ('live-', 'memory-', 'actor-', 'wire-', 'fixture-', 'run-')) and
            f'{mode}-{model}-{window}' in p.name]
previous += [p for p in out.iterdir() if p.is_file() and p.name in (f'command-actor-{model}.jsonl',f'command-wire-{model}.jsonl')]
if previous:
    archive = out / 'history' / str(time.time_ns())
    archive.mkdir(parents=True)
    for path in previous:
        path.rename(archive / path.name)
env = os.environ.copy()
env.update(PROJECT_AGENT_COMMAND_LIVE='true', PROJECT_AGENT_LIVE_MODEL=model,
           PROJECT_AGENT_BETA11_WINDOW=str(window), PROJECT_AGENT_NATIVE_TOOLS=str(mode == 'native').lower())
env.setdefault('JAVA_HOME', '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home')
cmd = ['./gradlew', 'test', '--tests', 'local.servermanager.ProjectAgentCommandLiveTest',
       '--offline', '--console=plain', '--no-daemon']
started = time.monotonic()
started_wall = time.time()
source_hashes = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in sorted((root / 'src').rglob('*.kt'))}
reason = None
with (out / f'live-{mode}-{model}-{window}.log').open('w') as log, (out / f'memory-{mode}-{model}-{window}.jsonl').open('w') as memory:
    memory.write(json.dumps(first) + '\n'); memory.flush()
    process = subprocess.Popen(cmd, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        while process.poll() is None:
            current = sample()
            memory.write(json.dumps(current) + '\n'); memory.flush()
            if current['pressure'] >= 4 or time.monotonic()-started > 360:
                reason = 'critical pressure' if current['pressure'] >= 4 else '6-minute watchdog'
                os.killpg(process.pid, signal.SIGTERM)
                break
            time.sleep(2)
        try:
            code = process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL); code = process.wait()
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=10)
        if reason:
            # Only the reviewed test model; never stop the Ollama service or other models.
            try:
                request = urllib.request.Request('http://127.0.0.1:11434/api/generate',
                    data=json.dumps(dict(model={'14b':'qwen3:14b','30b':'qwen3:30b','qwen3-coder-30b':'qwen3-coder:30b'}[model], keep_alive=0)).encode(),
                    headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=5) as response:
                    response.read()
            except Exception as error:
                reason += '; unload could not be confirmed: ' + type(error).__name__
        memory.write(json.dumps(sample()) + '\n')
xml = root / 'build/test-results/test/TEST-local.servermanager.ProjectAgentCommandLiveTest.xml'
if xml.exists() and xml.stat().st_mtime >= started_wall:
    shutil.copyfile(xml, out / f'live-{mode}-{model}-{window}.xml')
summary = dict(mode=mode, model=model, window=window, exit_code=code, stopped_for=reason,
               seconds=time.monotonic()-started, artifacts=str(out), source_sha256=source_hashes)
(out / f'run-{mode}-{model}-{window}.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print(json.dumps({key:value for key,value in summary.items() if key != "source_sha256"}, ensure_ascii=False))
raise SystemExit(code if code >= 0 else 1)
