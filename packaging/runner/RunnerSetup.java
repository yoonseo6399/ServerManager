import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** The CLI and desktop installer share one installation and enrollment path. */
public final class RunnerSetup {
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    static final String HELP = """
        Server Manager Runner 설치
          install.command / install.cmd                  대화형 CLI 설치
          install.command --install-only                 등록 없이 설치
          install.command --no-autostart                 로그인 자동 대기 끄기
          install.command --allow-tcp-relay              관리 웹 소유자의 TCP 중계 사용 허용 (기본 꺼짐)
          install.command --deny-tcp-relay               위 허용을 철회
          install.command --manager-url=https://주소     관리 주소 미리 지정
          install.command --code-stdin --manager-url=... 등록 코드를 표준 입력으로 전달
          install.command --server-id=UUID --server-path="기존 서버 폴더"
                          [--server-jar=paper.jar] [--required-java=21]
          install.command --folder-only --server-id=UUID --server-path="기존 폴더"
        기존 폴더는 원래 위치에서 실행합니다. 웹에 동일한 종류·버전의 서버를 만들고 ID를 복사하세요.
        --folder-only는 이미 설치된 실행기에 경로만 등록합니다 (실행기를 먼저 종료하세요).
        Java는 포함되어 있습니다. 등록 코드는 명령 인수나 파일로 저장하지 않습니다.
        --install-only에서는 설치 후 실행을 생략합니다. run.command / run.cmd로 시작하세요.
        """;

