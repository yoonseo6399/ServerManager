# Beta15 상주 호스트 교체 복구

운영 Mac에서 확인한 사실:
- 2026-10-20 13:35:09Z 첫 교체에서 Beta13으로 다시 붙어 실패. 두 번째 시도는30초 뒤 endpoint 없음으로 실패했으나 직후 새 Beta14 호스트가 시작됨.
- launchd job은 `keepalive`로 등록됨. 인증해 유휴 확인한 host PID만 SIGTERM하면 기존 프로그램/구버전이 자동 부활함. 임시 home의 실제 launchd 작업으로 수정 전 실패(expected113/actual0)를 재현함.
- 진단 시 실제 호스트14와 제어 계층 TCP 연결은 정상인데 업데이트 기록은 current13/target14/FAILED로 남음. `tryReplaceHost`가 현재 버전과 같으면 상태 갱신 없이 반환했음.

수정:
1. 유휴/게임/중계/인증PID·starttime 확인 후, home에서 유도한 launchd label의 실제 PID까지 비교하고 작업을 제거. 사용자가 쓴 임의 label 파일은 제거 대상으로 쓰지 않음. 다른 PID면 실패로 보류. 일반 비-launchd 서비스와 다른OS는 기존 process 종료 경로 유지. 강제 종료로 우회하지 않음.
2. 새 프로세스 준비를 monotonic 시간으로 최대30초 기다림. 이전 PID/다른버전/죽은PID/불일치 starttime/열리지 않은포트는 성공 아님. JAR 내장 버전과 endpoint 버전도 일치해야 함.
3. 인증된 현재 연결이 목표 버전이면 늦은 시작 이후 남은 FAILED를 DONE으로 갱신하고 전송. 이미DONE이면 반복 로그를 만들지 않고, 다른 버전 목표의 실패는 지우지 않음.

검증:
- HostReadinessTest4, HostReplacementTest6, GameHostSpawnTest2, RunnerHostIntegrationTest2, HostedRelayTest3, GameHostTest10: 합계27개 통과, fail/skip0.
- 실제 임시 launchd 교체→구 작업 제거→새 bootJar 시작→재접속→유휴 제거, stale PID/버전 차단 및 지연준비, 다른PID job 제거거부, 게임과 직접중계TCP/UDP 유지 회귀.
- 추가로 패키징 RunnerAgent에 실패 상태를 주입한 뒤 실제 인증된 호스트 연결로 DONE 복구하면서 게임PID/echo 유지 확인(관련6개 재실행).
- 전체 suite 및 다른OS 실행은 사용자 요청으로 생략. 운영 원문로그/비밀 값은 저장소에 넣지 않음. 수정 전 실패XML과 실행 로그는 ignored build/host-readiness-evidence/에 보존.

운영 적용은 공개 서명 자산 검증 후 기존 RunnerUpdater를 통해 예약하고 기존 게임·전송·호스트 유휴 gate를 유지한다. 파일 직접 편집으로 성공 표시를 만들거나 게임을 종료하지 않는다. 최초 확인 뒤 게임이 시작되어 수동 제어계층 재시작 시도는 사전 점검에서 중단되었고 kill은 실행하지 않았다. 실제 적용 결과는 게시 후 별도 기록한다.

## 게시·로컬 적용 결과

Beta15 게시/공개자산 검증 완료(source5c9f6b8, CI39434598162, stable/latest0.6.3보존). 공개 서명 검증된 코드의 기존 RunnerUpdater.install을 보수용 entry에서 호출해 서명·해시·호환성을 다시 검사하고 예약했다. 실행 중 제어계층이 자체gate로 적용했으며 수동kill/설정성공표시편집/호스트강제교체는 하지 않았다. RUNNER_CONTROL15 DONE/errornull; 기존 resident14 및 게임1개 PID/startidentity 유지. GAME_HOST14→15는 WAITING/GAME_RUNNING/errornull로 정상대기. 실제 운영 호스트15 교체는 게임 종료·파일인계 이후이므로 미실행, 동일 실제launchd교체 경로는 격리회귀에서 검증했다. 상세 beta15-release-verification.json.
