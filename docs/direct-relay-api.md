# 직접 TCP / Sable UDP 중계 · 상주 서비스 · 업데이트 상태 API (관리 웹 담당자용)

관리 웹의 직접 중계 카드와 공통 포트·DNS 카드가 아래 계약을 사용한다. 기존 관리 Mac 경유 설정은 공통 카드의 접힌 고급 설정에 있다.
모든 변경 요청(PUT/POST)은 기존과 같이 패스키 재인증(`freshDesktop`, 5분)이 필요하다. 응답은 `Cache-Control: no-store`.

## 1. 직접 중계 `/api/admin/direct-relay`

경로: 플레이어 → 선택한 중계 실행기(게임 포트) → 게임 실행기(중계 실행기의 연결 포트로 먼저 접속) → Minecraft.
관리 Mac은 티켓·권한·경로만 다루고 게임 바이트를 전달하지 않는다. 게임 포트와 실행기 연결 포트는 서로 다른 TCP 포트다.

| 메서드 | 경로 | 본문 | 설명 |
|---|---|---|---|
| GET | `/api/admin/direct-relay` | – | 전체 상태(`DirectRelayView`) |
| PUT | `/api/admin/direct-relay` | `{config, revision}` | 설정 저장. revision 불일치 시 거부 |
| POST | `/api/admin/direct-relay/prepare` | `{runnerId, agentHost, publicHost}` | 새 중계 대상을 준비(기존 경로는 그대로) |
| POST | `/api/admin/direct-relay/commit` | – | 새 대상이 ready일 때만 전환. 이전 대상은 `graceSeconds` 동안 유지 후 드레인 |
| POST | `/api/admin/direct-relay/cancel` | – | 준비 중인 대상 취소 |
| POST | `/api/admin/direct-relay/cut` | `{runnerId}` | 즉시 차단(동의 철회 등). `{impactedStreams}` 반환 |
| POST | `/api/admin/direct-relay/external-check` | `{runnerId}` | 설정된 중계 호스트·포트에만 접속 시험(임의 URL/포트 불가, 10초 1회). 결과 `REACHABLE_FROM_MANAGER` / `NOT_REACHABLE_FROM_MANAGER`는 "관리 서버 위치에서의 시험"이다 |

`config`: `{enabled, relayRunnerId, candidateRunnerId, securePort(기본 6400), agentHost, publicHost, retiring{runnerId:만료epochMs}, graceSeconds, relayHosts{runnerId:{agentHost,publicHost}}, sableUdpEnabled(기본 false)}`.
`agentHost`는 게임 실행기가 접속할 중계 주소, `publicHost`는 플레이어용 공개 주소(DNS 대상)다. 게임 포트는 기존 공유 중계 포트(`game-network.port`)를 쓴다.

