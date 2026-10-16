import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Small cross-platform supervisor. All downloaded JARs must match the pinned release signature. */
public final class RunnerBootstrap {
 static Path packageHome,home,updates;
 static String bundled;
 static volatile Process child;
 static String text(Path file,String fallback)throws IOException{return Files.exists(file)?Files.readString(file).trim():fallback;}
 static void atomic(Path file,String value)throws IOException{
  Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),".write-",".tmp");
  try{Files.writeString(temp,value);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
 }
 static int compare(String a,String b){
  if(!a.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(-beta\\.[1-9][0-9]{0,5})?")||!b.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(-beta\\.[1-9][0-9]{0,5})?"))throw new IllegalArgumentException("Invalid version");
  int[] x=parts(a),y=parts(b);for(int i=0;i<4;i++){int c=Integer.compare(x[i],y[i]);if(c!=0)return c;}return 0;
 }
 static int[] parts(String v){String[] s=v.split("-beta\\.");String[] c=s[0].split("\\.");return new int[]{Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]),s.length==2?Integer.parseInt(s[1]):Integer.MAX_VALUE};}
 static boolean approvedRollback(String target,String current)throws Exception{return compare(target,current)<0 && compare(target,"0.6.0")>=0 && !target.contains("-beta.") && target.equals(text(updates.resolve("rollback-approved.txt"),""));}
 static void activate(String version)throws Exception{atomic(updates.resolve("active.txt"),version);if(compare(version,bundled)<0)atomic(updates.resolve("rollback-active.txt"),version);else Files.deleteIfExists(updates.resolve("rollback-active.txt"));}
 static Path verified(String version)throws Exception{
  if(version.equals(bundled))return packageHome.resolve("server-manager.jar");
  if(compare(version,bundled)<0 && !version.equals(text(updates.resolve("rollback-active.txt"),"")) && !version.equals(text(updates.resolve("rollback-approved.txt"),"")))throw new SecurityException("Unapproved downgrade");
  Path dir=updates.resolve(version);byte[] manifest=Files.readAllBytes(dir.resolve("update.properties"));
  Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(packageHome.resolve("update-public-key.der")))));verifier.update(manifest);
  if(!verifier.verify(Files.readAllBytes(dir.resolve("update.sig"))))throw new SecurityException("Runner update signature mismatch");
  Properties p=new Properties();p.load(new ByteArrayInputStream(manifest));
  if(!version.equals(p.getProperty("version"))||!"1".equals(p.getProperty("protocol"))||!("server-manager-"+version+".jar").equals(p.getProperty("file")))throw new SecurityException("Invalid runner update manifest");
  Path jar=dir.resolve(p.getProperty("file"));if(Files.size(jar)!=Long.parseLong(p.getProperty("size")))throw new SecurityException("Runner update size mismatch");
  MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(jar)){byte[] b=new byte[65536];for(int n;(n=input.read(b))!=-1;)hash.update(b,0,n);}
  if(!HexFormat.of().formatHex(hash.digest()).equals(p.getProperty("sha256")))throw new SecurityException("Runner update hash mismatch");return jar;
 }
 static void run()throws Exception{
  String configured=System.getenv("RUNNER_PACKAGE_HOME");
  packageHome=configured!=null?Path.of(configured).toAbsolutePath().normalize():Path.of(RunnerBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().getParent();
  home=Path.of(System.getenv().getOrDefault("SERVER_MANAGER_RUNNER_HOME",text(packageHome.resolve("runner-home.txt"),System.getProperty("user.home")+"/.servermanager-runner"))).toAbsolutePath().normalize();
  updates=home.resolve("updates");Files.createDirectories(updates);Files.createDirectories(home.resolve("logs"));
  bundled=text(packageHome.resolve("version.txt"),"0.0.0");compare(bundled,"0.0.0");
  try(FileChannel channel=FileChannel.open(home.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){
   if(lock==null)throw new IllegalStateException("Runner is already active");
   Runtime.getRuntime().addShutdownHook(new Thread(()->{Process active=child;if(active!=null&&active.isAlive()){active.destroy();try{active.waitFor();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}}));
   String active=text(updates.resolve("active.txt"),bundled);if(compare(active,bundled)<0 && !active.equals(text(updates.resolve("rollback-active.txt"),"")))active=bundled;
   boolean rolledBack=false;
   for(;;){
    Path jar;try{jar=verified(active);}catch(Exception failure){atomic(home.resolve("update-error.txt"),failure.getMessage());active=bundled;jar=verified(active);activate(active);}
    Path ready=home.resolve("ready-"+UUID.randomUUID());
    ProcessBuilder builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-jar",jar.toString(),"--runner").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs/runner.log").toFile()));
    builder.environment().put("RUNNER_BOOTSTRAP_PROTOCOL","2");builder.environment().put("RUNNER_PACKAGE_HOME",packageHome.toString());builder.environment().put("SERVER_MANAGER_RUNNER_HOME",home.toString());builder.environment().put("SERVER_MANAGER_RUNNER_READY_FILE",ready.toString());
    child=builder.start();boolean healthy=false;
    for(int i=0;i<120&&child.isAlive()&&!Files.exists(ready);i++)Thread.sleep(500);
    healthy=Files.exists(ready);Files.deleteIfExists(ready);
    if(healthy){Thread.sleep(3000);healthy=child.isAlive();}
    if(!healthy){
     if(child.isAlive()){child.destroy();if(!child.waitFor(30,TimeUnit.SECONDS))throw new IllegalStateException("Runner did not stop; refusing to replace it");}
     if(!active.equals(bundled)&&!rolledBack){atomic(home.resolve("failed-update-version.txt"),active);active=text(updates.resolve("previous.txt"),bundled);activate(active);rolledBack=true;continue;}
     throw new IllegalStateException("Bundled runner did not start");
    }
    int exit=child.waitFor();child=null;
    if(exit==75){
     String pending=text(updates.resolve("pending.txt"),active);
     if(compare(pending,active)>0 || approvedRollback(pending,active)){verified(pending);atomic(updates.resolve("previous.txt"),active);active=pending;activate(active);Files.deleteIfExists(updates.resolve("pending.txt"));Files.deleteIfExists(updates.resolve("rollback-approved.txt"));rolledBack=false;}
    }else Thread.sleep(10000);
   }
  }
 }
 public static void main(String[] args)throws Exception{run();}
}
