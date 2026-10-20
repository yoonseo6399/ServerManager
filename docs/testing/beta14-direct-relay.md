# Beta14 직접 TCP 선택·Sable UDP 검증

단독 구현. 사용자 지시대로 전체 suite, 다른 운영체제 실행은 생략. 운영 설정/DNS/포트/게임을 테스트에 쓰거나 변경하지 않음. 사용자 요청에 따른 별도 실행기 제어 계층 정상 재시작만 앞서 수행했고 게임호스트·게임 PID 유지 확인.

## 범위와 실제 실행

- `DirectRelayManagerTest`, `DirectRelayTest`, `HostedRelayTest`, `SableRelayTest`, `SableRunnerTest`, `DirectSableTest`, `RunnerHostIntegrationTest`, `RunnerGatewayTest`, `GameNetworkTest`, `RunnerRegistryTest`: 최초 최종 범위60건 통과, 실패/skip0. UDP bind 자동 재시도 후속 테스트1건을 추가하고 관리자/직접UDP/상주호스트23건 재실행도 전부 통과. 고유 기능 검증 합계61건.
- 등록 직후 보고 없는 실행기 목록, 최초 선택 persistence, 허용 없는 활성화 거부, 활성 대상 prepare/commit 강제, 재연결 동의 재조회, HostedGames callback 재설정, SRV용 공유게임포트 전달.
- DRP1 인증 실제 TCP 소켓과 Sable AUTH3/ACK5 → loopback UDP → server4/0 응답의 정확한 바이트 확인. 잘못된 instance/stream/token/다른 UDP source port 차단, TCP 종료 binding 정리, UDP OFF 및 UDP 포트 충돌 시 기존 TCP 유지.
- 상주 호스트 두 개와 fake game child process로 localhost HTTP 등록의 bearer키/실제TCP sourceport 인증. 모든 controller 연결을 끊고도 UDP/TCP 왕복 유지, 재접속 후 기존 helper URL/키와 새 UUID 등록 유지. 실제 Minecraft 프로토콜 서버 대신 격리 echo와 Sable 지원 metadata fixture 사용.
- 새 기능 capability가 없는 resident host 차단, UDP opt-in 별도 revision marker 저장/롤백 스키마 유지, per-instance 상태가 다른 서버의 성공을 빌려오지 않음, 포워딩 protocol/port 분리.
- Playwright6건 통과: 최초 선택/재로딩/지연 poll/동의철회/prepare-commit/conflict, UDP capability 대기→opt-in 저장→왕복 상태, common DNS visible / legacy route collapsed / direct PUT이 DNS를 수정하지 않음 / capability를 잃어도 UDP OFF 가능.
- TypeScript 및 Vite production build 통과. `scripts/test-runner-setup.py` 격리 실행 통과. 실제 설치/로그인 서비스 수정 안 함.

## 실패 원문과 한계

로컬 ignored `build/direct-sable-evidence/`에 로그/XML/UI 스크린샷을 보존했다. 최초 `udp-validated.log`에서 기존 TCP 로컬 철회 테스트의 첫 hello 왕복이 1회 실패했다. 단독 재실행, 최종 범위60개 및 관리자15개3회 반복은 통과했으나 원인은 확정하지 못했다. 해당 실패를 해결됐다고 단정하지 않는다. `initial-failure/` XML도 보존했다.

개발 중 신규 resident fixture의 `StartResult.pid` 참조 컴파일 오류를 실제 `process.pid()`로 수정했다. 신규 UDP 표 순서 기대와 실제 순서를 맞춰 게임TCP/게임UDP/별도연결TCP로 정리했다. UI substring selector가 두 label을 고른 실패는 서로 다른 이름으로 고쳤다. 각 최초 로그를 지우지 않았다.

실제 별도 컴퓨터·공유기·공개 인터넷·설치된 Minecraft/Sable 접속과 새 게임호스트 설치판 교체는 **미실행**. 이번 검증을 외부 연결 성공이나 종단 UDP 성능 개선의 증명으로 쓰지 않는다. 중계↔게임 실행기는 기존 암호화 TCP 터널이므로 TCP head-of-line 지연이 남는다. 이미 실행 중인 게임에는 helper를 주입하지 않고 다음 정상 시작부터 적용한다. 호스트 자체 교체는 기존 유휴 조건 유지.

## 게시 및 호환성

새 Beta14 prerelease로 게시, 기존 stable/main/latest 및 게시된 태그/파일 유지. 관리 앱·중계 실행기·게임 실행기·상주 호스트 모두 새 버전 필요. 기존 signing secret만 사용하며 전체 suite는 `skip_full_tests=true`로 생략한다. 게시 후 공개9assets의 digest, 다운로드JAR/manifest/signature와 pinned Ed25519 서명·내장 버전을 검증하고 별도 기록한다.

Beta14 게시 검증 완료: source7e96823, CI39421495305 SUCCESS(전체 suite skipped). 공개9assets digest, 다운로드JAR/manifest/signature·pinned Ed25519·내장0.7.0-beta.14/DRP1_UDP1/UI 확인. stable/latest v0.6.3 유지. 상세 beta14-release-verification.json.