### 예시 응답 (GET)
```json
{
  "revision": 3,
  "phase": "ACTIVE",
  "message": "직접 중계 준비 완료",
  "config": {"enabled": true, "relayRunnerId": "7b1c…", "candidateRunnerId": null, "securePort": 6400,
             "agentHost": "relay.example.com", "publicHost": "relay.example.com", "retiring": {}, "graceSeconds": 600, "relayHosts": {}},
  "relays": [{
    "runnerId": "7b1c…", "name": "친구 PC", "role": "ACTIVE",
    "connected": true, "capable": true,
    "consentAllowed": true, "consentSource": "installer", "consentMigrated": false, "consentLockedByEnvironment": false,
    "active": true, "ready": true, "streams": 2,
    "internalAddresses": ["192.168.0.12"], "publicHost": "relay.example.com", "gamePort": 6399, "securePort": 6400,
    "router": [
      {"label": "게임 접속 (플레이어 → 중계)", "externalPort": 6399, "internalAddress": "192.168.0.12", "internalPort": 6399},
      {"label": "실행기 연결 (게임 실행기 → 중계)", "externalPort": 6400, "internalAddress": "192.168.0.12", "internalPort": 6400}],
    "diagnostics": {
      "consent": {"status": "OK", "detail": "로컬 허용: installer"},
      "portConflict": {"status": "OK", "detail": "게임 포트와 연결 포트를 열었습니다"},
      "agentConnection": {"status": "OK", "detail": "게임 실행기 1대 연결"},
      "dnsTarget": {"status": "RESOLVED", "detail": "relay.example.com → 203.0.113.9 (이 실행기 소유 주소인지는 검증하지 않습니다)"},
      "externalReachability": {"status": "UNVERIFIED", "detail": "인터넷 바깥에서의 접속은 검증하지 않았습니다. …"}},
    "impactIfCut": "즉시 차단하면 진행 중인 접속 2개가 끊깁니다"
  }],
  "agents": [{"runnerId": "a41f…", "name": "게임 PC",
              "relays": [{"relayId": "7b1c…", "state": "CONNECTED", "reason": null, "streams": 2, "connectedAt": 1760000000000}]}]
}
```
값 범위: `phase` = OFF | PREPARING | CANDIDATE_READY | ACTIVE | WAITING. `role` = ACTIVE | CANDIDATE | RETIRING | NONE.
`portConflict.status` = OK | CONFLICT | CLOSED | UNKNOWN. `agentConnection.status` = OK | WAITING | NO_ROUTE | UNKNOWN.

### UI 요구사항
- **허용(consentAllowed)·활성(active)·준비(ready)를 별도 표시 세 개**로 보여 준다. 로컬 허용이 켜진 실행기만 선택할 수 있다(허용 없이 저장하면 API가 거부).
- `externalReachability`가 `UNVERIFIED`이면 "미검증"으로 표시한다. 로컬 포트가 열렸다는 이유로 "인터넷 접속 성공"이라 쓰지 않는다. 외부 검사는 `external-check` 하나뿐이다.
- `router` 행을 "공유기 포트포워딩에 입력할 외부 포트/내부 주소/내부 포트"로 그대로 보여 준다. 공유기·방화벽·DNS는 자동으로 바꾸지 않는다.
- 게임 실행기 상태 `state` = IDLE | CONNECTING | CONNECTED | REJECTED | MANAGER_UNAVAILABLE | UNREACHABLE. `reason`에 AUTH_FAILED / NOT_APPROVED / STALE_EPOCH / EXPIRED / REPLAYED / NO_APPROVED_ROUTE / RELAY_CONSENT_DENIED 등 명시적 원인이 온다. 중앙 Mac으로 몰래 우회하지 않는다.
- 전환은 `prepare` → `phase=CANDIDATE_READY` 확인 → `commit`. 즉시 차단(`cut`)은 `impactIfCut`를 확인 대화상자에 보여 준 뒤 실행한다.

### 실행기 로컬 동의
설정 파일을 직접 만들 필요가 없다. 설치 화면 체크박스 "관리 웹 소유자의 TCP 중계 사용 허용"(신규 기본 꺼짐), 설치 CLI `--allow-tcp-relay` / `--deny-tcp-relay`, 실행기 CLI `--runner-relay-consent=allow|revoke|status`.
기존 `tcp-relay-enabled=true`·`RUNNER_TCP_RELAY_ENABLED=true`는 자동 이전되어 `consentMigrated` / `consentLockedByEnvironment`로 표시된다. 철회는 관리자 개입 없이 약 1초 안에 중계 소켓을 닫는다.

### 직접 Sable UDP (Beta14)

플레이어의 UDP → 선택한 중계 실행기 → 인증·암호화 DRP1 **TCP** 터널 → 게임 실행기의 `127.0.0.1:게임포트` UDP이다. 관리 Mac은 등록·권한·상태만 전달한다. 순수 종단 UDP가 아니므로 TCP 손실 시 후속 패킷이 기다릴 수 있다. 게임 데이터가 관리 Mac을 거치던 별도 WSS 경로와 구분한다.

