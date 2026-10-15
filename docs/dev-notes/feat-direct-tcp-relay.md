상태: 구현·범위 테스트 완료, 리뷰 대기 (실제 다중 컴퓨터·Mac·launchd/작업 스케줄러 검증과 일부 연결은 미완료 — 아래 "남은 위험")
# feat/direct-tcp-relay

- 기준 커밋: `origin/main` 2bf9368 (VERSION 0.4.14). main에 없는 `release/0.5.1`(ad2bc9c)을 `git merge --no-ff`로 먼저 병합해 기준을 맞췄다(병합 커밋 acd12c1). 0.5.0·0.5.1의 Qwen 실행기, Qwen 웹 설정, 모델 단일 적재, 음악 기능은 그대로 포함된다. 따라서 `main` 대비 diff에 보이는 `VERSION`, `docs/releases/*`, `docs/DEVELOPMENT.md`, `frontend/*`, Discord/LocalLlm 변경은 **이 병합이 가져온 것이며 이 브랜치에서 직접 수정하지 않았다.**
- 최종 구현 커밋: `1b807b5` (이 노트는 그 다음 커밋). 브랜치 `feat/direct-tcp-relay` — https://github.com/yoonseo6399/ServerManager/tree/feat/direct-tcp-relay . PR은 만들지 않았다(요청 시 draft로 생성 가능). main 병합·태그·릴리즈·배포 없음.
- 점유: 시작 시 `agent-status.sh` 확인(겹치는 파일은 release/0.5.0↔0.5.1 사이뿐) 후 브랜치를 먼저 푸시.

## 구조
1. **직접 중계 DRP1** (`DirectRelay.kt`): 게임 실행기가 중계 실행기의 **연결 포트(기본 6400)** 로 먼저 접속한다. 티켓 `drp1|relay|agent|epoch|만료|nonce` + `HMAC(relaySecret, …)`. 티켓 키는 소켓으로 전송되지 않고 양쪽이 핸드셰이크(HMAC 상호 증명 + X25519 + AES-256-GCM, 방향별 키, 카운터 nonce)에서 증명한다. 중계 허브는 서명된 정책(`RelayPolicy`: 승인된 에이전트→epoch, 호스트명→(에이전트,인스턴스))에 있는 경로로만 전달하며 임의 host/port는 연결하지 않는다. 오래된 epoch·만료·재사용 nonce·다른 중계용 티켓·정책 세대 역행은 명시 사유(`STALE_EPOCH`, `EXPIRED`, `REPLAYED`, `WRONG_RELAY`, `STALE_POLICY`…)로 거부.
2. **관리자 서비스** (`DirectRelayManager.kt`, `/api/admin/direct-relay`): 티켓 발급(승인된 에이전트·동의한 연결 중 중계·경로 존재 확인), 정책 푸시, 준비 상태 판정, `prepare → commit`(신규 연결만 새 경로, 이전 대상은 `graceSeconds` 유지 후 드레인), `cut`(즉시 차단·epoch 증가·영향 접속 수 표시), 외부 검사(설정된 호스트·포트만, 10초 1회, 결과는 "관리 서버 위치에서의 시험"). 관리 앱 재시작 중에도 기존 터널은 유지되고 새 인증은 복구 후 처리(epoch는 디스크에 보존).
3. **실행기 동의** (`DirectRelayRunner.kt`, `RunnerSetup.java`): `direct-relay.json`. 신규 기본 꺼짐. 설치 화면 체크박스·`--allow/--deny-tcp-relay`·`--runner-relay-consent=allow|revoke|status`. 기존 `tcp-relay-enabled`/환경변수 허용은 자동 이전(출처 표시). 로컬 철회는 약 1초 내 허브를 닫고, 원격 설정이 로컬 동의를 우회할 수 없다.
4. **상주 게임 서비스** (`GameHost.kt`, `GameHostLink.kt`): `--game-host`로 실행되는 별도 JVM이 게임 프로세스·stdin/콘솔·직접 중계 소켓을 소유. 루프백 TCP, 챌린지–HMAC 인증, 컨트롤러 1개(중복 거부, 무응답 임대 만료), 시퀀스 기반 콘솔 재생, 중복 시작/명령 ID/잘못된 PID/종료 미확인 재시작 거부, 호스트 재시작 뒤 생존 게임은 `ORPHAN_ALIVE_NOT_CONTROLLABLE`로만 보고(복제 금지). 관리 Mac 로컬 게임도 같은 경로(`ServerManager.localLauncher`, `HostedLocalGamesService`).
5. **분리 모드** (`HostModeStore`): `SERVICE` / `PENDING_NORMAL_STOP`(구 방식 게임이 살아 있으면 건드리지 않고 정상 종료 뒤 전환) / `LEGACY`(개발 실행·운영자 `SERVER_MANAGER_GAME_HOST=off`·호스트 시작 실패 — 사유 표시). 호스트 시작 실패 시 게임 시작 전에 이전 방식으로 폴백(게임 도중 폴백 없음).
6. **업데이트** (`UpdateCoordinator.kt`, `RunnerMain.kt`): `UpdatePolicy`(대기 사유 포함 순수 판정), `HostLifecycle.preflight`(호환되지 않는 호스트가 게임을 소유하면 건강 신호 전에 종료 → 기존 `RunnerBootstrap`이 이전 버전으로 롤백), 유휴일 때만 호스트 교체(PID+시작시각 확인), `ActivationGuard` 시작 확인 저널, `UpdatePackageVerifier`(서명·버전·해시·매니페스트 호환 범위), 구성요소별 상태·내구성 로그(`updates/progress.log`, 비밀 마스킹), `GET /api/runners/{id}/update-status`.

