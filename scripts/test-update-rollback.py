#!/usr/bin/env python3
"""Actual manager/runner supervisor processes; disposable signed fixtures, never the installed app."""
import os,pathlib,subprocess,tempfile,time,hashlib,shutil

ROOT=pathlib.Path(__file__).resolve().parent.parent
JAVA=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())/'bin'
SOURCE=r'''import java.nio.file.*;
public class RollbackFixture {
 public static void main(String[] args)throws Exception {
  boolean runner=System.getenv("RUNNER_PACKAGE_HOME")!=null;
  Path home=Path.of(System.getenv(runner?"SERVER_MANAGER_RUNNER_HOME":"SERVER_MANAGER_HOME"));
  String v=new String(RollbackFixture.class.getResourceAsStream("/fixture-version.txt").readAllBytes()).trim();
  if(Files.exists(home.resolve("fail-version"))&&Files.readString(home.resolve("fail-version")).trim().equals(v))System.exit(1);
  Files.writeString(home.resolve("started-version"),v);
  Files.writeString(home.resolve("protocol"),System.getenv(runner?"RUNNER_BOOTSTRAP_PROTOCOL":"SERVER_MANAGER_BOOTSTRAP_PROTOCOL"));
  Files.writeString(Path.of(System.getenv(runner?"SERVER_MANAGER_RUNNER_READY_FILE":"SERVER_MANAGER_READY_FILE")),"ready");
  Thread.sleep(4100);
  if(Files.exists(home.resolve("updates/pending.txt")))System.exit(75);
  Thread.sleep(120000);
 }
}'''

def wait(check,timeout=25):
 deadline=time.time()+timeout
 while time.time()<deadline:
  if check():return
  time.sleep(.15)
 raise RuntimeError('Supervisor fixture timed out')

with tempfile.TemporaryDirectory(prefix='sm-rollback-test-') as folder:
 root=pathlib.Path(folder);classes=root/'classes';classes.mkdir()
 (root/'RollbackFixture.java').write_text(SOURCE)
 subprocess.run([str(JAVA/'javac'),'--release','21','-d',str(classes),str(root/'RollbackFixture.java'),str(ROOT/'packaging/bootstrap/Bootstrap.java'),str(ROOT/'packaging/runner/RunnerBootstrap.java')],check=True)
 keys=root/'keys';keys.mkdir();public=root/'public.der'
 subprocess.run([str(JAVA/'java'),str(ROOT/'packaging/bootstrap/Keygen.java'),str(keys),str(public)],check=True,stdout=subprocess.DEVNULL)
 def jar(path,version):
  (classes/'fixture-version.txt').write_text(version)
  subprocess.run([str(JAVA/'jar'),'--create','--file',str(path),'--main-class','RollbackFixture','-C',str(classes),'RollbackFixture.class','-C',str(classes),'fixture-version.txt'],check=True)
 def package(runner):
  app=root/('runner' if runner else 'Manager.app');resources=app if runner else app/'Contents/Resources';resources.mkdir(parents=True)
  jar(resources/'server-manager.jar','0.6.2');(resources/'version.txt').write_text('0.6.2');shutil.copy2(public,resources/'update-public-key.der');(resources/'application.properties.example').write_text('server.port=8787\n')
  return app,resources
 def staged(home,target='0.6.1',approve=True):
  home.mkdir();updates=home/'updates';directory=updates/target;directory.mkdir(parents=True);payload=directory/f'server-manager-{target}.jar';jar(payload,target)
  manifest=directory/'update.properties';manifest.write_text(f'protocol=1\nversion={target}\nfile={payload.name}\nsize={payload.stat().st_size}\nsha256={hashlib.sha256(payload.read_bytes()).hexdigest()}\n')
  subprocess.run([str(JAVA/'java'),str(ROOT/'packaging/bootstrap/SignRelease.java'),str(keys/'private.der'),str(manifest),str(directory/'update.sig')],check=True,stdout=subprocess.DEVNULL)
  (updates/'pending.txt').write_text(target)
  if approve:(updates/'rollback-approved.txt').write_text(target)
  (home/'identity-preserved.txt').write_text('original identity')
  return payload
 def launch(home,app,runner):
  env=os.environ.copy()
  for n in ['RUNNER_PACKAGE_HOME','SERVER_MANAGER_RUNNER_HOME','SERVER_MANAGER_DATA']:env.pop(n,None)
  if runner:env.update(RUNNER_PACKAGE_HOME=str(app),SERVER_MANAGER_RUNNER_HOME=str(home))
  else:env.update(SERVER_MANAGER_APP_PATH=str(app),SERVER_MANAGER_HOME=str(home))
  return subprocess.Popen([str(JAVA/'java'),'-cp',str(classes),'RunnerBootstrap' if runner else 'Bootstrap',*([] if runner else ['--service'])],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 def stop(proc):
  if proc.poll() is None:proc.terminate()
  proc.wait(timeout=15)
 def started(home,version):return (home/'started-version').exists() and (home/'started-version').read_text()==version
 for runner in [False,True]:
  name='runner' if runner else 'manager';app,resources=package(runner)
  home=root/(name+'-approved');payload=staged(home);proc=launch(home,app,runner)
  try:
   wait(lambda:started(home,'0.6.1'));assert (home/'updates/active.txt').read_text()=='0.6.1';assert (home/'protocol').read_text()=='2';assert not (home/'updates/rollback-approved.txt').exists()
  finally:stop(proc)
  (home/'started-version').unlink();proc=launch(home,app,runner)
  try:wait(lambda:started(home,'0.6.1'))
  finally:stop(proc)
  payload.write_bytes(b'tampered');(home/'started-version').unlink();proc=launch(home,app,runner)
  try:wait(lambda:started(home,'0.6.2'));assert (home/'update-error.txt').exists()
  finally:stop(proc)
  home=root/(name+'-unapproved');staged(home,approve=False);proc=launch(home,app,runner)
  try:
   wait(lambda:started(home,'0.6.2'));time.sleep(5);assert not (home/'updates/active.txt').exists();assert started(home,'0.6.2')
  finally:stop(proc)
  home=root/(name+'-failed');staged(home);(home/'fail-version').write_text('0.6.1');proc=launch(home,app,runner)
  try:
   wait(lambda:(home/'failed-update-version.txt').exists());wait(lambda:started(home,'0.6.2'));assert (home/'failed-update-version.txt').read_text()=='0.6.1';assert (home/'identity-preserved.txt').read_text()=='original identity'
  finally:stop(proc)
  print(name+': approved downgrade below bundled version, restart persistence, tamper rejection, unapproved downgrade refusal and failed-start recovery passed',flush=True)