- `config.sableUdpEnabled=true`로 명시적으로 켠다. TCP는 기본 경로이며 UDP 오류가 TCP 리스너/기존 연결을 끊지 않는다.
- 중계·게임 실행기 **상주 게임호스트**가 인증된 `relay-consent` / `drp-status`에 `version=DRP1_UDP1`을 보고해야 켤 수 있다. 제어 계층만 업데이트하고 구버전 상주 호스트가 살아 있으면 지원으로 간주하지 않는다. 재접속 시 보고를 다시 기다린다.
- `DirectRelayView.sableUdpBlockers`: 업데이트/연결 확인이 필요한 게임 실행기 이름. `relays[].sableUdpCapable`, `sableUdp{state,connections,dropped,message}`로 지원 여부와 실제 UDP 왕복 상태를 구분한다. 인스턴스 조회는 해당 인스턴스의 상태만 반환한다.
- `router[].protocol`은 TCP 또는 UDP. **공유 게임 포트 TCP + 같은 번호 UDP + 별도 실행기 연결 포트 TCP**를 중계 컴퓨터로 포워딩한다. 예: 게임6700이면 TCP6700/UDP6700, 실행기연결6400이면 TCP6400. `external-check`는 TCP만 검사하며 UDP 외부 도달을 증명하지 않는다.
- SRV `_minecraft._tcp.<서버주소>`는 공유 게임 포트다. 서버별 서브도메인을 TCP handshake로 식별한 뒤 UDP 토큰을 그 실제 TCP stream에 묶는다. UDP packet에 hostname을 새로 넣거나 모든 서버로 broadcast하지 않는다.
- NeoForge1.21.1/Sable2.0.6 지원. 보조 모드·localhost 등록 키는 다음 정상 서버 시작 때 준비한다. 이미 실행 중인 JVM에 보조 모드를 주입하지 않으며 강제 재시작하지 않는다. helper HTTP와 키는 상주 호스트가 소유하여 제어 계층 재접속 후에도 유지한다.
- 원격 임의 주소로 UDP를 보내지 않는다. TCP stream 소유권·instance·정책·ticket epoch·실제 TCP peer IP·일회 등록 UUID를 검증한다. client AUTH3/ACK5와 server0/1/2/4만 전달, 500등록/64pps·유실형64queue/100msTTL 유지. TCP 종료·정책철회·로컬 동의 철회 시 UDP binding도 제거한다.
- 직접 중계를 처음 선택할 때는 `relayRunnerId`를 PUT 설정에 포함한다. 활성 대상을 바꿀 때는 prepare/commit을 사용한다. 로컬 동의 없는 실행기·오프라인·미확인 상태는 목록에 표시하되 선택하지 못한다.
- 로컬 허용은 설치기의 “게임 중계 사용 허용 (TCP / Sable UDP)” 또는 기존 호환 CLI로 설정한다. 기본은 꺼짐, UDP는 관리 웹에서 추가로 선택한다. 수동 파일 작성은 필요 없다.
- 새 UDP opt-in은 기존 settings.json에 추가하지 않고 revision과 묶인 별도 내부 marker로 보관하여 Beta13 스키마 롤백을 지원한다. 쓰기 중단/구버전 설정 변경 시 UDP는 기본 꺼짐으로 복구한다. 이것은 사용자 직접 편집용 설정이 아니다.

상주 호스트 자체 업데이트에는 기존 유휴 조건이 필요하다. 구버전 호스트가 활성 중계 역할 때문에 대기하면 정상 점검 시간에 직접 중계를 끄고 게임·전송을 종료한 뒤 호스트 업데이트를 적용한다. 이후 새 직접 UDP 설정을 저장하고 게임을 시작한다. 설치판/실제 Minecraft·공유기·인터넷의 연속성은 별도 현장 검증 대상이다.

