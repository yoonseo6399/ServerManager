from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--server',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--test',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parent;server=a.server.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
create=server/'mods/create-1.21.1-6.0.10.jar';assert create.is_file()
libs=[server/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar',create]+sorted((server/'libraries').rglob('*.jar'))
# MixinExtras is supplied by NeoForge; use that exact embedded distribution for compilation.
neo=server/'libraries/net/neoforged/neoforge/21.1.264/neoforge-21.1.264-universal.jar'
with zipfile.ZipFile(neo) as z:
    names=[s for s in z.namelist() if 'mixinextras' in s.lower() and s.endswith('.jar')]
    assert len(names)==1,names
    mixinextras=out/'mixinextras-compile.jar';mixinextras.write_bytes(z.read(names[0]));libs.append(mixinextras)
classes=out/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir()
cp=os.pathsep.join(str(p) for p in libs)
args=[str(a.java_home/'bin/javac'),'--release','21','-proc:none','-cp',cp,'-d',str(classes)]+[str(p) for p in (root/'src').rglob('*.java')]
subprocess.run(args,check=True)
for source in (root/'resources').rglob('*'):
    if source.is_file():dest=classes/source.relative_to(root/'resources');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
jar=out/'survival-schematic-safety-1.0.0.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
    for source in sorted(classes.rglob('*')):
        if source.is_file():z.write(source,source.relative_to(classes))
result={'artifact':str(jar),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'createSha256':hashlib.sha256(create.read_bytes()).hexdigest(),'unitTests':'not run'}
if a.test:
    tests=out/'test-classes';tests.mkdir(exist_ok=True)
    subprocess.run([str(a.java_home/'bin/javac'),'--release','21','-proc:none','-cp',str(classes),'-d',str(tests)]+[str(p) for p in (root/'test').rglob('*.java')],check=True)
    subprocess.run([str(a.java_home/'bin/java'),'-cp',os.pathsep.join([str(classes),str(tests)]),'local.survival.schematicsafety.SchematicFailureGuardTest'],check=True)
    result['unitTests']='pass'
(out/'build-result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
