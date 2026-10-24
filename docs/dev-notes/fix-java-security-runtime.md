상태: 구현 및 선커밋 테스트 계획, 검증 전
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
