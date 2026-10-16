import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Bootstrap {
 static Path app,home,resources,updates; static volatile Process child;static String bundled;
 static String env(String name,String fallback){return System.getenv().getOrDefault(name,fallback);}
 static void atomic(Path path,String value)throws Exception {Files.createDirectories(path.getParent());Path tmp=Files.createTempFile(path.getParent(),"write-",".tmp");try{Files.writeString(tmp,value);Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
 static String text(Path p,String fallback)throws Exception{return Files.exists(p)?Files.readString(p).trim():fallback;}
 static int[] versionParts(String value){
  if(!value.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(-beta\\.[1-9][0-9]{0,5})?"))throw new IllegalArgumentException("Invalid version");
  String[] split=value.split("-beta\\."),core=split[0].split("\\.");
  return new int[]{Integer.parseInt(core[0]),Integer.parseInt(core[1]),Integer.parseInt(core[2]),split.length==2?Integer.parseInt(split[1]):Integer.MAX_VALUE};
 }
 static int compare(String a,String b){int[] x=versionParts(a),y=versionParts(b);for(int i=0;i<4;i++){int c=Integer.compare(x[i],y[i]);if(c!=0)return c;}return 0;}
 static boolean approvedRollback(String target,String current)throws Exception{return compare(target,current)<0 && compare(target,"0.6.0")>=0 && !target.contains("-beta.") && target.equals(text(updates.resolve("rollback-approved.txt"),""));}
 static void activate(String version)throws Exception{atomic(updates.resolve("active.txt"),version);if(compare(version,bundled)<0)atomic(updates.resolve("rollback-active.txt"),version);else Files.deleteIfExists(updates.resolve("rollback-active.txt"));}
 static Path verified(String version)throws Exception {
  if(version.equals(bundled))return resources.resolve("server-manager.jar");
  if(compare(version,bundled)<0 && !version.equals(text(updates.resolve("rollback-active.txt"),"")) && !version.equals(text(updates.resolve("rollback-approved.txt"),"")))throw new SecurityException("Unapproved downgrade");
  Path dir=updates.resolve(version);byte[] manifest=Files.readAllBytes(dir.resolve("update.properties"));
  Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(resources.resolve("update-public-key.der")))));verifier.update(manifest);
  if(!verifier.verify(Files.readAllBytes(dir.resolve("update.sig"))))throw new SecurityException("Update signature mismatch");
  Properties p=new Properties();p.load(new ByteArrayInputStream(manifest));
  if(!version.equals(p.getProperty("version"))||!"1".equals(p.getProperty("protocol"))||!p.getProperty("file","").equals("server-manager-"+version+".jar"))throw new SecurityException("Invalid update manifest");
  Path jar=dir.resolve(p.getProperty("file"));if(Files.size(jar)!=Long.parseLong(p.getProperty("size")))throw new SecurityException("Update size mismatch");
  MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(jar)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)hash.update(bytes,0,n);}
  if(!HexFormat.of().formatHex(hash.digest()).equals(p.getProperty("sha256")))throw new SecurityException("Update hash mismatch");return jar;
 }
 static Path validateData(String value)throws Exception {
  Path path=Path.of(value);if(!path.isAbsolute())throw new IllegalArgumentException("프로필 위치는 절대 경로여야 합니다.");
  Files.createDirectories(path);path=path.toRealPath();
  try(var files=Files.list(path)){for(Path entry:files.toList()){String name=entry.getFileName().toString();if(name.startsWith("."))continue;if(Set.of("security","imports","deleted-instances","neoforge-versions.json").contains(name))continue;if(Files.isDirectory(entry)&&Files.exists(entry.resolve("instance.json")))continue;throw new IllegalArgumentException("비어 있는 폴더 또는 기존 Server Manager 프로필 폴더를 선택하세요.");}}
  Path check=Files.createTempFile(path,".write-check-",".tmp");Files.delete(check);return path;
 }
 static void chooseData(String explicit)throws Exception {
  String chosen=explicit;
  if(chosen==null){Process p=new ProcessBuilder("/usr/bin/osascript",resources.resolve("choose-profile.applescript").toString(),home.resolve("data").toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();chosen=new String(p.getInputStream().readAllBytes()).trim();if(p.waitFor()!=0||chosen.isEmpty())throw new IOException("프로필 폴더 선택을 취소했습니다.");}
  atomic(home.resolve("data-location.txt"),validateData(chosen).toString()+"\n");
 }
 static void stopChild(){Process p=child;if(p!=null&&p.isAlive()){p.destroy();try{p.waitFor();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}}
 public static void main(String[] argv)throws Exception {
  try { run(argv); } catch(Exception e) { System.err.println(e.getMessage());if(!List.of(argv).contains("--service"))new ProcessBuilder("/usr/bin/osascript","-e","on run argv\ndisplay alert \"Server Manager\" message (item 1 of argv)\nend run",e.getMessage()==null?"앱 실행 실패":e.getMessage()).start().waitFor();System.exit(1); }
 }
 static void run(String[] argv)throws Exception {
  app=Path.of(System.getenv("SERVER_MANAGER_APP_PATH"));home=Path.of(env("SERVER_MANAGER_HOME",System.getProperty("user.home")+"/Library/Application Support/ServerManager"));resources=app.resolve("Contents/Resources");updates=home.resolve("updates");bundled=text(resources.resolve("version.txt"),"0.3.0");
  Files.createDirectories(home);Files.setPosixFilePermissions(home,PosixFilePermissions.fromString("rwx------"));Files.createDirectories(home.resolve("logs"));Files.createDirectories(home.resolve("config"));Files.createDirectories(updates);
  Path config=home.resolve("config/application.properties");if(!Files.exists(config))Files.copy(resources.resolve("application.properties.example"),config);
  List<String> args=new ArrayList<>(List.of(argv));boolean service=args.remove("--service"),configure=args.remove("--configure"),recovery=args.contains("--recover-owner");
  String explicit=null;int location=args.indexOf("--data-directory");if(location>=0){explicit=args.get(location+1);args.subList(location,location+2).clear();}
  try(FileChannel channel=FileChannel.open(home.resolve("launcher.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
   FileLock lock=channel.tryLock();
   if(lock==null){if(configure)throw new IllegalStateException("실행 중인 Server Manager 앱을 종료한 뒤 저장 위치를 변경하세요.");if(!service)openBrowser(config);return;}
   try(lock){
    if(configure){chooseData(explicit);return;}
    if(explicit!=null)chooseData(explicit);
    if(System.getenv("SERVER_MANAGER_DATA")!=null&&!Files.exists(home.resolve("data-location.txt")))atomic(home.resolve("data-location.txt"),validateData(System.getenv("SERVER_MANAGER_DATA")).toString()+"\n");
    if(System.getenv("SERVER_MANAGER_DATA")==null&&!Files.exists(home.resolve("data-location.txt"))){if(service)atomic(home.resolve("data-location.txt"),home.resolve("data").toString()+"\n");else chooseData(null);}
    Runtime.getRuntime().addShutdownHook(new Thread(Bootstrap::stopChild));
    String active=text(updates.resolve("active.txt"),bundled);if(compare(active,bundled)<0 && !active.equals(text(updates.resolve("rollback-active.txt"),"")))active=bundled;
    boolean rolledBack=false;
    while(true){
     Path jar;try{jar=verified(active);}catch(Exception e){atomic(home.resolve("update-error.txt"),e.getMessage());active=bundled;jar=verified(active);activate(active);}
     Path data=Path.of(text(home.resolve("data-location.txt"),home.resolve("data").toString()));Files.createDirectories(data);
     Path ready=home.resolve("ready-"+UUID.randomUUID()+".txt");
     List<String> command=new ArrayList<>(List.of(System.getProperty("java.home")+"/bin/java","-jar",jar.toString()));command.addAll(args);
     ProcessBuilder builder=new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs/app.log").toFile()));
     builder.environment().put("SERVER_MANAGER_DATA",data.toString());builder.environment().put("SERVER_MANAGER_HOME",home.toString());builder.environment().put("SERVER_MANAGER_SUPERVISED","1");builder.environment().put("SERVER_MANAGER_BOOTSTRAP_PROTOCOL","2");builder.environment().put("SERVER_MANAGER_EXPECTED_VERSION",active);builder.environment().put("SERVER_MANAGER_READY_FILE",ready.toString());child=builder.start();
     if(recovery){System.exit(child.waitFor());return;}
     for(int i=0;i<120&&child.isAlive()&&!Files.exists(ready);i++)Thread.sleep(500);
     boolean healthy=Files.exists(ready);Files.deleteIfExists(ready);
     if(!healthy){stopChild();if(!active.equals(bundled)&&!rolledBack){atomic(home.resolve("failed-update-version.txt"),active);atomic(home.resolve("update-error.txt"),"새 버전이 시작되지 않아 이전 버전으로 복구했습니다.");active=text(updates.resolve("previous.txt"),bundled);activate(active);rolledBack=true;continue;}throw new IllegalStateException("앱을 시작하지 못했습니다. "+home.resolve("logs/app.log"));}
     if(!service){if(Files.exists(data.resolve("security/setup-code.txt")))new ProcessBuilder("/usr/bin/open","-t",data.resolve("security/setup-code.txt").toString()).start();openBrowser(config);service=true;}
     int code=child.waitFor();child=null;if(code!=75)return;
     String pending=text(updates.resolve("pending.txt"),active);
     if(compare(pending,active)>0 || approvedRollback(pending,active)){verified(pending);atomic(updates.resolve("previous.txt"),active);active=pending;activate(active);Files.deleteIfExists(updates.resolve("pending.txt"));Files.deleteIfExists(updates.resolve("rollback-approved.txt"));rolledBack=false;}
     Path pendingData=home.resolve("pending-data-location.txt");if(Files.exists(pendingData)){String next=Files.readString(pendingData).trim();atomic(home.resolve("data-location.txt"),validateData(next).toString()+"\n");Files.delete(pendingData);}
    }
   }
  }
 }
 static void openBrowser(Path config)throws Exception {Properties p=new Properties();try(InputStream in=Files.newInputStream(config)){p.load(in);}String origin=p.getProperty("server-manager.public-origin","").trim();if(origin.isEmpty())origin="http://localhost:"+p.getProperty("server.port","8787");new ProcessBuilder("/usr/bin/open",origin).start();}
}
