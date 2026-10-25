#!/usr/bin/env python3
"""Build self-contained Mac universal and Windows x64 runner installers."""
import hashlib
import json
import os
import pathlib
import plistlib
import shutil
import stat
import subprocess
import sys
import zipfile
from runtime_security import download_runtime, build_trust_tool, harden_runtime, runtime_source

ROOT=pathlib.Path(__file__).resolve().parent.parent
VERSION=(ROOT/'VERSION').read_text().strip()
DIST=ROOT/'dist'
STAGE=ROOT/'build/runner-package'
MAC_RUNTIMES=ROOT/'build/macos-package/root/Applications/Server Manager.app/Contents/Resources/runtimes'
JAVA=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip())

def copy_common(target):
    target.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/f'build/libs/server-manager-{VERSION}.jar',target/'server-manager.jar')
    shutil.copy2(ROOT/'src/main/resources/update-public-key.der',target/'update-public-key.der')
    (target/'version.txt').write_text(VERSION+'\n')
    classes=STAGE/'bootstrap-classes'
    classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(JAVA/'bin/javac'),'--release','21','-d',str(classes),str(ROOT/'packaging/runner/RunnerBootstrap.java')],check=True)
    subprocess.run([str(JAVA/'bin/jar'),'--create','--file',str(target/'runner-bootstrap.jar'),'--main-class','RunnerBootstrap','-C',str(classes),'.'],check=True)
    setup_classes=STAGE/'setup-classes'
    setup_classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(JAVA/'bin/javac'),'--release','21','-encoding','UTF-8','-d',str(setup_classes),str(ROOT/'packaging/runner/RunnerSetup.java')],check=True)
    subprocess.run([str(JAVA/'bin/jar'),'--create','--file',str(target/'runner-setup.jar'),'--main-class','RunnerSetup','-C',str(setup_classes),'.'],check=True)

def guide(folder,gui,platform):
    start=('Server Manager Runner Installer.app' if platform=='mac' else 'Setup.cmd') if gui else ('터미널에서 ./install.command' if platform=='mac' else '터미널에서 install.cmd')
    (folder/'먼저 읽어주세요.txt').write_text(f'''Server Manager Runner {VERSION} · {'UI' if gui else 'CLI'} 설치판

1. ZIP 전체를 풀어주세요. ZIP 안에서 파일 하나만 실행하지 마세요.
2. {start} 실행
3. 관리 웹 HTTPS 주소 확인 → 웹의 서버 실행기 메뉴에서 패스키로 등록 코드 발급
4. 5분 안에 코드를 설치기에 붙여넣고 설치·연결
5. 웹에서 컴퓨터의 온라인 상태 확인 → 서버 사용 허용 → 서버 화면에서 실행 위치 선택

기존 마크 서버가 있다면 UI의 '기존 마크 서버 폴더'를 선택하세요.
웹에서 같은 종류·버전의 서버를 만들고 ID를 복사한 뒤 원래 폴더를 지정합니다.
CLI: install.command / install.cmd --server-id=UUID --server-path="기존 서버 폴더"
JAR 이름이 다르면 --server-jar=paper.jar (Forge/NeoForge는 생략).
웹 파일 관리자의 기존 폴더 가져오기를 누르면 서버 실행 없이 최초 전송합니다.
검사·전송 용량이 표시되며 20GB 같은 대용량은 시간이 걸립니다.
원래 위치에서 실행하며, 이후 정상 종료 시 변경 파일이 관리 서버로 인계됩니다.

Java 포함 / 로그인 자동 대기 선택 가능
일반 게임 실행기는 바깥으로 연결하므로 포트포워딩이 필요하지 않습니다.
직접 중계 실행기로 선택한 컴퓨터는 게임 접속 TCP 포트와 별도 실행기 연결 TCP 포트를 포워딩해야 합니다.
Sable UDP를 켜면 게임 접속 포트와 같은 번호의 UDP 포워딩도 추가합니다. 관리 웹에 정확한 입력값과 상태가 표시됩니다.
Windows x64, Mac Intel·Apple Silicon 지원. 관리자 권한은 요청하지 않습니다.
등록 코드는 기기 등록용 일회용 코드이며 패스키 비밀키를 복사하지 않습니다.
기존 등록·월드·설정은 ~/.servermanager-runner에 유지됩니다.
실행 중인 실행기는 설치기가 강제 종료하지 않습니다. 게임을 정상 종료하고 실행기를 종료한 뒤 재설치하세요.
개발 빌드는 Apple 공증 / Windows Authenticode 서명이 없습니다. 조직 정책이 차단하면 관리자에게 문의하세요.
CLI 도움말: install.command --help / install.cmd --help
''',encoding='utf-8')