## 2. 업데이트 상태 `GET /api/runners/{id}/update-status?after=<seq>`
```json
{"available": true,
 "components": [
  {"component": "RUNNER_CONTROL", "current": "0.5.1", "target": "0.5.2", "stage": "WAITING",
   "waitReason": "LEGACY_GAME_RUNNING", "error": null, "updatedAt": "2026-10-16T01:00:05Z", "jobId": "job-runner-control-0.5.2-1"},
  {"component": "GAME_HOST", "current": "0.5.1", "target": null, "stage": "IDLE", "waitReason": null, "error": null, "updatedAt": "", "jobId": null},
  {"component": "MANAGER_APP", "current": "unknown", "target": null, "stage": "IDLE", "waitReason": null, "error": null, "updatedAt": "", "jobId": null}],
 "log": [{"seq": 4, "time": "2026-10-16T01:00:05Z", "jobId": "job-runner-control-0.5.2-1", "component": "RUNNER_CONTROL", "stage": "WAITING", "message": "대기: LEGACY_GAME_RUNNING"}],
 "hostMode": {"mode": "SERVICE", "reason": null, "since": "…", "legacyGames": []}}
```
- `stage` = IDLE | CHECKING | DOWNLOADING | VERIFYING | WAITING | APPLYING | RECONNECTING | DONE | FAILED.
- `waitReason`: LEGACY_GAME_RUNNING(최초 분리는 다음 정상 종료 뒤), GAME_RUNNING(상주 서비스 교체는 유휴 때), HOST_PROTOCOL_INCOMPATIBLE, HOST_UNREACHABLE, RELAY_STREAMS_ACTIVE, SERVER_PREPARING, TRANSFER_ACTIVE, TRANSFER_NOT_RESUMABLE, DIRTY_HANDOFF_PENDING, APPLY_WHEN_SAFE, HOST_REPLACED_WHEN_IDLE.
- `hostMode.mode`: SERVICE(게임을 상주 서비스가 소유) / PENDING_NORMAL_STOP(이전 방식 게임이 정상 종료될 때까지 보류, `legacyGames` 목록) / LEGACY(개발 실행·운영자 해제·시작 실패, `reason` 표시).
- 로그는 실행기 디스크에 남아 재접속 후에도 읽을 수 있고 비밀 값은 `[redacted]`로 치환된다. 구버전 실행기는 `available:false`.
- 관리 앱 자체 상태는 `GET /api/admin/updates`의 `detail: UpdateDetail`로 제공한다. `MANAGER_APP`의 실제 단계·대기 사유·내구성 로그가 포함된다. 실행기별 상태의 MANAGER_APP 기본 행은 해당 실행기의 관리 앱 상태를 의미하지 않는다.
- 관리 앱 교체는 입증된 상주 게임/직접 중계 연결만 유지한다. 이전 방식 게임·중앙 TCP stream·진단·설치·전송·미완료 handoff는 대기한다. 저장 위치 변경에는 완전 유휴 조건을 적용한다.
- 호스트 자체 교체는 인증된 유휴 준비 확인을 필요로 한다. 이미 relay role이 활성화된 호스트의 교체는 연결 수0이어도 보수적으로 보류하며, 호환 불가 기존 호스트의 유휴 상태를 확인하지 못하면 새 제어 계층 시작을 실패시켜 롤백한다.

## 3. 상주 서비스 프로토콜 (참고)
- 진입점 `--game-host --host-home=<경로>`, 루프백 TCP, 줄 단위 JSON, 서버 챌린지 → HMAC 증명(`host/endpoint.json`은 0600). 프로토콜 `GameHostProtocol.VERSION=1`, `MIN_COMPATIBLE=1`.
- 컨트롤러는 동시에 1개. 두 번째는 `CONTROLLER_ACTIVE`로 거부, 응답 없는 컨트롤러는 15초 후 임대 만료.
- 서명된 업데이트 매니페스트의 선택 키 `controller.minHost` / `controller.maxHost` / `host.protocol`(기본 1)로 호환 범위를 선언한다. 범위 밖이면 게임이 살아 있는 동안 제어 계층 업데이트는 `HOST_PROTOCOL_INCOMPATIBLE`로 대기한다.
