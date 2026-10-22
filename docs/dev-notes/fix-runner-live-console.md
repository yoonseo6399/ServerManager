상태: 통합 브랜치에서 전체 백엔드 661통과/29조건부 미실행·브라우저 12통과, 운영 배포 전

# fix/runner-live-console

- 실운영 Let's Create Survival의 실행기 console.log에는 22:41 Sodium NoClassDefFoundError와 exit code 1이 있으나 중앙 console.log는 21:58 설치 성공 로그로 끝났다. 원격 로그는 RuntimeState.events에만 추가됐고 웹 log-window는 기존 console.log를 우선하므로 생긴 문제다.
- 인증된 해당 실행기의 raw 로그를 중앙 프로필 console.log에도 즉시 append한다. 인메모리 이벤트·Discord 수집은 유지하고 진단 로그는 진단 프로필에 격리한다. 디스크 쓰기 실패는 기록하되 공유 WebSocket을 끊지 않는다. 이전 누락 로그의 소급 복구는 별도 운영 작업이며 자동 수행하지 않는다.
- 브라우저 tail이 500줄 제한 응답을 받은 뒤 아직 읽지 않은 파일 끝 청크까지 읽었다고 표시하는 버그도 수정한다. 로그가 한꺼번에 몰려도 끝의 크래시를 별도 window로 읽도록 한다. 커밋 뒤 기존 즉시 크래시와 1,200줄 burst 회귀를 함께 실행한다.

## 커밋 후 검증 계획

1. RemoteConsoleTest: 실제 ServerManager/Api/LogFiles 경로로 설치 로그 뒤의 시작 즉시 크래시를 업로드 전 조회, 검색·상태 전환·재기동 보존, 권한 다른 실행기/노이즈 제외, 진단 격리, 원문·길이 제한, 저장 실패시 연결 경로 예외 없음과 후속 복구.
2. LogFilesTest 및 RunnerGatewayTest 회귀. 브라우저에서는 시작→즉시 실패 로그가 새로고침 없이 live tail에 보이는지 확인한다.
3. 통합 테스트 브랜치에서 강제 끄기 수정과 합쳐 전체 백엔드·frontend build/관련 Playwright 실행. 운영 배포·게임 실행·새 릴리즈는 아직 요청 범위 확정 전이다.

## 모드 토글 지연 조사

- 2026-10-22 iMac 대상 236 JAR/disabled JAR 합계 770.17 MiB. 읽기 전용 Python SHA-256+SHA-512 순차 스캔 1회 5.067초. 캐시 상태를 통제하지 않은 단일 관측이며 JVM의 전체 토글 실행시간과 동일하다고 주장하지 않는다.
- 경로: ModManagement.toggle의 files(id) 전체 이중 해시 → ModFiles.rename의 FileInventory.collect 전체 SHA-256 → 선택 JAR 복사 및 복구 journal/atomic 이동 → scan(id)의 전체 이중 해시와 캐시/미조회 Modrinth 조회. DeltaTransfer.apply는 전체 해시 재검사 대신 shape 검사를 사용하므로 이 부분을 추가 해시 비용으로 세지 않는다.
- 즉 전체 모드 약 770 MiB를 3회 읽으며 2회는 두 해시를 계산한다. 변경 파일 자체의 크기·디스크·외부 메타데이터 조회·복구 이력 검사도 영향을 준다. 실제 사용자 토글의 단계별 JVM 시간/외부 조회 시간은 아직 미측정. 무결성 검사를 제거하는 변경은 하지 않는다.

검증 결과: `fix/mod-start-diagnostics`의 `docs/testing/mod-start-diagnostics.md` 및 JSON 참조. 전체 Kotlin/Java 테스트 실패 0, bootJar 및 TypeScript/Vite 통과. 관련 브라우저 12개는 원격 API fixture이며 운영 Discord·다른 OS 검증은 수행하지 않았다.
