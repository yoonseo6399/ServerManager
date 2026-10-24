상태: 구현·Mac 운영 적용·범위 검증 완료, Windows 공식 보안 런타임 미게시로 새 공개 릴리즈는 미생성
# Java October 2026 security runtime update

- Latest published beta.25 is the base; solo implementation. No PHP runtime found in ServerManager or the two inspected Macs; user explicitly excluded PHP work on this computer.
- Require Temurin Java 21.0.13 or newer within Java 21. Keep Minecraft's Java major compatibility. Authenticate archive bytes against official SHA-256 and refuse an older vendor rollout per architecture (Windows API currently reports 21.0.12+101).
- CVE-2026-91403 requires an additional trust-store mitigation, even when a runtime has the new quarterly version. Follow https://ops.java/emudhra: remove emSign ECC Root CA - G3 and AAA Certificate Services by certificate subject, independent of vendor alias. Harden staged cacerts and optional jssecacerts, verify again, record mitigation in package metadata. Affected roots are deliberately removed rather than disabling TLS validation.
- JAR updates preserve bundled Java. Existing installations need runtime replacement, not only the app JAR. Prepare downloaded runtimes and trust validation before a user-authorized graceful Minecraft/runner/manager restart. Back up old bundles outside published packages; preserve profiles/worlds. Never replace files under a live JVM.

## Planned verification (committed before execution)

1. Focused Python/real JVM tests: baseline rejection, vendor rollout refusal, publisher validation, real cacerts hardening, idempotence, symlink/missing store refusal.
2. Official archive checksum, staged release version, independent default TrustManager issuer check, outbound HTTPS smoke test on Apple Silicon and on the user's Intel manager.
3. Compile/package manager and Mac runner with new metadata and trust stores. Whole backend suite and other OS execution omitted; do not claim Windows patched if upstream version is below the baseline.
4. Before live replacement confirm game identities and normal stop; replace only stopped runtime directories with backups. Restart bootstrap/controller/host and previously running game; verify new process java.home/version, online heartbeat and Minecraft startup. Report any component not restarted as pending.
5. No published ServerManager asset re-download/hash test; no OS GPU memory changes.

## Sources

- https://www.oracle.com/security-alerts/alert-cve-2026-91403.html
- https://ops.java/emudhra
- https://www.oracle.com/security-alerts/cpuoct2026.html
- https://www.php.net/archive/2026.php#2026-10-21-5 (applicability check only)

## Verified results — 2026-10-24

- Official Adoptium Mac aarch64/x64 JRE and JDK 21.0.13+9 archives downloaded and checked against the publisher SHA-256. New runtime stores already omit the affected roots; both staged and installed runtime checks passed. Old local bundled 21.0.12.1 failed the independent OpenJDK CheckCerts check (affected emSign root present).
- Focused runtime suite: 8 passed. Synthetic real X.509 certificate subjects with unfamiliar aliases exercise removal of both affected CAs, followed by idempotence/check tests. Initial count assertion also counted roots already present in the old development JDK; normalize the fixture before adding two synthetic certificates. Original failure retained in task output; do not claim the first run passed.
- Mac manager PKG and CLI/UI runner ZIP packaging passed using the unchanged existing beta.25 application JAR. These are local verification artifacts only, never published as replacement beta.25 assets. Isolated manager startup and runner enrollment/standby smoke checks passed. Manager smoke cleanup initially left its resident JVM briefly after asynchronous launchd removal; wait for the exact job PID to exit. Final isolated rerun: leftover test JVMs 0.
- Installed Temurin JDK 21.0.13 in each user's Library/Java/JavaVirtualMachines. This Mac's default /usr/bin/java now selects 21.0.13. Other old SDK installations were not deleted; iMac's generic /usr/bin/java still selects its unrelated JDK23. ServerManager uses its bundled Java explicitly.
- Replaced both x64 and aarch64 bundled Java directories in the local runner and Intel manager, only after their relevant JVMs stopped; preserved old directories in external SecurityBackups. Current application version stays beta.25. No new public release was created: official Adoptium Windows latest JRE/JDK API returned 21.0.12+101 and exact queried 21.0.13+9 JRE was unavailable, so a new all-platform package must wait or use a separately reviewed vendor source.
- Manager bootstrap/app/host restarted, local HTTP and public https://server.enclody.sarl/api/auth/session 200; original profile retained, setupRequired=false. Actual running manager app PID23307 and host23320 report JVM21.0.13 via jcmd VM.version. Local host41219/bootstrap41239/controller41246/game41237 all report JVM21.0.13.
- Let's Create Survival (474f040a-08f3-424e-bc6c-4186721bef37): authenticated host stop, old game PID554 exited0. New game PID41237 reached Done at 15:18:49 KST and player reconnect appeared in actual latest.log. Rebuilt documented NeoForge launch arguments from saved server settings, without harvesting process credentials or environments; recreated bridge registration through the authenticated host API. Console sequence reset for the new host's sequence space, original marker copies backed up.
- Existing installed bootstrap auto-respawned its controller. Stop its precise launchd session first. Controller40795 then blocked in shutdownGracefully -> uploadStopped -> uploadSnapshots; exact thread dump captured locally. After verifying no live game/child, only the stuck controller was force-terminated, leaving disk queue/snapshots untouched. Queue-lock shutdown behavior is a separate existing product issue, not fixed or represented as fixed by this Java packaging patch.
- User authorized Minecraft/service restarts and explicitly excluded PHP. No PHP runtime/service found in inspected locations. Full backend suite, Windows execution, other SDK migrations and published asset re-download/hash tests omitted.