## 프로토콜/API 계약, UI 예시 응답
→ [`docs/direct-relay-api.md`](../direct-relay-api.md) (Codex UI 담당자용 상세·예시 응답 포함).

## 변경 파일(이 브랜치가 직접 추가·수정)
- 신규: `DirectRelay.kt`, `DirectRelayManager.kt`, `DirectRelayRunner.kt`, `GameHost.kt`, `GameHostLink.kt`, `UpdateCoordinator.kt`, `docs/direct-relay-api.md`, 이 노트, 테스트 7종(`DirectRelayTest`, `DirectRelayManagerTest`, `DirectRelayProcessTest/Main`, `GameHostTest/ControllerMain`, `HostedLocalGamesTest`, `HostedRelayTest`, `UpdateCoordinatorTest`).
- 수정: `RunnerGateway.kt`(직접 중계 메시지 분기, 기존 Qwen/TCP 중계 경로는 보존), `RunnerMain.kt`(호스트 사용·재연결·업데이트 판정·동의 CLI), `RunnerRegistry.kt`(상태 캐시·API), `RunnerUpdater.kt`/`RunnerUpdates.kt`(VERIFYING 단계·호환 판정), `ServerManager.kt`(호스트 훅 `localLauncher`, `watch()` 분리, 종료 시 호스트 게임 유지), `Application.kt`(`--game-host`), `packaging/runner/RunnerSetup*.java`.
- 수정하지 않음: `frontend/**`, `VERSION`, `docs/releases/*`, `docs/DEVELOPMENT.md`, Qwen 하네스/AI 제공자.

## 마이그레이션 · 롤백 · 구버전 호환
- 구버전 실행기(0.5.1 이하)는 직접 중계 메시지를 모르므로 `capable`이 표시되지 않아 선택할 수 없다. 기존 0.4.14 방식(관리 Mac 경유) 중계 설정·코드는 그대로 남아 있다.
- 최초 서비스 분리: 구 방식 게임은 강제 종료·강제 인계하지 않는다. 구 실행기는 게임 종료 전에는 업데이트되지 않으므로 정상 종료 이후 업데이트 때 신버전이 `SERVICE`로 시작한다. 신버전이 구 방식 게임을 발견하면 `PENDING_NORMAL_STOP`.
- 롤백: 신 제어 계층이 호스트와 통신 불가이면 건강 신호 전에 종료(exit 3) → `RunnerBootstrap`이 `previous.txt`로 되돌림. 서명·해시 불일치는 기존 부트스트랩/업데이터가 거부. 호스트 프로토콜을 올리는 릴리즈는 매니페스트에 `controller.minHost/maxHost`, `host.protocol`을 넣어야 한다(없으면 1로 간주).
- 상주 서비스 자체·비호환 프로토콜 업데이트는 유휴까지 대기(`GAME_RUNNING`). 모든 구성요소가 무중단으로 교체된다고 주장하지 않는다.

