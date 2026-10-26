상태: v0.7.0-beta.26 통합·게시 완료, 운영 자동 적용 없음
# feat/multi-server-ports

- 기반: feat/voicechat-direct-relay e34c276. 기존 여러 인스턴스 실행 기능을 유지하며 같은 컴퓨터의 포트 충돌을 보완한다. 구버전에서 저장한 중복 포트도 다음 원격 시작 전 현재 서버만 재배정한다.
- 실행기 배정 때 해당 컴퓨터의 서버·중계 TCP·공개/내부 예약 음성 포트를 피해서 내부 게임 포트를 자동 배정한다. 다른 컴퓨터에서는 같은 내부 포트 사용을 유지한다. 실행 중인 다른 서버의 포트는 변경하지 않는다.
- 명시적인 포트 수정은 중복을 거부한다. 음성 포트가 겹치는 컴퓨터로의 이동은 설정 위치를 설명하고 거부한다.
- Query UDP 선택은 이미 선택했지만 아직 바인딩하지 않은 다른 서버의 Query·게임·음성 포트를 제외한다. 상주 호스트가 새 capability로 예정 음성 포트를 제어 계층에 전달한다. 구버전은 기존 경로를 유지한다.
- 웹은 같은 실행기에서 여러 서버를 운영하는 방법, 자동 배정된 내부 포트, 공개 접속·중계 설정 위치를 안내한다.

## 커밋 후 실행할 테스트

1. MultiServerPortsTest: 같은 실행기 자동 분리, 다른 실행기 포트 재사용, 실행 중인 서버 불변, 수동 중복 거부, 중계 예약 포트 제외, 범위 끝 순환·고갈, 기존 중복 설정의 시작 전 복구.
2. QueryEndpointTest: 복제한 같은 query.port를 두 게임이 바인딩하기 전에도 구분하고 음성·게임 예약 포트를 제외.
3. MultiServerGatewayTest / VoiceChatManagerTest: 이동 시 음성 중복·중계 겸용 충돌 차단, 같은 컴퓨터의 다른 게임 포트와 음성 포트 충돌 차단.
4. MultiServerHostTest: 격리된 호스트 한 개에서 게임 대역 JVM 두 개를 동시에 실행. 실제 TCP/Query UDP/음성 UDP 소켓을 서로 다른 포트에 바인딩하고 하나의 암호화 중계를 통해 동시 음성 왕복 40개 검증. A 종료 뒤 B PID·TCP·음성 유지.
5. 기존 ServerManagerTest, RunnerGatewayTest, GameHostTest, VoiceChatHostTest, VoiceChatRelayTest, DirectRelayManagerTest, DirectSableTest 범위 회귀.
6. 프런트 빌드 및 multi-server-ports / direct-voicechat / relay-first-selection 브라우저 테스트. 두 음성 서버 포트 분리·저장·새로고침, 배정 결과 포트 표시.

전체 테스트·운영 서버 재시작·실제 Minecraft/마이크/공유기/인터넷 시험은 하지 않는다. 새 릴리즈·설치 적용은 이 작업에 포함하지 않는다.

## 실행 결과 (2026-10-25, macOS arm64)

- 구현 커밋 `f69240d`, 추가 복구 커밋 `50e4f74`. 구현과 테스트 계획을 먼저 커밋하고 테스트를 실행했다.
- 최종 백엔드 관련 12개 클래스 **75개 통과 / 실패 0 / 제외 0**, Temurin 21.0.13. 전체 테스트는 실행하지 않았다.
- `MultiServerHostTest`가 같은 상주 호스트에서 독립 게임 대역 JVM 두 개를 실제 실행하고 서로 다른 게임 TCP·Query UDP·음성 UDP를 바인딩했다. 두 음성 경로로 40개 패킷을 동시에 왕복했고, A 정상 종료 후 B의 PID·TCP 연결·음성 왕복이 유지됐다.
- 최종 소켓 증거: 게임 A/B `54089 / 54090`, Query `55805 / 62495`, 내부 음성 `58334 / 62001`, 공개 음성 `52785 / 62645`. B PID `26767`은 격리된 시험 중 증거이며 운영 PID가 아니다.
- 프런트 TypeScript/Vite 빌드 성공. Playwright `multi-server-ports`, `direct-voicechat`, `relay-first-selection` 합계 **6개 통과 / 실패 0**.
- 결과 원문에서 추출한 집계·소켓 증거: `docs/testing/multi-server-ports-results.json`. 설정 방법: `docs/testing/multi-server-ports.md`.
- 실제 Minecraft 두 개·마이크 음성·공유기·외부 인터넷·다른 OS·운영 배포는 미실행. 기존 `direct-relay.spec.ts`의 별도 업데이트 로그 가시성 실패는 앞선 음성 작업에서 공개 beta.25에서도 재현한 무관 항목이며 이 범위에 포함하지 않았다.
- 운영 앱·실행기·Minecraft를 재시작하거나 설정을 변경하지 않았고 공개 릴리즈도 만들지 않았다. 릴리즈 시 기반 `feat/voicechat-direct-relay`와 앞선 별도 Java 보안 패치 `fix/java-security-runtime`의 통합 범위를 확인해야 한다.

## 릴리즈 노트 후보

한 실행기에 여러 서버를 배정할 때 내부 게임 포트를 자동 분리하고, 시작 준비 중인 서버의 Query·음성 포트 충돌을 예방했습니다. 기존 중복 설정은 다음 시작 때 해당 서버만 복구하며, 웹에서 서버별 음성 포트와 배정 결과를 확인할 수 있습니다.
