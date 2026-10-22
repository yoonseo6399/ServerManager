# 모드 강제 끄기·원격 크래시 실시간 콘솔 검증

2026-10-22, Apple Silicon macOS. 게시된 Beta23 기준 수정들을 `fix/mod-start-diagnostics`에 통합했다. 앞선 `fix/runner-stale-install-result`도 포함한다. 구현·테스트 계획 선커밋 후 테스트했다.

- 전체 백엔드: **690 보고 = 661 통과 / 29 조건부 미실행 / 실패 0 / 오류 0**, `./gradlew test bootJar --no-daemon`, 2분 23초. 미실행은 명시적 live 플래그/실환경이 필요한 기존 테스트이며 통과로 세지 않았다.
- 관련 클래스: ModManagementTest 7/7, ModSearchTest 18/18, ModArtifactsTest 6통과/1조건부 미실행, RemoteConsoleTest 3/3, RunnerGatewayTest 10/10, LogFilesTest 5/5. 전체 결과 안에 포함된 수치다.
- TypeScript/Vite 프로덕션 빌드 통과. backend 검증 후 Console.tsx의 대량 로그 tail 수정만 추가했으며 frontend를 다시 빌드하고 관련 UI를 재검증했다. Kotlin 코드는 전체 테스트 통과 뒤 변경하지 않았다.
- 최종 Playwright **12/12 통과**, 22.4초. 강제 끄기 4, 기존 강제 켜기/목록 재시도 2, 원격 시작 크래시 4(0/1,200줄 × 필터 켜짐/꺼짐), 큰 목록 1, 읽기 전용 1. 즉시 크래시·500줄을 넘는 burst 끝 예외가 파일 선택/수동 새로고침 없이 표시됐다. 실제 브라우저의 fixture API 시험이며 운영 로그인 세션을 사용하지 않았다.
- 최초 추가 burst 시험은 fixture가 next를 무조건 null로 반환해 9통과/1실패했다. 실제 log-window 커서 계약에 맞게 고치고 필터 양쪽까지 확장해 위 최종 결과를 얻었다. 최초 실패 출력은 로컬 `/private/tmp/sm-start-diagnostics-browser-burst.log`에 남겼다.

## 운영 확인과 복구

- Let's Create Survival은 시작 요청이 실행기에 도달했고 실제 Java 프로세스가 시작됐으나 Sodium 0.8.13의 `NoClassDefFoundError: org/lwjgl/Version`으로 종료 코드 1을 보고했다. 별도 Minecraft crash-report 파일이 없어도 stdout/stderr의 해당 스택이 콘솔에 보여야 한다.
- 실행기 원문 console.log에는 예외가 있지만 중앙 console.log는 이전 설치 성공까지만 기록됐다. remoteLog가 메모리 이벤트에만 저장하는 동안 web log-window는 존재하는 중앙 파일을 읽어 발생한 누락이다. 수정은 중앙 원문 로그에도 append하고 진단 세션별 경로를 유지한다. 저장 실패는 공유 실행기 WebSocket을 종료시키지 않는다.
- 별도로 live tail이 한 번에 최대 500줄만 읽고도 전체 파일 끝 청크를 로드한 것으로 표시했다. 잘못된 표시를 제거해 가상 목록이 실제 끝 청크를 읽도록 수정했다.
- 운영 중앙 console.log를 `security/recovery/20261022-225719-survival-console/console.log`에 백업하고, 해당 서버 실행기 원문 949,296바이트를 출처 표식과 함께 한 번 추가했다. 현재 중앙 파일에도 실제 예외가 있음을 확인했다. 앱 코드 배포·운영 재시작·MOD 변경은 이 복구에 포함하지 않았다. 기존 웹은 로그 다시 불러오기/페이지 새로고침으로 복구분을 볼 수 있다.

## 모드 끄기 지연 분석

- 실제 iMac 대상 **236개, 770.17MiB**. SHA-256·SHA-512를 함께 계산한 읽기 전용 순차 스캔 1회 **5.067초**. 단일 Python 관측이고 디스크 캐시 통제·JVM 토글 전체 시간·Modrinth 실호출별 시간은 미측정이다.
- 토글 경로는 변경 전 전체 이중 해시, rename 트랜잭션 준비의 전체 SHA-256, 변경 후 전체 이중 해시의 **세 차례 전체 파일 읽기**를 수행한다. 추가로 선택 JAR 복사·복구 기록 및 메타데이터 조회가 있다. Modrinth는 캐시 미조회/실패 파일이 있어야 네트워크를 사용하므로 현재 지연의 주원인이 네트워크라고 단정하지 않는다.
- DeltaTransfer.apply는 목록·크기 검사이며 추가 전체 해시로 세지 않는다. 약 770MiB를 세 번 읽는 구조가 직접 확인된 병목 후보다. 이번 요청은 분석이며 무결성 검사 삭제나 속도 개선을 수행했다고 주장하지 않는다.

## 남은 범위

새 기능·자동 로그 전달 수정은 **커밋/푸시 상태, 운영 배포 전**이다. 원본 모드의 활성 상태는 바꾸지 않았다. Sodium을 끈 뒤 다른 클라이언트 MOD/호환성 문제도 드러날 수 있어 전체 서버 정상 시작을 보장하지 않는다. 실제 로그인된 운영 UI·다른 OS·공유기·외부 플레이어 검증과 새 릴리즈 게시는 미실행이다.