    record Options(boolean gui, boolean autostart, boolean installOnly, boolean codeStdin, String manager, Binding binding, boolean folderOnly, Boolean relay) {
        static Options parse(String[] args) throws IOException {
            boolean gui=false, auto=true, only="1".equals(System.getenv("SERVER_MANAGER_SKIP_PAIRING")), stdin=false, folderOnly=false; Boolean relay=null; String manager="",id="",path="",jar="";int required=21;
            for (String arg:args) {
                switch(arg) {
                    case "--gui" -> gui=true;
                    case "--cli" -> gui=false;
                    case "--no-autostart" -> auto=false;
                    case "--allow-tcp-relay" -> relay=Boolean.TRUE;
                    case "--deny-tcp-relay" -> relay=Boolean.FALSE;
                    case "--install-only" -> only=true;
                    case "--code-stdin" -> stdin=true;
                    case "--folder-only" -> folderOnly=true;
                    default -> {
                        if(arg.startsWith("--manager-url=")) manager=origin(arg.substring(14));
                        else if(arg.startsWith("--server-id="))id=arg.substring(12);
                        else if(arg.startsWith("--server-path="))path=arg.substring(14);
                        else if(arg.startsWith("--server-jar="))jar=arg.substring(13);
                        else if(arg.startsWith("--required-java="))required=Integer.parseInt(arg.substring(16));
                        else throw new IllegalArgumentException("알 수 없는 옵션입니다. --help로 사용법을 확인하세요.");
                    }
                }
            }
            if(stdin && (manager.isEmpty() || only)) throw new IllegalArgumentException("--code-stdin은 --manager-url과 함께 사용하세요.");
            Binding binding=id.isBlank()&&path.isBlank()&&jar.isBlank()?null:Binding.of(id,path,jar,required);
            if(folderOnly && binding==null)throw new IllegalArgumentException("--folder-only에는 서버 ID와 경로가 필요합니다.");
            return new Options(gui,auto,only,stdin,manager,binding,folderOnly,relay);
        }
    }
    record Binding(String id,Path path,String jar,int requiredJava) {
        static Binding of(String id,String path,String jar,int required)throws IOException {
            try {if(!UUID.fromString(id.trim()).toString().equals(id.trim()))throw new IllegalArgumentException();}
            catch(Exception invalid){throw new IllegalArgumentException("관리 웹에서 서버 ID(UUID)를 복사하세요.");}
            Path folder=Path.of(path.trim()).toAbsolutePath().normalize();
            if(path.isBlank() || Files.isSymbolicLink(folder) || !Files.isDirectory(folder) || !Files.isRegularFile(folder.resolve("server.properties")))throw new IOException("server.properties가 있는 기존 서버 폴더를 선택하세요.");
            folder=folder.toRealPath();
            if(folder.getParent()==null || folder.equals(Path.of(System.getProperty("user.home")).toRealPath()))throw new IOException("홈 폴더나 디스크 전체는 서버 폴더로 등록할 수 없습니다.");
            jar=jar.trim();
            if(!jar.isEmpty() && (!jar.endsWith(".jar") || jar.contains("/") || jar.contains("\\") || jar.contains(":") || !Files.isRegularFile(folder.resolve(jar))))throw new IOException("서버 폴더 안에 있는 JAR 파일 이름만 입력하세요.");
            if(!Set.of(17,21,25).contains(required))throw new IllegalArgumentException("필요 Java는 17·21·25 중 선택하세요.");
            return new Binding(id.trim(),folder,jar,required);
        }
    }
    static void bindFolder(Path data,Binding binding)throws Exception {
        Files.createDirectories(data);
        try(FileChannel setup=FileChannel.open(data.resolve("setup.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock setupLock=setup.tryLock();
            FileChannel runner=FileChannel.open(data.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock runnerLock=runner.tryLock()) {
            if(setupLock==null || runnerLock==null)throw new IOException("실행기와 다른 설치 창을 먼저 종료하세요. 실행 중에는 서버 폴더를 변경할 수 없습니다.");
            Path folder=binding.path();Path normalized=data.toRealPath();
            if(folder.startsWith(normalized) || normalized.startsWith(folder))throw new IOException("서버 폴더와 실행기 등록 데이터는 분리하세요.");
            Path root=data.resolve("instances");Files.createDirectories(root);
            try(var entries=Files.list(root)) {
                for(Path entry:entries.toList()) {
                    Path file=entry.resolve("server-folder.properties");
                    if(!Files.isRegularFile(file) || entry.getFileName().toString().equals(binding.id()))continue;
                    Properties other=new Properties();try(InputStream in=Files.newInputStream(file)){other.load(in);}
                    Path previous=Path.of(other.getProperty("path")).toAbsolutePath().normalize();
                    if(folder.startsWith(previous) || previous.startsWith(folder))throw new IOException("다른 서버에 이미 연결된 폴더와 겹칩니다.");
                }
            }
            try(var entries=Files.walk(folder)) {
                for(Path entry:(Iterable<Path>)entries::iterator)if(Files.isSymbolicLink(entry) || !(Files.isDirectory(entry) || Files.isRegularFile(entry)))throw new IOException("서버 폴더의 링크·특수 파일을 정리한 뒤 등록하세요.");
            }
            Properties server=new Properties();try(InputStream in=Files.newInputStream(folder.resolve("server.properties"))){server.load(in);}
            Path world=folder.resolve(server.getProperty("level-name","world")).normalize();
            if(!world.startsWith(folder) || world.equals(folder))throw new IOException("월드 경로가 서버 폴더 밖을 가리킵니다.");
            Path worldLock=world.resolve("session.lock");
            if(Files.exists(worldLock))try(FileChannel channel=FileChannel.open(worldLock,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()) {
                if(lock==null)throw new IOException("이 서버 폴더는 사용 중입니다. Minecraft 서버를 정상 종료하세요.");
            }
            Path instance=root.resolve(binding.id());Files.createDirectories(instance);
            if(Files.exists(instance.resolve("dirty")))throw new IOException("이 서버의 이전 실행 데이터 인계를 먼저 완료하세요.");
            Path original=instance.resolve("folder-settings-backups").resolve(UUID.randomUUID().toString());Files.createDirectories(original);
            for(String name:List.of("server.properties","eula.txt","server-manager-runtime.json"))if(Files.isRegularFile(folder.resolve(name)))Files.copy(folder.resolve(name),original.resolve(name));
            Properties properties=new Properties();properties.setProperty("path",folder.toString());properties.setProperty("initial","true");properties.setProperty("jar",binding.jar());properties.setProperty("requiredJava",String.valueOf(binding.requiredJava()));
            Path temp=Files.createTempFile(instance,".folder-",".tmp");
            try {try(OutputStream out=Files.newOutputStream(temp)){properties.store(out,"Locally approved existing Minecraft folder");}Files.move(temp,instance.resolve("server-folder.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(temp);}
        }
    }

    static String origin(String input) {
        try {
            URI u=URI.create(input.trim());
            if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null
                || !(u.getPath().isEmpty() || u.getPath().equals("/")) || u.getPort()==0 || u.getPort()>65535)
                throw new IllegalArgumentException();
            return input.trim().replaceAll("/$", "");
        } catch(Exception invalid) { throw new IllegalArgumentException("경로가 없는 HTTPS 관리 주소를 입력하세요. 예: https://server.example.com"); }
    }
    static Path packageHome() throws Exception {
        return Path.of(RunnerSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().getParent();
    }
    static Path home() { return Path.of(System.getenv().getOrDefault("SERVER_MANAGER_RUNNER_HOME",System.getProperty("user.home")+"/.servermanager-runner")).toAbsolutePath().normalize(); }
    static Path target() {
        return WINDOWS ? Path.of(System.getenv("LOCALAPPDATA"),"ServerManagerRunner") : Path.of(System.getProperty("user.home"),"Applications","Server Manager Runner");
    }
    static Path java(Path root, boolean windowless) {
        if(WINDOWS) return root.resolve("runtime/bin/"+(windowless?"javaw.exe":"java.exe"));
        String arch=switch(System.getProperty("os.arch")) {case "aarch64","arm64" -> "aarch64"; case "amd64","x86_64" -> "x64"; default -> throw new IllegalStateException("지원하지 않는 CPU입니다.");};
        return root.resolve("runtimes/"+arch+"/Contents/Home/bin/java");
    }
    interface Services {
        void configure(Path target, Path home, boolean autostart) throws Exception;
        void start(Path target, Path home) throws Exception;
    }
    static void copy(Path source,Path destination) throws IOException {
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                // Bundled macOS Java contains relative links. Preserve them, but refuse escaping links.
                Path relative=source.relativize(file), output=destination.resolve(relative);
                if(attrs.isSymbolicLink()) {
                    Path link=Files.readSymbolicLink(file);
                    if(link.isAbsolute() || !file.getParent().resolve(link).normalize().startsWith(source)) throw new IOException("안전하지 않은 패키지 링크입니다.");
                    Files.createSymbolicLink(output,link);
                } else Files.copy(file,output,StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    static void deleteTree(Path path) throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException {if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
    static void install(Path source,Path destination,Path data,boolean autostart,Services services,Consumer<String> progress)throws Exception {
        source=source.toAbsolutePath().normalize(); destination=destination.toAbsolutePath().normalize();
        data=data.toAbsolutePath().normalize();
        if(source.equals(destination) || destination.startsWith(source) || data.startsWith(destination) || source.startsWith(destination)) throw new IOException("내려받은 설치 파일에서 설치를 실행하세요. 설치 파일·등록 데이터와 설치 위치는 분리되어야 합니다.");
        if(!Files.isRegularFile(source.resolve("server-manager.jar")) || !Files.isRegularFile(java(source,false)) || !Files.isRegularFile(source.resolve("runner-bootstrap.jar")))
            throw new IOException("설치 파일이 불완전합니다. ZIP 전체를 다시 풀어주세요.");
        Files.createDirectories(data); Files.createDirectories(destination.getParent());
        try(FileChannel setup=FileChannel.open(data.resolve("setup.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock setupLock=setup.tryLock()) {
            if(setupLock==null) throw new IOException("다른 설치 창이 작업 중입니다.");
            Path instances=data.resolve("instances");
            if(Files.isDirectory(instances))try(var children=Files.list(instances)) {
                if(children.anyMatch(path->Files.exists(path.resolve("dirty"))))throw new IOException("실행 중이거나 파일 인계를 마치지 않은 서버가 있습니다. 정상 종료·인계를 완료한 뒤 설치하세요.");
            }
            try(FileChannel runner=FileChannel.open(data.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock runnerLock=runner.tryLock()) {
                if(runnerLock==null) throw new IOException("실행기가 이미 대기 중입니다. 게임을 정상 종료하고 실행기를 종료한 뒤 설치하세요. 기존 등록은 유지됩니다.");
                if(Files.isSymbolicLink(destination) || (Files.exists(destination) && (!Files.isRegularFile(destination.resolve("runner-bootstrap.jar")) || !Files.isRegularFile(destination.resolve("version.txt")))))
                    throw new IOException("설치 위치에 다른 파일이 있습니다. 기존 폴더를 확인하세요.");
                Path stage=Files.createTempDirectory(destination.getParent(),".runner-install-");
                Path backup=destination.resolveSibling(".runner-backup-"+UUID.randomUUID()); boolean replaced=false, hadOld=false;
                try {
                    progress.accept("Java와 실행기를 복사하고 있습니다…"); copy(source,stage);
                    if(Files.exists(destination)) {Files.move(destination,backup,StandardCopyOption.ATOMIC_MOVE);hadOld=true;}
                    Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);replaced=true;
                    Files.writeString(destination.resolve("runner-home.txt"),data.toAbsolutePath().normalize().toString());
                    progress.accept("로그인 자동 대기를 설정하고 있습니다…"); services.configure(destination,data,autostart);
                } catch(Exception failure) {
                    if(replaced) deleteTree(destination);
                    if(hadOld) Files.move(backup,destination,StandardCopyOption.ATOMIC_MOVE);
                    throw failure;
                } finally {deleteTree(stage);}
                deleteTree(backup);
            }
        }
    }
    /** Current local consent for the management web owner to use this computer as a TCP relay (new file, or the older marker file). */
    static boolean relayAllowed(Path data) {
        try {
            Path file=data.resolve("direct-relay.json");
            if(Files.isRegularFile(file)) return Pattern.compile("\"allowed\"\s*:\s*true").matcher(Files.readString(file)).find();
            Path legacy=data.resolve("tcp-relay-enabled");
            return Files.isRegularFile(legacy) && Files.readString(legacy).trim().equals("true");
        } catch(IOException e) {return false;}
    }
    /** Same file the runner reads (direct-relay.json); keeps the migration marker so the web UI can still show where an old grant came from. */
    static void writeRelayConsent(Path data,boolean allowed,String source) throws IOException {
        Files.createDirectories(data);
        Path file=data.resolve("direct-relay.json");
        String migrated="null";
        if(Files.isRegularFile(file)) {Matcher m=Pattern.compile("\"migratedFrom\"\s*:\s*\"([^\"]*)\"").matcher(Files.readString(file));if(m.find())migrated="\""+m.group(1)+"\"";}
        String json="{\"allowed\":"+allowed+",\"source\":\""+source+"\",\"migratedFrom\":"+migrated+",\"updatedAt\":\""+java.time.Instant.now()+"\",\"environmentLocked\":false}";
        Path temp=Files.createTempFile(data,".relay-consent-",".tmp");
        try {Files.writeString(temp,json);if(!WINDOWS)Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temp);}
    }
    static void pair(Path installed,Path data,String manager,char[] code) throws Exception {
        try {
            manager=origin(manager);
            if(code.length<30 || code.length>100 || new String(code).chars().anyMatch(Character::isWhitespace)) throw new IOException("웹에서 발급한 등록 코드를 확인하세요.");
            if(Files.exists(data.resolve("identity.json"))) throw new IOException("이미 등록되어 있습니다. 기존 등록을 유지합니다.");
            ProcessBuilder pb=new ProcessBuilder(java(installed,false).toString(),"-jar",installed.resolve("server-manager.jar").toString(),"--runner-pair","--manager-url="+manager);
            pb.environment().put("SERVER_MANAGER_RUNNER_HOME",data.toString());
            // The enrollment code travels only over stdin; never include child output in errors/logs.
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process=pb.start();
            try(Writer writer=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8)) {writer.write(code);writer.write('\n');}
            if(!process.waitFor(60,TimeUnit.SECONDS)) {process.destroy();throw new IOException("등록 응답 시간이 초과되었습니다. 웹에서 등록 여부를 확인한 뒤 다시 시도하세요.");}
            if(process.exitValue()!=0 || !Files.isRegularFile(data.resolve("identity.json"))) throw new IOException("등록하지 못했습니다. 관리 주소·연결 상태를 확인하고 웹에서 새 코드를 발급하세요. 설치는 유지되므로 다시 시도할 수 있습니다.");
        } finally {Arrays.fill(code,'\0');}
    }
    static final class SystemServices implements Services {
        final String label;
        SystemServices(){this("local.servermanager.runner");}
        SystemServices(String label){
            if(!label.matches("local\\.servermanager\\.runner(?:\\.test-[a-z0-9-]+)?")) throw new IllegalArgumentException("Invalid service label");
            this.label=label;
        }
        public void configure(Path target,Path data,boolean auto)throws Exception {
            if(WINDOWS) {
                String script="""
                    $ErrorActionPreference = 'Stop'
                    $existing = Get-ScheduledTask -TaskName $env:SM_SETUP_TASK -ErrorAction SilentlyContinue
                    if ($env:SM_SETUP_AUTO -ne '1' -and $existing) { Unregister-ScheduledTask -TaskName $env:SM_SETUP_TASK -Confirm:$false }
                    if ($env:SM_SETUP_AUTO -eq '1') {
                      $java = Join-Path $env:SM_SETUP_TARGET 'runtime\\bin\\javaw.exe'
                      $bootstrap = Join-Path $env:SM_SETUP_TARGET 'runner-bootstrap.jar'
                      $action = New-ScheduledTaskAction -Execute $java -Argument ('-jar "' + $bootstrap + '"')
                      $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
                      $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
                      $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
                      Register-ScheduledTask -TaskName $env:SM_SETUP_TASK -Action $action -Trigger $trigger -Principal $principal -Force | Out-Null
                    }
                    exit 0
                    """;
                runPowerShell(script,target,auto,label);
            } else {
                Path plist=Path.of(System.getProperty("user.home"),"Library/LaunchAgents/"+label+".plist");
                Files.createDirectories(plist.getParent());
                // Holding runner.lock proves the installed supervisor has no live child.
                // Unload an inactive old job so OFF also takes effect in this login session.
                String uid=new String(new ProcessBuilder("/usr/bin/id","-u").start().getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
                Process unload=new ProcessBuilder("/bin/launchctl","bootout","gui/"+uid+"/"+label).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if(!unload.waitFor(30,TimeUnit.SECONDS)) throw new IOException("이전 자동 대기 설정을 해제하지 못했습니다.");
                if(auto) {
                    Files.createDirectories(Path.of(System.getProperty("user.home"),"Library/Logs"));
                    String log=Path.of(System.getProperty("user.home"),"Library/Logs/ServerManagerRunner-launch.log").toString();
                    String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\"><plist version=\"1.0\"><dict>"
                        +"<key>Label</key><string>"+label+"</string><key>ProgramArguments</key><array><string>"+escapeXml(java(target,false).toString())+"</string><string>-jar</string><string>"+escapeXml(target.resolve("runner-bootstrap.jar").toString())+"</string></array>"
                        +"<key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>EnvironmentVariables</key><dict><key>SERVER_MANAGER_RUNNER_HOME</key><string>"+escapeXml(data.toString())+"</string></dict>"
                        +"<key>StandardOutPath</key><string>"+escapeXml(log)+"</string><key>StandardErrorPath</key><string>"+escapeXml(log)+"</string></dict></plist>";
                    Path temporary=Files.createTempFile(plist.getParent(),".runner-plist-",".tmp");
                    try {Files.writeString(temporary,xml);Files.move(temporary,plist,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    finally {Files.deleteIfExists(temporary);}
                } else Files.deleteIfExists(plist);
            }
        }
        public void start(Path target,Path data)throws Exception {
            if(!WINDOWS) {
                Path plist=Path.of(System.getProperty("user.home"),"Library/LaunchAgents/"+label+".plist");
                if(Files.exists(plist)) {
                    String uid=new String(new ProcessBuilder("/usr/bin/id","-u").start().getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
                    String domain="gui/"+uid;
                    Process check=new ProcessBuilder("/bin/launchctl","print",domain+"/"+label).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if(check.waitFor()==0) run(List.of("/bin/launchctl","kickstart",domain+"/"+label));
                    else run(List.of("/bin/launchctl","bootstrap",domain,plist.toString()));
                    return;
                }
            }
            Files.createDirectories(data.resolve("logs"));
            ProcessBuilder pb=new ProcessBuilder(java(target,WINDOWS).toString(),"-jar",target.resolve("runner-bootstrap.jar").toString());
            pb.environment().put("SERVER_MANAGER_RUNNER_HOME",data.toString());
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("logs/setup-start.log").toFile())).start();
        }
    }
    static String escapeXml(String text) {return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    static void runPowerShell(String script,Path target,boolean auto,String label)throws Exception {
        // Fixed script, paths passed as environment values instead of interpolated PowerShell.
        ProcessBuilder pb=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
        pb.environment().put("SM_SETUP_TARGET",target.toString());pb.environment().put("SM_SETUP_AUTO",auto?"1":"0");
        pb.environment().put("SM_SETUP_TASK",label.equals("local.servermanager.runner")?"ServerManagerRunner":label);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=pb.start(); if(!p.waitFor(60,TimeUnit.SECONDS) || p.exitValue()!=0) throw new IOException("로그인 자동 대기를 설정하지 못했습니다. Windows 작업 스케줄러의 ServerManagerRunner 항목을 확인하세요.");
    }
    static void run(List<String> args)throws Exception {
        Process p=new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!p.waitFor(30,TimeUnit.SECONDS) || p.exitValue()!=0) throw new IOException("실행기 자동 대기를 시작하지 못했습니다. run.command / run.cmd로 직접 시작할 수 있습니다.");
    }
    static void openWeb(String manager)throws Exception {Desktop.getDesktop().browse(URI.create(origin(manager)+"/#runners"));}
    static void cli(Options options)throws Exception {
        System.out.println("Server Manager Runner · CLI 설치\n설치 위치: "+target()+"\n등록·서버 데이터: "+home());
        if(options.folderOnly()) {
            if(!Files.isRegularFile(target().resolve("runner-bootstrap.jar")))throw new IOException("먼저 실행기를 설치하세요.");
            bindFolder(home(),options.binding());System.out.println("기존 서버 폴더를 연결했습니다. 원래 경로: "+options.binding().path()+"\n웹에서 실행기를 배정한 뒤 파일 관리자에서 기존 폴더 가져오기를 누르세요.");return;
        }
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        boolean registered=Files.isRegularFile(home().resolve("identity.json"));
        String manager=options.manager();char[] code=null;
        if(!options.installOnly() && !registered) {
            if(manager.isEmpty()) {System.out.print("관리 웹 HTTPS 주소: ");System.out.flush();manager=origin(Objects.requireNonNullElse(input.readLine(),""));}
            System.out.println("관리 웹의 ‘서버 실행기’에서 패스키로 5분 유효 등록 코드를 발급하세요.");
            if(!options.codeStdin()) try{openWeb(manager);}catch(Exception ignored){}
            Console console=System.console();
            if(console!=null && !options.codeStdin()) code=console.readPassword("등록 코드 (화면에 표시되지 않음): ");
            else {System.out.print("등록 코드 (표준 입력): ");System.out.flush();code=Objects.requireNonNullElse(input.readLine(),"").trim().toCharArray();}
            if(code==null || code.length<30 || code.length>100) throw new IOException("등록 코드를 확인하세요.");
        }
        try {
            Services services=new SystemServices();
            install(packageHome(),target(),home(),options.autostart(),services,System.out::println);
            if(options.binding()!=null)bindFolder(home(),options.binding());
            if(options.relay()!=null && options.relay()!=relayAllowed(home())) writeRelayConsent(home(),options.relay(),"installer-cli");
            System.out.println("관리 웹 소유자의 TCP 중계 사용 허용: "+(relayAllowed(home())?"허용":"허용 안 함 (기본값)"));
            if(code!=null) pair(target(),home(),manager,code);
            if(!options.installOnly()) services.start(target(),home());
            System.out.println("설치 완료 · 로그인 자동 대기 "+(options.autostart()?"ON":"OFF")+(options.installOnly()?"\n등록·실행은 생략했습니다.":"\n실행기를 시작했습니다. 관리 웹에서 온라인 상태와 서버 사용 허용을 확인하세요."));
        } finally {if(code!=null)Arrays.fill(code,'\0');}
    }

    static final class Wizard {
        final JFrame frame=new JFrame("Server Manager Runner 설치");
        final JTextField manager=new JTextField("https://server.enclody.sarl");
        final JPasswordField code=new JPasswordField();
        final JCheckBox useFolder=new JCheckBox("기존 마크 서버 폴더를 원래 위치에서 실행 (선택)");
        final JTextField serverId=new JTextField(),folder=new JTextField(),jar=new JTextField();
        final JComboBox<String> required=new JComboBox<>(new String[]{"21","17","25"});
        final JButton choose=new JButton("서버 폴더 선택");
        final JCheckBox auto=new JCheckBox("컴퓨터에 로그인하면 실행기를 자동으로 대기",true);
        final JCheckBox relay=new JCheckBox("관리 웹 소유자의 TCP 중계 사용 허용",relayAllowed(home()));
        final JButton web=new JButton("웹에서 패스키로 등록 코드 발급"),install=new JButton("설치하고 연결"),close=new JButton("닫기");
        final JLabel status=new JLabel("웹 주소 확인 → 등록 코드 발급 → 설치하고 연결");
        final JProgressBar progress=new JProgressBar();
        boolean installed=false;
        Wizard() {
            JPanel panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));panel.setBorder(new EmptyBorder(24,28,24,28));
            JLabel title=new JLabel("친구의 Minecraft 서버를 이 컴퓨터에서 실행");title.setFont(title.getFont().deriveFont(Font.BOLD,19));panel.add(title);gap(panel);
            panel.add(new JLabel("Java는 포함되어 있습니다. 관리자 권한이나 포트포워딩이 필요하지 않습니다."));gap(panel);
            addField(panel,"1. 관리 웹 HTTPS 주소",manager);web.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(web);gap(panel);
            addField(panel,"2. 웹에서 발급한 일회용 등록 코드 (5분 유효)",code);
            useFolder.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(useFolder);
            JPanel folderPanel=new JPanel();folderPanel.setLayout(new BoxLayout(folderPanel,BoxLayout.Y_AXIS));folderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            addField(folderPanel,"웹에서 같은 종류·버전의 서버 생성 → 서버 ID 복사",serverId);
            addField(folderPanel,"기존 서버 폴더 경로 (server.properties가 있는 폴더)",folder);choose.setAlignmentX(Component.LEFT_ALIGNMENT);folderPanel.add(choose);gap(folderPanel);
            addField(folderPanel,"서버 JAR 이름 (선택 · 예: paper.jar / Forge·NeoForge는 비워두세요)",jar);
            JLabel javaLabel=new JLabel("이 서버가 요구하는 Java 버전");javaLabel.setLabelFor(required);folderPanel.add(javaLabel);required.setAlignmentX(Component.LEFT_ALIGNMENT);required.setMaximumSize(new Dimension(160,28));folderPanel.add(required);
            folderPanel.add(new JLabel("첫 시작은 이 폴더를 사용합니다. 정상 종료 후 관리 서버와 변경 파일을 동기화합니다."));gap(folderPanel);
            folderPanel.setVisible(false);panel.add(folderPanel);gap(panel);
            useFolder.addActionListener(e->{folderPanel.setVisible(useFolder.isSelected());frame.pack();var screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();frame.setSize(Math.max(720,Math.min(frame.getWidth(),screen.width-40)),Math.min(frame.getHeight(),screen.height-40));frame.setLocationRelativeTo(null);});
            choose.addActionListener(e->{JFileChooser picker=new JFileChooser();picker.setDialogTitle("기존 Minecraft 서버 폴더");picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(picker.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION)folder.setText(picker.getSelectedFile().getAbsolutePath());});
            auto.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(auto);
            relay.setAlignmentX(Component.LEFT_ALIGNMENT);relay.setToolTipText("켜면 관리 웹 소유자가 이 컴퓨터를 게임 TCP 중계로 지정하거나 해제할 수 있습니다. 기본은 꺼짐이며 실행기 설정에서 언제든 철회할 수 있습니다.");panel.add(relay);
            JLabel relayNote=new JLabel("기본 꺼짐 · 철회: install.command/install.cmd --deny-tcp-relay 또는 --runner-relay-consent=revoke");relayNote.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(relayNote);gap(panel);
            panel.add(new JLabel("설치 위치: "+target()));panel.add(new JLabel("기존 기기 등록과 서버 데이터는 보존됩니다."));gap(panel);
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));buttons.setAlignmentX(Component.LEFT_ALIGNMENT);buttons.add(install);buttons.add(Box.createHorizontalStrut(12));buttons.add(close);panel.add(buttons);gap(panel);
            progress.setAlignmentX(Component.LEFT_ALIGNMENT);progress.setMaximumSize(new Dimension(Integer.MAX_VALUE,12));panel.add(progress);gap(panel);
            status.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(status);
            JScrollPane scroll=new JScrollPane(panel);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(18);frame.setContentPane(scroll);frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);frame.pack();frame.setMinimumSize(new Dimension(720,440));frame.setLocationRelativeTo(null);
            frame.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){System.exit(0);}});
            close.addActionListener(e->frame.dispose());
            web.addActionListener(e->{try{openWeb(manager.getText());}catch(Exception failure){showError(failure.getMessage());}});
            install.addActionListener(e->submit());
            if(Files.isRegularFile(home().resolve("identity.json"))) {code.setEnabled(false);manager.setEnabled(false);status.setText("이미 등록된 컴퓨터입니다. 기존 등록을 유지해 설치합니다.");install.setText("기존 등록으로 설치");}
        }
        void addField(JPanel panel,String text,JTextField input) {
            JLabel label=new JLabel(text);label.setLabelFor(input);label.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(label);
            input.setAlignmentX(Component.LEFT_ALIGNMENT);input.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));panel.add(input);gap(panel);
        }
        void gap(JPanel panel){panel.add(Box.createVerticalStrut(12));}
        void showError(String message){JOptionPane.showMessageDialog(frame,message,"설치 확인",JOptionPane.ERROR_MESSAGE);}
        void submit() {
            boolean paired=Files.isRegularFile(home().resolve("identity.json"));
            String url;char[] secret=code.getPassword();
            final Binding binding;
            try {url=paired?"":origin(manager.getText());if(!paired && (secret.length<30 || secret.length>100))throw new IllegalArgumentException("웹에서 등록 코드를 발급한 뒤 붙여넣으세요.");}
            catch(Exception failure){Arrays.fill(secret,'\0');showError(failure.getMessage());return;}
            try {binding=useFolder.isSelected()?Binding.of(serverId.getText(),folder.getText(),jar.getText(),Integer.parseInt((String)required.getSelectedItem())):null;}
            catch(Exception failure){Arrays.fill(secret,'\0');showError(failure.getMessage());return;}
            if(binding!=null && JOptionPane.showConfirmDialog(frame,"웹 서버의 종류·버전이 기존 파일과 일치하는지 확인하세요.\n첫 실행은 이 폴더의 월드·모드·설정을 원본으로 사용합니다.\n이후에는 웹 파일 관리와 실행기 간 동기화가 적용됩니다.\n"+binding.path(),"기존 서버 폴더 연결",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION){Arrays.fill(secret,'\0');return;}
            boolean startup=auto.isSelected(),allowRelay=relay.isSelected();relay.setEnabled(false);code.setText("");manager.setEnabled(false);code.setEnabled(false);auto.setEnabled(false);web.setEnabled(false);install.setEnabled(false);close.setEnabled(false);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);progress.setIndeterminate(true);
            useFolder.setEnabled(false);serverId.setEnabled(false);folder.setEnabled(false);jar.setEnabled(false);choose.setEnabled(false);required.setEnabled(false);
            new SwingWorker<Void,String>() {
                protected Void doInBackground()throws Exception {
                    Services services=new SystemServices();
                    if(!installed) {RunnerSetup.install(packageHome(),target(),home(),startup,services,this::publish);installed=true;}
                    if(binding!=null)bindFolder(home(),binding);
                    if(allowRelay!=relayAllowed(home()))writeRelayConsent(home(),allowRelay,"installer");
                    if(!paired) {publish("패스키로 발급한 코드를 확인하고 연결합니다…");pair(target(),home(),url,secret);}
                    services.start(target(),home());return null;
                }
                protected void process(List<String> messages){status.setText(messages.getLast());}
                protected void done() {
                    Arrays.fill(secret,'\0');progress.setIndeterminate(false);close.setEnabled(true);frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    try {get();progress.setValue(100);status.setText("설치 완료! 웹에서 서버 허용·실행 위치 선택 → 파일 관리자에서 기존 폴더 가져오기");close.setText("완료");}
                    catch(Exception failure) {
                        status.setText(installed?"설치는 완료됐습니다. 등록·연결을 다시 시도하세요.":"설치가 완료되지 않았습니다.");
                        Throwable cause=failure.getCause();showError(cause==null?"설치하지 못했습니다.":cause.getMessage());
                        manager.setEnabled(!paired);code.setEnabled(!paired);auto.setEnabled(!installed);relay.setEnabled(true);web.setEnabled(true);install.setEnabled(true);install.setText("다시 시도");
                        useFolder.setEnabled(true);serverId.setEnabled(true);folder.setEnabled(true);jar.setEnabled(true);choose.setEnabled(true);required.setEnabled(true);
                    }
                }
            }.execute();
        }
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out,true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err,true,StandardCharsets.UTF_8));
        if(Arrays.asList(args).contains("--help")) {System.out.print(HELP);return;}
        try {
            Options options=Options.parse(args);
            if(options.gui()) SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());new Wizard().frame.setVisible(true);}catch(Exception failure){JOptionPane.showMessageDialog(null,"설치 창을 열지 못했습니다. CLI 설치판을 사용하세요.");}});
            else cli(options);
        } catch(Exception failure) {System.err.println("설치 중단: "+failure.getMessage());System.exit(1);}
    }
}
