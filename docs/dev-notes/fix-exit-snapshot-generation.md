상태: 범위 기능45개 통과, beta35 통합 준비
# 종료 보고·복사본 세대 보호와 JVM 크래시
- 운영: 2026-11-27 03:14 Spark native SIGSEGV(exit134), 03:16:41 Discord 시작 요청 이후 관리 remote-run과 실행기/ready token 불일치. 정확한 최초 상태 전이 이력은 기존 로그에 없어 미확정. 수동 상주 유지보수도 있었으므로 단일 원인으로 단정하지 않음.
- 미완료 lease를 가진 시작은 runtime 표시와 무관하게 거절하여 토큰 덮어쓰기 방지. 실패한 새 시작 토큰 정리.
- 복사본 준비 전에 종료 보고, 세대 검증과 영속 알림 중복 방지. 종료와 복사본 전송 완료를 구분.
- hs_err_pid*.log만 추가 수집/로그 뷰어/Discord 첨부 및 native 요약. 경로·링크·크기 제한 유지.
- HTTP409의 제한된 오류 사유 표시.
## 커밋 후 테스트 계획
- LiveRunnerOperationsTest: stale STOPPED+lease, 중복 종료/복사본, 잘못된 token, 재시작 및 기존 background transfer.
- NativeCrashReportTest, AutoRestartTest, DiscordErrorSummaryTest, LogFilesTest: 새 native 로그와 기존 crash report, 링크 거부, 알림 회귀.
- RunnerTransferTest/RunnerReceiptRecoveryTest: 기존 HTTP 종료·복사본 호환성.
- Kotlin/bootJar 및 프런트 빌드. 전체 suite/타OS 실기/운영 자동 재시작/공개 자산 재다운로드 해시검사 생략.
## Spark 조사
- 설치 spark1.10.124 native async-profiler3.0-3cf733d.
- hs_err: ThreadFilter::accept(int)+0x10 -> WallClock::threadEntry, x1=0xffffffff(-1). 11월20일 동일 스택 재발.
- 공식 async-profiler issue1143: macOS task_threads의0/-1을 걸러내지 못한 native crash, 수정cc76f05. 증거가 일치하나 라이브러리 수정 후 장시간 재발 방지는 미검증.
- https://github.com/async-profiler/async-profiler/issues/1143
- 첫 범위 실행: 44개 중43통과/1실패. 실제 HTTP fixture가 새 즉시 exit endpoint를 구현하지 않아404, 복사본 미생성. fixture에 실제 manager.remoteExited 추가하고 old/new manager exitProtocol 호환 검증도 추가한 뒤 재검증한다.
- 2차 범위45개 통과. 후속 코드 검토에서 ready 수락 후 응답 유실 시 lease가 이미 닫혀 즉시 exit 재보고409가 ready 재시도를 막을 수 있음을 발견: 즉시 보고 실패와 ready 복구를 분리하고 실제 HTTP 응답 유실+청크500+신규 실행 시나리오로 추가 검증한다.

- 최종 관련7클래스45개/실패0/오류0. 실제 HTTP ready 수락응답500유실→exit409→ready 재접수→chunk500→새 게임 실행 중 구 복사본 반영 포함.
- 운영 상태 백업 후 정지 상태의 세대만 맞춰 기존 beta34가 복사본을 수락함(lease 해제, pending1); 월드 변경·서비스/Minecraft 재시작 없음. 전송 완료는 별도 확인.
- Spark 공식 1.21.1 NeoForge 최신도1.10.124. 설치 JAR의 backgroundProfilerEngine=java 지원을 javap로 확인. 이번 앱 릴리즈는 Spark JAR나 사용자 모드 설정을 자동 변경하지 않음.
