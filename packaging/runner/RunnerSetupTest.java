import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real filesystem/process tests; no login jobs or live runner profile are touched. */
public class RunnerSetupTest {
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    interface Checked {void run()throws Exception;}
    static void rejects(Checked body,String name)throws Exception {
        try {body.run();throw new AssertionError("Accepted "+name);} catch(java.io.IOException|IllegalArgumentException|OverlappingFileLockException expected) {}
    }
    public static void main(String[] args)throws Exception {
        System.setOut(new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err,true,java.nio.charset.StandardCharsets.UTF_8));
        for(String bad:List.of("http://example.com","https://a/b","https://u:secret@a","https://a?x=y","https://a/#runners","https://a:0","https://a:99999","file:///tmp/a")) rejects(()->RunnerSetup.origin(bad),bad);
        check(RunnerSetup.origin(" https://server.example.com:8443/ ").equals("https://server.example.com:8443"),"origin normalization");
        rejects(()->RunnerSetup.Options.parse(new String[]{"--code-stdin"}),"stdin without origin");
        rejects(()->RunnerSetup.Options.parse(new String[]{"--token=secret"}),"secret argument");
        check(!RunnerSetup.Options.parse(new String[]{"--no-autostart"}).autostart(),"startup OFF");
        check(RunnerSetup.escapeXml("A&B<\"'>").equals("A&amp;B&lt;&quot;&apos;&gt;"),"plist escaping");
        Path temp=Files.createTempDirectory("runner-setup-test-");
        try {
            Path source=temp.resolve("download"),target=temp.resolve("Applications/Runner & friend"),home=temp.resolve("profile");
            Files.createDirectories(source);
            if(RunnerSetup.WINDOWS) RunnerSetup.copy(Path.of(System.getProperty("java.home")),source.resolve("runtime"));
            Files.createDirectories(RunnerSetup.java(source,false).getParent());
            // A tiny fixture JAR is invoked through a real bundled-java path for code stdin tests.
            if(!RunnerSetup.WINDOWS) Files.copy(Path.of(System.getProperty("java.home"),"bin/java"),RunnerSetup.java(source,false));
            // java needs its runtime tree; the process fixture below uses a script on Mac instead.
            if(!RunnerSetup.WINDOWS) {
                String actual=Path.of(System.getProperty("java.home"),"bin/java").toString();
                Files.writeString(RunnerSetup.java(source,false),"#!/bin/bash\nexec '"+actual.replace("'","'\\''")+"' \"$@\"\n");
                RunnerSetup.java(source,false).toFile().setExecutable(true);
            }
            Files.writeString(source.resolve("runner-bootstrap.jar"),"bootstrap");Files.writeString(source.resolve("version.txt"),"0.4.0-beta.7");
            Files.copy(Path.of(args[0]),source.resolve("server-manager.jar"));
            Files.createDirectories(home);byte[] identity="existing-private-identity".getBytes();Files.write(home.resolve("identity.json"),identity);
            AtomicInteger configured=new AtomicInteger();List<Boolean> auto=new ArrayList<>();
            RunnerSetup.Services services=new RunnerSetup.Services() {
                public void configure(Path t,Path h,boolean a)throws Exception {check(Arrays.equals(Files.readAllBytes(h.resolve("identity.json")),identity),"identity preserved");configured.incrementAndGet();auto.add(a);}
                public void start(Path t,Path h){throw new AssertionError("Install must not start before enrollment");}
            };
            RunnerSetup.install(source,target,home,false,services,s->{});
            check(configured.get()==1 && auto.equals(List.of(false)),"configure auto OFF");
            check(Files.readString(target.resolve("runner-home.txt")).equals(home.toString()),"scheduled custom home");
            check(!RunnerSetup.relayAllowed(home) && RunnerSetup.Options.parse(new String[]{}).relay()==null,"TCP relay consent defaults to OFF");
            check(RunnerSetup.Options.parse(new String[]{"--allow-tcp-relay"}).relay()==Boolean.TRUE && RunnerSetup.Options.parse(new String[]{"--deny-tcp-relay"}).relay()==Boolean.FALSE,"relay consent options");
            RunnerSetup.writeRelayConsent(home,true,"installer");
            check(RunnerSetup.relayAllowed(home) && Files.readString(home.resolve("direct-relay.json")).contains("\"allowed\":true"),"relay consent recorded");
            RunnerSetup.install(source,target,home,false,services,s->{});
            check(RunnerSetup.relayAllowed(home),"reinstall keeps relay consent");
            RunnerSetup.writeRelayConsent(home,false,"installer");check(!RunnerSetup.relayAllowed(home),"relay consent withdrawn");
            Path legacyHome=Files.createTempDirectory(temp,"legacy-relay");Files.writeString(legacyHome.resolve("tcp-relay-enabled"),"true");
            check(RunnerSetup.relayAllowed(legacyHome),"legacy relay marker is shown as allowed");
            RunnerSetup.writeRelayConsent(legacyHome,false,"installer");check(!RunnerSetup.relayAllowed(legacyHome),"unchecking overrides the legacy marker");
            Files.writeString(source.resolve("version.txt"),"next");
            try(FileChannel lock=FileChannel.open(home.resolve("runner.lock"),StandardOpenOption.WRITE);FileLock held=lock.lock()) {
                rejects(()->RunnerSetup.install(source,target,home,true,services,s->{}),"active runner");
                check(Files.readString(target.resolve("version.txt")).equals("0.4.0-beta.7"),"running files unchanged");
            }
            rejects(()->RunnerSetup.install(source,target,home,true,new RunnerSetup.Services() {
                public void configure(Path t,Path h,boolean a)throws Exception {throw new java.io.IOException("fixture service failure");}
                public void start(Path t,Path h){}
            },s->{}),"OS configuration failure");
            check(Files.readString(target.resolve("version.txt")).equals("0.4.0-beta.7"),"rollback previous install");
            check(Arrays.equals(Files.readAllBytes(home.resolve("identity.json")),identity),"rollback preserves identity");
            Path unrelated=temp.resolve("unrelated");Files.createDirectories(unrelated);Files.writeString(unrelated.resolve("document"),"keep");
            rejects(()->RunnerSetup.install(source,unrelated,home,true,services,s->{}),"unrelated target");
            rejects(()->RunnerSetup.install(source,target,target.resolve("world"),true,services,s->{}),"nested data path");
            check(Files.readString(unrelated.resolve("document")).equals("keep"),"unrelated file");
            if(!RunnerSetup.WINDOWS) {
                Files.createSymbolicLink(source.resolve("escape"),Path.of("/tmp"));
                rejects(()->RunnerSetup.install(source,target,home,true,services,s->{}),"escaping package symlink");
                Files.delete(source.resolve("escape"));
            }
            char[] existingSecret="fixture-code-that-must-never-be-logged".toCharArray();
            rejects(()->RunnerSetup.pair(target,home,"https://example.com",existingSecret),"already registered");
            check(new String(existingSecret).chars().allMatch(c->c==0),"erase secret even on failure");
            Files.delete(home.resolve("identity.json"));
            char[] secret="fixture-code-that-must-never-be-logged".toCharArray();
            RunnerSetup.pair(target,home,"https://example.com",secret);
            check(new String(secret).chars().allMatch(c->c==0),"erase secret after success");
            check(Files.readString(home.resolve("identity.json")).equals("fixture-identity"),"pair stdout not needed");
            Files.delete(home.resolve("identity.json"));
            char[] failed="fixture-failing-code-that-is-secret".toCharArray();
            try {RunnerSetup.pair(target,home,"https://example.com",failed);throw new AssertionError("pair failure expected");}
            catch(java.io.IOException expected){check(!expected.getMessage().contains("fixture-failing"),"failure redaction");}
            check(!Files.exists(home.resolve("identity.json")),"failed pairing leaves no identity");
            check(new String(failed).chars().allMatch(c->c==0),"erase failed secret");
            Path existing=temp.resolve("existing Minecraft server");Files.createDirectories(existing.resolve("world"));
            Files.writeString(existing.resolve("server.properties"),"level-name=world\nserver-port=25565\n");
            Files.writeString(existing.resolve("paper-custom.jar"),"existing-jar");Files.writeString(existing.resolve("world/level.dat"),"original-world");
            String serverId=UUID.randomUUID().toString();
            var binding=RunnerSetup.Binding.of(serverId,existing.toString(),"paper-custom.jar",21);
            RunnerSetup.bindFolder(home,binding);
            Path bound=home.resolve("instances/"+serverId+"/server-folder.properties");
            Properties stored=new Properties();try(var stream=Files.newInputStream(bound)){stored.load(stream);}
            check(Path.of(stored.getProperty("path")).equals(existing.toRealPath()),"canonical custom path");
            check(Files.readString(existing.resolve("world/level.dat")).equals("original-world"),"binding doesn't copy or alter world");
            check(stored.getProperty("initial").equals("true"),"first start uses local world");
            rejects(()->RunnerSetup.bindFolder(home,RunnerSetup.Binding.of(UUID.randomUUID().toString(),existing.toString(),"",21)),"duplicate folder");
            rejects(()->RunnerSetup.Binding.of(serverId,existing.toString(),"../secret.jar",21),"escaping JAR");
            rejects(()->RunnerSetup.Binding.of(serverId,existing.toString(),"",8),"unsupported Java");
            Path worldLock=existing.resolve("world/session.lock");Files.writeString(worldLock,"lock");
            try(FileChannel channel=FileChannel.open(worldLock,StandardOpenOption.WRITE);FileLock held=channel.lock()) {
                rejects(()->RunnerSetup.bindFolder(home,binding),"active original world");
            }
            Files.writeString(home.resolve("instances/"+serverId+"/dirty"),"pending-transfer");
            rejects(()->RunnerSetup.bindFolder(home,binding),"unfinished handoff");
            rejects(()->RunnerSetup.install(source,target,home,true,services,s->{}),"installer unfinished handoff");
            Files.delete(home.resolve("instances/"+serverId+"/dirty"));
            if(!RunnerSetup.WINDOWS) {
                Files.createSymbolicLink(existing.resolve("escape"),temp);
                rejects(()->RunnerSetup.bindFolder(home,binding),"link in source folder");Files.delete(existing.resolve("escape"));
            }
            if(RunnerSetup.WINDOWS) {
                String label="local.servermanager.runner.test-"+UUID.randomUUID();
                var service=new RunnerSetup.SystemServices(label);
                try {
                    service.configure(target,home,true);
                    RunnerSetup.runPowerShell("""
                        $ErrorActionPreference = 'Stop'
                        $task = Get-ScheduledTask -TaskName $env:SM_SETUP_TASK
                        $expected = Join-Path $env:SM_SETUP_TARGET 'runtime\\bin\\javaw.exe'
                        if ($task.Actions.Execute -ne $expected) { throw 'Wrong runtime' }
                        if ($task.Principal.RunLevel -ne 'Limited') { throw 'Unexpected elevation' }
                        if ($task.Triggers.CimClass.CimClassName -ne 'MSFT_TaskLogonTrigger') { throw 'Wrong trigger' }
                        """,target,true,label);
                    service.configure(target,home,false);
                    RunnerSetup.runPowerShell("if (Get-ScheduledTask -TaskName $env:SM_SETUP_TASK -ErrorAction SilentlyContinue) { throw 'Task remains after OFF' }",target,false,label);
                    System.out.println("Actual isolated Windows task: windowless bundled Java, limited user, login trigger and startup OFF passed.");
                } finally {service.configure(target,home,false);}
            }
            System.out.println("Runner setup: URL/CLI validation, startup choice, active lock, staged copy, rollback, data preservation, symlink safety, enrollment/redaction and original-folder binding/locks/duplicates passed.");
        } finally {RunnerSetup.deleteTree(temp);}
    }
}
