상태: 음성 직접 중계·웹 설정·모드 설정 준비 구현 중, 선커밋 후 범위 검증 예정
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
