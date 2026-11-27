상태: 코드 구현 완료, 커밋 후 범위 테스트 예정
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