def archive(folder,name):
    destination=DIST/name
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
        for path in folder.rglob('*'):
            if path.is_file():
                info=zipfile.ZipInfo(str(path.relative_to(folder.parent)))
                mode=stat.S_IMODE(path.stat().st_mode)
                info.external_attr=(0o755 if path.suffix in ('.command','.sh') or mode&0o111 else 0o644)<<16
                info.compress_type=zipfile.ZIP_DEFLATED
                output.writestr(info,path.read_bytes())
    print(destination)

def mac():
    if not (MAC_RUNTIMES/'x64').exists() or not (MAC_RUNTIMES/'aarch64').exists():
        raise RuntimeError('Run scripts/package-macos.py before packaging the Mac runner')
    folder=STAGE/'ServerManagerRunner-mac'
    copy_common(folder)
    shutil.copytree(MAC_RUNTIMES,folder/'runtimes',dirs_exist_ok=True)
    tool=build_trust_tool()
    metadata=[]
    for arch in ('x64','aarch64'):
        security=harden_runtime(folder/'runtimes'/arch/'Contents/Home',tool)
        metadata.append({'architecture':arch,**security})
    (folder/'RUNTIMES.json').write_text(json.dumps(metadata,indent=2)+'\n')
    for script in (ROOT/'packaging/runner/macos').glob('*.command'):shutil.copy2(script,folder/script.name)
    guide(folder,False,'mac')
    archive(folder,f'ServerManager-Runner-{VERSION}-mac-universal.zip')
    gui=STAGE/'ServerManagerRunner-UI-mac'
    app=gui/'Server Manager Runner Installer.app'/'Contents'
    resources=app/'Resources'
    shutil.copytree(folder,resources,dirs_exist_ok=True)
    (app/'MacOS').mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/'packaging/runner/macos/Setup',app/'MacOS/Setup')
    (app/'MacOS/Setup').chmod(0o755)
    (app/'Info.plist').write_bytes(plistlib.dumps({'CFBundleExecutable':'Setup','CFBundleIdentifier':'local.servermanager.runner.installer','CFBundleName':'Server Manager Runner Installer','CFBundlePackageType':'APPL','CFBundleShortVersionString':VERSION.split('-')[0],'CFBundleVersion':VERSION.split('-')[0],'NSHighResolutionCapable':True}))
    guide(gui,True,'mac')
    archive(gui,f'ServerManager-Runner-UI-{VERSION}-mac-universal.zip')

def windows():
    folder=STAGE/'ServerManagerRunner-windows'
    copy_common(folder)
    for script in (ROOT/'packaging/runner/windows').iterdir():
        if script.name!='Setup.cmd':shutil.copy2(script,folder/script.name)
    archive_file,version,digest=download_runtime('x64','windows')
    with zipfile.ZipFile(archive_file) as source:
        names=source.namelist()
        if any(name.startswith('/') or '..' in pathlib.PurePosixPath(name).parts for name in names):raise RuntimeError('Unsafe Windows Java runtime archive')
        prefix=pathlib.PurePosixPath(names[0]).parts[0]
        for item in source.infolist():
            parts=pathlib.PurePosixPath(item.filename).parts
            if len(parts)<=1 or item.is_dir():continue
            target=folder/'runtime'/pathlib.Path(*parts[1:])
            target.parent.mkdir(parents=True,exist_ok=True)
            with source.open(item) as input,target.open('wb') as output:shutil.copyfileobj(input,output)
    if not (folder/'runtime/bin/java.exe').exists():raise RuntimeError('Windows Java runtime layout not recognized')
    security=harden_runtime(folder/'runtime')
    (folder/'RUNTIMES.json').write_text(json.dumps([{'architecture':'x64','java':version,'sha256':digest,'source':runtime_source(archive_file),**security}],indent=2)+'\n')
    guide(folder,False,'windows')
    archive(folder,f'ServerManager-Runner-{VERSION}-windows-x64.zip')
    gui=STAGE/'ServerManagerRunner-UI-windows'
    shutil.copytree(folder,gui,dirs_exist_ok=True)
    shutil.copy2(ROOT/'packaging/runner/windows/Setup.cmd',gui/'Setup.cmd')
    guide(gui,True,'windows')
    archive(gui,f'ServerManager-Runner-UI-{VERSION}-windows-x64.zip')

if __name__=='__main__':
    DIST.mkdir(exist_ok=True)
    if STAGE.exists():shutil.rmtree(STAGE)
    modes=sys.argv[1:] or ['mac','windows']
    for mode in modes:
        if mode=='mac':mac()
        elif mode=='windows':windows()
        else:raise ValueError(mode)