## 실행한 테스트 (환경: Windows 11 Home 10.0.26200, Temurin 21, `./gradlew test --offline`, 단일 컴퓨터)
| 범위 | 결과 | 증거 |
|---|---|---|
| `DirectRelayTest` 11 | 통과 | 게임 바이트 관통, 포트 충돌 진단, 무등록/위조/오래된 epoch/만료/재사용/다른 중계 거부, 가짜 중계 거부, 임의 목적지 미연결(로컬 연결 0), 라우트 전환 시 기존 연결 유지, 철회 즉시 차단, 관리자 부재 중 터널 유지, 변조·재전송 프레임 거부 |
| `DirectRelayManagerTest` 10 | 통과 | 기본 꺼짐·원격 우회 불가, 허용/활성/준비 분리, 로컬 철회 뒤 재활성 불가, 재시작 후 동의 보존·레거시 이전, 미승인 에이전트 티켓 거부, prepare→commit 전환, cut, 관리자 재시작 복구, 포트 충돌 |
| `DirectRelayProcessTest` 1 | 통과 | **관리자·중계·게임 실행기 3개 별도 JVM**. 25.6 MB 게임 트래픽 동안 관리자 제어 채널 증가 **1,527 바이트**(`PROCESS-PROOF`). 관리자 프로세스 강제 종료·재시작 중 기존 연결 유지, 로컬 동의 철회 시 게임 포트 닫힘 |
| `GameHostTest` 10 + `HostLifecycleTest` 4 | 통과 | 제어 계층 교체 시 PID·콘솔 연속(손실·중복 없음, 시작 1회), 별도 JVM 컨트롤러 강제 종료 후 재접속, 중복 시작/명령/잘못된 PID/2번째 컨트롤러/무응답 임대/잘못된 비밀/프로토콜 불일치, 종료 미확인 재시작 거부, 호스트 재시작 후 복제 없음, 호환 불가 호스트 사전점검·유휴 때만 교체 |
| `HostedLocalGamesTest` 4 | 통과 | 관리 앱(로컬 게임) 종료·재시작 후 같은 PID로 재연결, 구 방식 대기→정상 종료 후 전환, 호스트 시작 실패 폴백, 컨트롤러 종료가 게임을 죽이지 않음 |
| `HostedRelayTest` 1 | 통과 | 중계 허브·게임 터널을 상주 서비스가 소유 → 양쪽 제어 계층이 동시에 교체되는 동안 기존 플레이어 연결 유지, 신규 접속 가능, 게임 재시작 없음 |
| `UpdateCoordinatorTest` 11 | 통과 | 대기 사유별 판정, 전송 중 체크포인트 정지·dirty 보호 유지, 상태 API 필드·재시작 후 로그·같은 jobId, 비밀 마스킹, 서명 변조/패키지 변조/다른 키/버전 불일치/다운그레이드/매니페스트 오류 거부, 시작 확인·시간 초과·버전 불일치·반복 실패 롤백 |
| 설치기 | 통과 | `RunnerSetupTest`(기본 꺼짐·옵션·재설치 시 동의 유지·레거시 표시), `RunnerSetupUiTest`로 렌더링한 설치 화면에서 체크박스 기본 꺼짐 확인(스크린샷 첨부) |
| **전체 백엔드 회귀** | 기준선과 동일 | 내 브랜치 335개 중 66 실패, 병합 직전 기준선(acd12c1) 283개 중 66 실패. **실패 클래스·개수가 완전히 동일**(21개 클래스). 원인은 이 PC(Windows) 한계: `UnsupportedOperationException`(POSIX 권한) 36, `실행 가능한 Java 경로를 지정하세요`(java.exe) 21, 심볼릭 링크 권한 등. 0.5.1 Qwen 전달·모델 선택(`RunnerGatewayTest` 9, `LocalLlmTest` 8, `RunnerTcpRelayTest` 2, `RunnerUpdateTest` 5, `RunnerReceiptRecoveryTest` 3, `RunnerTransferTest` 12, `RunnerIdleStopTest` 1)은 전부 통과 |

