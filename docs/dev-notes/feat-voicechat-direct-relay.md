상태: v0.7.0-beta.26 통합·게시 완료, 운영 자동 적용 없음
# Simple Voice Chat direct relay

- Beta25 published base, solo. Add per-instance UDP public/local voice ports under direct relay settings; same selected relay runner and authenticated encrypted DRP tunnel, no manager payload. Separate Sable protocol and namespace; UDP has no DNS hostname, so unique public port per instance. The router must additionally forward selected UDP ports.
- New capability DRP1_VOICE1 required on relay/game resident hosts. Local relay consent and allowed-instance/ticket/epoch gates retained. Only loopback destinations chosen by approved settings, live Minecraft TCP peer-IP admission, bounded queues/sessions/packets and expiry. Voice mod retains its own client secret authentication. TCP head-of-line can increase voice latency.
- On next normal game start prepare only port/bind_address/voice_host in known mod/plugin config, preserving other settings/comments and keeping originals outside server files. Disable restores managed original values at next start, preserving user changes. No automatic mod installation, live server restart or router/DNS edit.
- Persist revision-bound voice feature sidecar, omit empty wire fields for older layouts. UI shows per-server ports, forwarding rows, pending configuration/capability and measured UDP roundtrip states separately.

## Test plan (commit implementation and plan before executing)

1. Actual authenticated TCP tunnel + UDP echo: upstream/downstream, two instance routing, no TCP player denial, disconnect/revoke/disable cleanup, UDP port conflict leaves game TCP working, malformed/oversize envelopes and configured loopback destination only.
2. File preparation: original CRLF/comments/unknown keys survive; backup; idempotence; changed endpoint; disable restore; preserve manual changes; external symlink/duplicate/invalid JSON/config and query-port conflict rejection.
3. Manager capability/route/port validation, local-port collisions on same runner, revision/rollback sidecar and restart persistence, prepare/commit and readiness.
4. Focused relay/host regression plus frontend TypeScript/Vite, mocked browser UI save/forwarding/capability checks. No full suite, OS matrix, production game restart, router changes or real microphone audio test.

Official references: https://modrepo.de/minecraft/voicechat/wiki/server_config and https://modrepo.de/minecraft/voicechat/wiki/server_setup_self_hosted . Default voice UDP24454, independent of game networking. Voice host may include public hostname:port.

## Final results

- Backend focused64 passed, failures0/skips0, including18 new voice cases. Native authenticated encrypted tunnel + two independent UDP destinations, live-TCP-IP gate and close, route revoke/disable, UDP bind conflict with surviving TCP, malformed envelopes, shared TCP/UDP public port number, co-located relay/game port collision and candidate validation. Real resident-host process start prepares config before launch and restores on the next disabled start.
- Relevant browser8 unique passed; final changed voice/Sable/first-selection subset5 passed. UI fixtures verify upgraded-host gate, distinct public/private values, co-located internal default34454, forwarding table, pending restart and disabling after lost capability. Initial new test selected a portal SaveBar inside the wrong region; corrected test locator. Kotlin expression-body revocation test initially returned an exception object and was not discovered; explicit Unit makes it execute. Original failures preserved in tool logs; do not claim all initial runs passed.
- Existing runner-update visibility browser test fails identically on this branch and unmodified published beta25 (hidden offline state after disclosure click). Kept outside the final feature subset, no unrelated production UI change. This is not an all-browser-suite pass.
- TypeScript/Vite and final bootJar including built UI passed. Whole suite, other OS, production install/restart/voice-file edits, actual microphone/mod-client and real router/Internet audio test not run. No release tag/assets created. Future release must include the Java security runtime branch separately and verify official platform runtime availability.
- New public default UDP24454; on a shared relay/game computer use distinct local UDP34454 (auto-suggested). Sable OFF may instead use e.g. game TCP6700 + voice UDP6700, verified with actual sockets. Router UDP forwarding is still required. No extra relay secure TCP listener is introduced.
- Configuration and port requirements: docs/testing/voicechat-direct-relay.md; structured results in docs/testing/voicechat-direct-relay-results.json. Manager + runner controller + resident game host must all be updated before enabling; don't override local relay consent.
