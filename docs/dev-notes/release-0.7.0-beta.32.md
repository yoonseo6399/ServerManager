상태: beta32 공개9자산 게시 확인, 빠른 종료 세션 보완은 beta33으로 분리
# release/0.7.0-beta.32

- 기준: origin/release/0.7.0-beta.31. origin/fix/live-log-session-search(Mixin 닫기 수정 포함), origin/feat/server-runtime-updates를 순서대로 통합했다. main·안정판은 변경하지 않는다.
- 통합 후 추가 보완: 이스케이프된 로그 결과의 실행기 응답 크기 상한, 가변 결과 페이지 이전 커서, 버전 변경 뒤 일반 설정 저장의 이전 런타임 필드 재전송 방지.
- 대상 백엔드27개, 관련 브라우저19개(검색2·자동스크롤5·Mixin닫기9·런타임UI3), TS/Vite 빌드 통과. 120만 줄 끝부분 실제 검색과 전체 필터·스택/gzip/소유권/취소/세션 보존을 확인했다.
- 별도 실제 공식 파일 검증: RUN_REAL_RUNTIME_UPDATE=true에서 공식 Mojang JAR 다운로드·해시 확인 후 격리 RunnerWorkspace로 Minecraft1.21.1→1.21.2 런타임 교체, 원본 server.jar 백업과 world/level.dat sentinel 바이트 보존 통과. Minecraft 프로세스는 실행하지 않았다.
- RuntimeUpdateTest는 사용자 데이터/JVM 인자 보존, 중간 반영 실패 복구, 사용자 지정 JAR, 다운그레이드·동일 버전 차단, 비동기 실패·링크 거부를 검사한다.
- 런타임 보안 핀11개·실행기 설치 회귀 통과. 전체 백엔드 회귀·다른 OS 실행·실제 모드 로더 버전 변경·실제 운영 월드 로드는 미실행이다. 기존 요청대로 관련 검증만 수행한다.
- 서명 CI는 패키징 후 bootstrap 검사·기존 키 서명·beta prerelease 게시를 수행한다. 게시된 자산은 재다운로드해 해시 확인하지 않는다.
- 운영 관리웹·실행기·Minecraft·DNS·월드·모드·계정 변경은 수행하지 않았다. 원자로 안전 보조 모드는 일반 앱에 포함하지 않는다. 사용자는 관리웹과 실행기를 모두 새 릴리즈로 갱신해야 한다.

## 알려진 범위
- 검색은 시작 시점 파일의 전체 일치 수를 세되, 최초100,000개 위치만 보관한다. 긴 행은 앞16KiB 미리보기이며 원문은 유지한다. 새 로그는 다시 검색한다.
- 설치 실패 시 실행 파일 백업을 이용해 복구한다. 새 Minecraft로 실제 월드를 연 이후의 월드 형식 다운그레이드는 지원하지 않는다. 모드는 자동 교체하지 않으므로 별도 호환성 확인/월드 백업이 필요하다. 대상 버전이 요구하는 Java가 실행 컴퓨터에 없으면 설치 전 검증에서 거부한다.

## CI 실행 환경 수정
- 최초 서명 실행45002237295는 단계 실행 전에 취소됐다. GitHub check annotation은 `The configuration 'macos-14-arm64-us-default' is not supported`였다.
- 공식 runner-images 안내의 macOS14 지원 종료와 현재 GitHub-hosted runner 표를 확인해 release/runner-installers 워크플로의 Mac 실행 라벨을 macos-15로 변경했다. 서명·검증 단계는 유지한다.
- 근거: https://github.com/actions/runner-images/issues/13518 및 https://docs.github.com/en/actions/reference/runners/github-hosted-runners . 이 실패는 제품 테스트 실패가 아니며 아직 태그/릴리즈는 만들어지지 않았다.

## 게시 전 세션 경합 보완
- 서명 실행45002387223은 패키징/부트스트랩 단계에서 중단을 요청했으나 취소 상태 확인 때 beta32가 이미 게시된 것을 확인했다. 빠르게 종료된 Process.info가 조회 불가능해질 때 launch/consumer의 세션 ID가 달라지거나 예외가 나는 경로를 보완하기 위해서다.
- 프로세스별 약한 참조 캐시에 시작 시 식별값을 보관하고 재사용하며, info 조회 실패를 처리한다. 명시적으로 종료된 프로세스를 모사한 회귀 검사를 추가했다.

- 공개 beta32: tag/source060ac4212e0ca9b1e9e454273d3f444f213bd873, publishedAt2026-11-22T08:26:14Z, prerelease=true, draft=false, 자산9개. 파일과 태그는 보존한다. 빠른 종료 회귀는 신규 beta33에만 포함한다.
