# 원격 원본 관리·전송·로그·재시작 검증 (2026-10-23)

사용자 지시로 전체 백엔드 테스트는 생략했다. 단일 구현자가 각 변경 및 테스트 계획을 먼저 커밋한 뒤 기능 범위를 실행했다. 운영 Minecraft/실행기/관리웹은 재시작하거나 파일을 수정하지 않았다.

## 결과

- 관련 backend 고유 81건: 통과 80, 조건부 미실행 1, 실패 0. 마지막 수정 이후 변경 경로를 다시 검증했다. 대상: LiveRunnerOperations, RunnerWorkspace/Transfer/ReceiptRecovery/Folders/Gateway, ModManagement/InventoryCache/Artifacts, ServiceRestart/Access/RunnerServiceRestart, HostedLocalGames, WebDavSync, LogFiles, GameHostSpawn, HostReadiness.
- UI 고유 19건 통과: 실행·전송 중 파일 저장/모드 토글, 읽기 전용 사용자, 강제 활성/비활성화, 처음 시작 후 0/1200줄 뒤 크래시, 모바일 가로 스크롤·logger 그룹·프레임 Mixin, 새 크래시 파일 발견/다운로드 링크, 관리 앱/실행기 재시작·취소·새 boot 확인.
- TypeScript/Vite production build, Kotlin/bootJar 통과. 릴리즈 버전 빌드·패키지·서명·bootstrap 시험은 릴리즈 CI 결과로 별도 확정한다.
- 실제 Mac의 모드 234개, 804,135,748바이트 읽기 전용 스캔: 첫 986ms / 재조회 3ms, 재조회 읽은 파일 바이트 0, 해시 재사용 234. 실제 운영 모드 토글 전체 처리 시간 측정은 아니다.

## 데이터 보존 시험

실제 APFS 종료 복사본을 만든 뒤 원본 내용을 바꿔도 복사본 바이트는 보존된다. 실행기→임시 HTTP 관리 서버의 첫 chunk를 의도적으로 HTTP500으로 실패시킨 뒤 새 게임을 실행 상태로 전환하고 원본을 바꿨다. 이어받기는 종료 복사본을 중앙에 반영했고, 원본 새 내용·새 게임 state/PID/lease를 유지했다. 오래된 ready/commit 재전달, 잘못된 run token/실행기/전송 순서, stale revision, 루트 탈출·링크, 같은 파일 offset 충돌도 확인했다. 기존 실행분은 PID를 확인해 run token만 부여하여 프로세스를 다시 시작하지 않고 새 경로로 편입할 수 있다.

## Java 프로세스 조사

현재 실행기 Mac의 Java18개 중 운영4개(bootstrap/controller/resident/Minecraft), 6~7일 전 임시 테스트 디렉터리에서 시작된 구버전 resident14개를 구분했다. 관리웹 Mac은 운영3개와 오래된 테스트 resident2개였다. 새 GameHostSpawn/HostReadiness 시험을 마친 뒤 실행기 Mac Java는 다시18개였고 새로 남은 PID는0개였다. 이번 관측에서 새로운 운영 JVM 누적은 재현하지 못했다. 오래된 테스트 프로세스나 현재 게임을 종료하지 않았다. 과거 테스트 잔여물이 현재 업데이트가 계속 JVM을 생성한다는 증거는 아니다.

## 제한

정지 복사본을 준비하고 관리 서버가 접수한 다음 같은 실행기에서 재시작할 수 있다. APFS COW를 지원하지 않으면 일반 복사가 필요하므로 준비 시간은 늘어난다. 다른 실행기로 이동/중앙 복구본 사용은 최신 전송을 기다린다. 실제 라우터/여러 장비의 새 전송 경로, 실제 Discord 첨부, 다른 OS 실행, 전원 차단 시험은 미실행이다. 로그50ms 구간은 실제 Minecraft tick ID가 아니며 원문 순번과 각 프레임 Mixin을 유지한다. 원격 격리 모드 검사는 정지·동기화 완료 및 원본/복구본 모드 revision 일치를 요구한다.

## 중간 실패 기록

초기 테스트 준비에서 uncached 모드 누락, mock runner online 누락, Mockito nullable matcher, 브라우저 editor selector/update-status 응답 누락을 수정했다. 최초 컴파일의 잘못 치환된 manifest 인자를 수정했고, 재시작 테스트의 로컬 변수 이름도 정리했다. 환경의 Vite 포트 sandbox 거절은 허용된 로컬 테스트 실행으로 재실행했다. 실제 기능 결함인 snapshot 주기 재시도 누락·heartbeat의 배경 진행률 덮어쓰기·이전 중앙 crash 첨부 경로를 수정하고 해당 경로를 재검증했다.

게시 직전 짧은 파일·모드 변경을 preparing 예약에 등록해 제어 계층 재시작과의 경합을 막았다. 완료된 편집 복사본은 RPC 응답 직전에도 반영 완료로 인정하되 아직 WRITE_CHUNK 중인 업로드는 완료로 간주하지 않는 시험도 통과했다. 첫 릴리즈 CI는 이 보완 전에 게시하지 않도록 수동 취소했으며 기능 보완 후 재실행한다.

## 게시 확인

2026-10-23 GitHub Actions39893980700 성공. 최종 태그 v0.7.0-beta.24는 검증 커밋 d35bd4d090e624a91fe49b71e0a6257d80d27bbd를 가리킨다. Full backend suite는 skipped, JAR/실행기 설치검증/관리앱·실행기 패키징/격리 bootstrap/서명/게시 모두 success였다. 공개 메타데이터에서 prerelease·비draft 및9개자산을 확인했다. 자산을 재다운로드하지 않았다. 안정판 latest는 v0.6.3으로 유지됐다.

릴리즈: https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.24
CI: https://github.com/yoonseo6399/ServerManager/actions/runs/39893980700
