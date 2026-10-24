#!/usr/bin/env python3
import os,pathlib,subprocess,tempfile,time,urllib.request,json,socket,hashlib
root=pathlib.Path(__file__).resolve().parent.parent
app=root/'build/macos-package/root/Applications/Server Manager.app'
with socket.socket() as sock:
    sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
with tempfile.TemporaryDirectory(prefix='server-manager-launch-') as directory:
    home=pathlib.Path(directory);env=os.environ.copy();env.update(SERVER_MANAGER_HOME=str(home),SERVER_MANAGER_DATA=str(home/'data'))
    proc=subprocess.Popen([str(app/'Contents/MacOS/ServerManager'),'--service',f'--server.port={port}',f'--server-manager.public-origin=http://localhost:{port}'],env=env)
    try:
        for _ in range(60):
            if proc.poll() is not None: raise RuntimeError('Packaged launcher stopped: '+(home/'logs/app.log').read_text()[-3000:])
            try:
                with urllib.request.urlopen(f'http://localhost:{port}/api/auth/session',timeout=2) as response: session=json.load(response)
                assert session['setupRequired'] and session['origin']==f'http://localhost:{port}'
                assert (home/'data/security/setup-code.txt').exists()
                assert (home/'config/application.properties').exists()
                print('Packaged launcher passed: bundled Java, isolated data/config, authenticated web bootstrap')
                break
            except (OSError,TimeoutError):time.sleep(1)
        else:raise RuntimeError('Packaged launcher timed out')
    finally:
        proc.terminate()
        try:proc.wait(timeout=30)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
        # Resident hosts intentionally outlive their controller. An isolated test
        # must remove its own transient job before deleting the temporary home.
        host_home=home/'data/game-host'
        label='local.servermanager.game-host.'+hashlib.sha256(str(host_home.resolve()).encode()).hexdigest()[:24]
        query=subprocess.run(['/bin/launchctl','list',label],capture_output=True)
        if query.returncode==0:
            cleanup=subprocess.run(['/bin/launchctl','remove',label],capture_output=True)
            if cleanup.returncode!=0:
                raise RuntimeError('Could not remove isolated game-host job')
        elif query.returncode!=113:
            raise RuntimeError('Could not query isolated game-host job')