## 실패 · 미실행 · 남은 위험
- **미실행**: 서로 다른 실제 컴퓨터·공유기 포트포워딩·공개 DNS·외부 인터넷에서의 접속, macOS 실행(launchd), Windows 작업 스케줄러 환경에서 상주 서비스가 로그인 작업 종료 후에도 살아남는지, 프런트엔드 빌드·Playwright(미수정), `./gradlew bootJar`·패키징·`test-bootstrap.py`·CI. 위 66개 실패는 이 PC에서 기존부터 실패하며 Mac/Linux에서의 재실행이 필요하다.
- **상주 서비스 분리 실행(`spawnDetached`)**: Mac은 `nohup` 분리, Windows는 WMI `Win32_Process.Create`를 쓰도록 작성했지만 **실제 설치판·작업 스케줄러·launchd에서 검증하지 않았다**(테스트는 호스트를 테스트 JVM/별도 JVM으로 직접 띄움). launchd plist에 `AbandonProcessGroup` 추가 등 설치기 보강이 필요할 수 있다.
- 연결 안 된 부분: ① `MANAGER_APP` 구성요소 상태는 자리만 있고 관리 앱 업데이트 코드(`AppUpdates`)와 연결되지 않음. ② 호스트 소유 게임에서 `RunnerAgent`의 실제 `stop`/`forceStop` 흐름과 `processes` 맵을 이용한 기존 경로는 코드상 호환되지만 **RunnerAgent 전체를 호스트 모드로 구동하는 통합 테스트는 없음**(호스트·ServerManager·중계 각각의 테스트로 대체). ③ 호스트 프로세스가 죽거나 재시작되면 그 시점의 게임은 stdout 소비가 끊기므로 호스트 교체는 유휴일 때만 허용(구현됨)하며 호스트 비정상 종료는 복구 불가. ④ `hosted.relayForward` 실패 시 로컬 역할로 폴백하므로 호스트 연결이 불안정한 순간에 허브가 이중으로 생길 수 있음(포트 충돌로 거부될 것으로 예상, 미검증).
- 상주 서비스 콘솔 로그는 컨트롤러 비정상 종료 시 최대 약 1초 분량이 재생되어 `console.log`에 중복될 수 있다(손실은 없음, 호스트 링 버퍼 5,000줄 범위 내).
- 외부 도달성은 관리 서버 위치에서의 TCP 접속 시험만 제공하며 플레이어 쪽 인터넷 성공을 보증하지 않는다(`UNVERIFIED` 기본).
- UDP(Sable)는 범위 밖이며 기존 제약 유지: 직접 중계 모드에서 UDP 중계는 지원하지 않는다.
- 기존 `ModManagementTest` 등 Windows 한계 테스트는 이 브랜치 변경과 무관하게 실패한다.

## 원본 운영 환경 변경 여부
없음. 운영 Minecraft·사용자 공유기·DNS·계정·설치된 앱·서명 키는 건드리지 않았고, 테스트는 임시 폴더·루프백 포트·가짜 게임 프로세스만 사용했다. 설치기 UI 렌더링은 `SERVER_MANAGER_RUNNER_HOME`을 임시 폴더로 지정해 실행했다.

## 릴리즈 노트용 한 줄
- 중계 실행기를 직접 경유하는 TCP 중계(관리 Mac은 인증·권한만 담당)와, 제어 계층·관리 앱을 업데이트해도 게임과 중계 연결이 유지되는 상주 서비스 기반을 추가했다.
