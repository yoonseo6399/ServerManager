상태: 로컬 검증 완료; GitHub CLI 인증 복구 후 0.4.14 게시 검증 진행 중
# 0.4.14 candidate handoff

## Scope
- Base: published v0.4.13 / 2d0cbe9a95ec83b4c3d556a279b69d518738ffec.
- Feature: cherry-pick of 9a92cd8 (local Qwen chat and opt-in authenticated runner TCP ingress).
- main's unrelated Discord music changes remain excluded. No nav.ts/main.tsx/Console.tsx/Mods.tsx changes relative to v0.4.13.
- No production manager, runner or Minecraft lifecycle actions. Temporary manager startup used a fresh build/startup-profile and port 18788, then terminated that process.

## Verification
- Frontend TypeScript/Vite build PASS.
- Full Gradle test + bootJar PASS: 262 discovered, 255 passed, 7 skipped, 0 failures/errors.
- Includes fake Ollama HTTP request/response tests, real loopback TCP byte preservation/closure/port collision, selected relay → manager router → simulated game runner, and nonselected runner rejection.
- New Playwright relay selector test PASS against the built candidate.
- Existing nav tests: 2 PASS / 1 FAIL. The SAME tab Back failure at nav.spec.ts:31 occurs in an untouched detached v0.4.13 checkout. User requested inspection only; navigation implementation/tests were not changed. Do not claim all navigation behavior is verified.
- Shared runner installer tests PASS. Isolated bootstrap signature/tamper/rollback/profile tests PASS.
- Candidate JAR startup PASS: appVersion 0.4.14, setupRequired=true in isolated profile.
- Mac universal manager PKG and four Mac/Windows CLI/UI runner ZIPs generated. Packaged JAR hashes and pinned public key match source/build. Local package generation used bundled modern Python because system Python lacks tarfile extraction filters.
- No actual Qwen inference, live Discord call, external multi-computer routing or Windows runtime test. Memory and inference throughput remain unmeasured.

## Previous publication blocker (resolved for CLI)
- Local HTTPS git push: could not read Username (no credential).
- Connected GitHub branch creation: 403 Resource not accessible by integration. Metadata reports the account can push, but the active integration write request is denied.
- GitHub CLI browser login completed as repository owner yoonseo6399; credential is stored in the system keyring and Git authentication is configured. The earlier integration denial does not prevent CLI publishing.
- Prepublication check: public latest v0.4.13; no existing release/0.4.14 branch or v0.4.14 tag. No published assets were replaced.
- dist packages are UNSIGNED CANDIDATES with CHECKSUMS-UNSIGNED.txt, not a signed update set. No private release key was read/generated. Existing GitHub Actions release workflow signs using its configured UPDATE_SIGNING_KEY secret after tag push.

## Resume publication after write access is restored
1. Confirm public latest is still v0.4.13 and v0.4.14 does not already exist; if another release supersedes it, choose a new version and rebuild.
2. Push release/0.4.14; tag the validated final commit as v0.4.14 and push that tag.
3. Wait for signed release workflow and installer checks. Inspect failed jobs; never call an unpublished or failing workflow a completed release.
4. Verify the public release and all nine names/sizes/hashes, latest/non-prerelease, manifest signature and pinned public key.
5. Follow docs/WORKFLOW.md to merge the validated release into main without dropping main's unrelated work. Do not apply it to a running production app without checking live state and user scope.
