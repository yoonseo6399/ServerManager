#!/usr/bin/env python3
"""Build a universal Intel + Apple Silicon .pkg, with verified Temurin 21 JREs."""
import sys,os,re
import concurrent.futures, hashlib, json, pathlib, plistlib, shutil, subprocess, tarfile, urllib.request
from runtime_security import download_runtime, build_trust_tool, harden_runtime
ROOT=pathlib.Path(__file__).resolve().parent.parent
VERSION=(ROOT/'VERSION').read_text().strip()
if not re.fullmatch(r'[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}(?:-beta\.[1-9][0-9]{0,5})?',VERSION): raise ValueError('Invalid version')
MAC_VERSION=VERSION.split('-')[0]
STAGE=ROOT/'build/macos-package'; CACHE=ROOT/'.runtime-cache'; DIST=ROOT/'dist'
CACHE.mkdir(exist_ok=True); DIST.mkdir(exist_ok=True)
def runtime(arch):
    archive,version,digest=download_runtime(arch,'mac')
    return arch,archive,version,digest

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: runtimes=list(pool.map(runtime,['x64','aarch64']))
if '--download-only' in sys.argv:
    print('Verified Java runtimes:', ', '.join(x[0]+' '+x[2] for x in runtimes))
    raise SystemExit(0)
if STAGE.exists(): shutil.rmtree(STAGE)
app=STAGE/'root/Applications/Server Manager.app'; contents=app/'Contents'; resources=contents/'Resources'
(contents/'MacOS').mkdir(parents=True); resources.mkdir()
shutil.copy2(ROOT/'packaging/macos/ServerManager',contents/'MacOS/ServerManager')
shutil.copy2(ROOT/f'build/libs/server-manager-{VERSION}.jar',resources/'server-manager.jar')
shutil.copy2(ROOT/'config/application.properties.example',resources/'application.properties.example')
metadata=[]
trust_tool=build_trust_tool()
for arch,archive,version,digest in runtimes:
    unpack=STAGE/f'unpack-{arch}';unpack.mkdir()
    with tarfile.open(archive) as tar:
        for item in tar.getmembers():
            if item.name.startswith('/') or '..' in pathlib.PurePosixPath(item.name).parts: raise RuntimeError('Unsafe runtime archive')
        tar.extractall(unpack,filter='data')
    bundle=next(p for p in unpack.iterdir() if p.is_dir())
    target=resources/'runtimes'/arch; target.parent.mkdir(exist_ok=True);shutil.move(str(bundle),target)
    security=harden_runtime(target/'Contents/Home',trust_tool)
    metadata.append({'architecture':arch,'java':version,'sha256':digest,'source':'Eclipse Temurin / Adoptium',**security,'cacertsSha256':hashlib.sha256((target/'Contents/Home/lib/security/cacerts').read_bytes()).hexdigest()})
with (contents/'Info.plist').open('wb') as f:
    plistlib.dump({'CFBundleName':'Server Manager','CFBundleDisplayName':'Server Manager','CFBundleIdentifier':'local.servermanager','CFBundleVersion':MAC_VERSION,'CFBundleShortVersionString':MAC_VERSION,'CFBundleExecutable':'ServerManager','CFBundlePackageType':'APPL','LSMinimumSystemVersion':'11.0','NSHighResolutionCapable':True},f)
(resources/'RUNTIMES.json').write_text(json.dumps(metadata,indent=2)+'\n')
shutil.copy2(ROOT/'INSTALL.md',resources/'INSTALL.md')
shutil.copy2(ROOT/'packaging/macos/choose-profile.applescript',resources/'choose-profile.applescript')
shutil.copy2(ROOT/'src/main/resources/update-public-key.der',resources/'update-public-key.der')
(resources/'version.txt').write_text(VERSION+'\n')
java_home=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())
classes=STAGE/'bootstrap-classes';classes.mkdir()
subprocess.run([str(java_home/'bin/javac'),'--release','21','-d',str(classes),str(ROOT/'packaging/bootstrap/Bootstrap.java')],check=True)
subprocess.run([str(java_home/'bin/jar'),'--create','--file',str(resources/'bootstrap.jar'),'--main-class','Bootstrap','-C',str(classes),'.'],check=True)

component=STAGE/'component.pkg'
component_rules=STAGE/'components.plist'
with component_rules.open('wb') as f: plistlib.dump([{'RootRelativeBundlePath':'Applications/Server Manager.app','BundleIsRelocatable':False,'BundleHasStrictIdentifier':True,'BundleOverwriteAction':'upgrade'}],f)
subprocess.run(['/usr/bin/pkgbuild','--root',str(STAGE/'root'),'--identifier','local.servermanager','--version',MAC_VERSION,'--install-location','/','--component-plist',str(component_rules),'--scripts',str(ROOT/'packaging/macos/installer-scripts'),str(component)],check=True)
output=DIST/f'ServerManager-{VERSION}-universal.pkg'
subprocess.run(['/usr/bin/productbuild','--package',str(component),str(output)],check=True)
(DIST/'SHA256SUMS').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n')
print(output)
