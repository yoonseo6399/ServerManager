# 0.7.0-beta.10 테스트 가이드

대상 브랜치: `feat/0.7.0-beta.10-modules` (기반: `release/0.7.0-beta.9`). beta.10은 **구조 변경 릴리즈**다: 새 사용자 기능은 `/permission` 하나이고, 나머지는 기존 동작을 그대로 유지하면서 코드 구조를 바꿨다. 따라서 시험의 핵심은 "기존 기능이 그대로인가"와 "새 권한 게이트가 의도대로만 막는가"다. 읽기만으로 통과를 보고하지 말고 실제 명령 출력의 숫자를 붙인다. 공통 준비(Windows 임시 완화, 알려진 환경 실패)는 `beta9-test-guide.md` 2~3절을 따른다.

## 1. 무엇이 바뀌었나

| # | 변경 | 위치 | 자동 테스트 |
|---|---|---|---|
| 1 | Discord 모듈 계약과 레지스트리(라우팅, 장애 격리, 등록 검증, 예약 이름) | `discord/DiscordModule.kt` | `DiscordModuleRegistryTest` 8 |
| 2 | 기존 음악·에이전트·AI·비서를 모듈 어댑터로 감쌈, `DiscordBot`이 레지스트리로 분배 | `discord/DiscordModules.kt`, `discord/DiscordBot.kt` | `DiscordTest` 7(맥/리눅스 필요) |
| 3 | 권한 서비스(선언, 규칙, 로컬 상한, 감사) | `discord/PermissionService.kt` | `PermissionServiceTest` 14 |
| 4 | `/permission` 명령 | `discord/DiscordPermissionModule.kt` | `DiscordPermissionModuleTest` 4 |
| 5 | `ServerControlPort`: Discord가 서버 제어에 쓰는 유일한 통로 | `server/ServerControlPort.kt` | (컴파일, `DiscordTest`) |
| 6 | 소스를 기능별 폴더로 이동(패키지 동일) | 전체 | 컴파일, 전체 테스트 |
| 7 | CI 경로 필터를 새 폴더에 맞게 수정 | `.github/workflows/runner-installers.yml` | (6.2) |

설계 문서: `docs/architecture/` (README, discord-modules, permissions, server-control-api, source-layout).

## 2. 자동 테스트

### 2.1 컴파일

```sh
./gradlew --offline compileKotlin compileTestKotlin
```

기대: 오류 없음. 폴더만 옮겼으므로 소스 수정이 없어도 컴파일되어야 한다.

### 2.2 이번 변경의 테스트

```sh
./gradlew --offline test --tests '*PermissionServiceTest' --tests '*DiscordModuleRegistryTest' --tests '*DiscordPermissionModuleTest'
```

기대: 14 + 8 + 4 = 26개 모두 통과. Discord 연결이나 POSIX 권한이 필요 없다.

### 2.3 DiscordBot 회귀 (맥/리눅스 권장)

```sh
./gradlew --offline test --tests '*DiscordTest' --tests '*Discord*' --tests '*AssistantAccessTest' --tests '*ProjectAgentDiscordTest'
```

`DiscordTest`(7개)는 `DiscordBot` 전체를 시험한다(토큰 암호화, 패널 ID, 서버 제어 권한과 중복 방지, 중지/재시작에 강제 종료 없음). **Windows에서는 `'posix:permissions' not supported as initial attribute`로 5개가 실패한다(기존 환경 문제).** 이 테스트가 이번 리팩터링의 가장 중요한 회귀 검사이므로 반드시 맥/리눅스에서 통과를 확인한다. Windows에서만 가능하면 "미실시"로 쓰고 이유를 적는다.

### 2.4 전체 회귀

```sh
./gradlew --offline test
```

기대(macOS/Linux): 전체 통과, 건너뜀(skipped)은 환경 의존 테스트뿐. Windows에서는 `beta9-test-guide.md` 3.4의 알려진 환경 실패만 허용한다. 새 실패는 `release/0.7.0-beta.9`를 별도 워크트리로 체크아웃해 같은 테스트를 돌려 비교한다.

## 3. 구조 검증 (자동화되지 않은 것)

1. **폴더 이동이 이력을 보존하는가**: `git log --follow --oneline src/main/kotlin/local/servermanager/agent/ProjectAgentEngine.kt | head`에 beta.9 이전 커밋이 나와야 한다. `git diff -M --stat 694047b 8ec97d1 | tail -1`에서 내용 변경은 문서·워크플로뿐이어야 한다(이동은 rename으로 표시).
2. **패키지가 그대로인가**: `grep -rL "^package local.servermanager$" src/main src/test --include=*.kt`의 출력이 비어 있어야 한다.
3. **소스 외 경로 참조가 깨지지 않았는가**: `git grep -n "src/main/kotlin/local/servermanager/[A-Z]" -- . ':!docs'`에서 옛 위치(폴더 없는 경로)가 남아 있지 않아야 한다.
4. **레지스트리 예약 규칙**: `/mc`와 `sm:` 접두사를 모듈이 가로챌 수 없다(`DiscordModuleRegistryTest`).

## 4. 수동 확인 (실제 Discord 서버, 비운영 서버에서)

사전 조건: 테스트용 Discord 서버에 봇을 초대하고, 관리 웹의 Discord 설정에 **최고 관리자 역할**과 일반 역할(예: `tester`)을 등록해 둔다. 모든 응답이 본인에게만 보이는지(임시 응답) 함께 본다.

### 4.1 기존 기능이 그대로인가 (회귀)

| 확인 | 기대 |
|---|---|
| `/mc status server:<서버>` (허용된 역할) | 상태 표시. 허용되지 않은 역할은 기존과 같은 거부 메시지. |
| 서버 제어 패널 버튼(`sm:`) | 시작/중지/재시작 동작은 기존과 같고, 패널이 현재 설정과 다르면 "이 제어 패널은 현재 설정과 일치하지 않습니다". |
| `/music` 재생·일시정지·다음, 패널 버튼 | 기존과 같이 동작. 오디오가 불가한 환경이면 "음악 오디오 기능을 시작하지 못했습니다" 안내. |
| `/agent` (최고 관리자) 요청 실행, 승인 버튼(`qa:`) | 기존과 같이 동작. |
| `/local-llm`, `/cloud-agent` | 기존과 같이 동작. |
| 봇 연결을 껐다 켬(관리 웹) | 음악 음성 연결이 끊기고(`disconnect`) 다시 연결 후 명령이 등록된다. |
| 명령 등록 | 연결할 때마다 `/mc`, `/music`, `/agent`, `/permission` 등이 길드에 나타난다. |

### 4.2 `/permission` (새 기능)

1. 최고 관리자가 아닌 계정으로 `/permission list` → "최고 관리자 역할만 /permission을 사용할 수 있습니다". (`rules`, `grant`, `revoke`, `audit`, `reset`도 동일)
2. 최고 관리자: `/permission list` → 모듈별 권한(`music.use` LOW, `agent.use` MEDIUM, `ai.cloud` MEDIUM, `ai.local` LOW, `assistant.use` MEDIUM)과 "위험도 상한: MEDIUM"이 보인다.
3. `/permission grant role:@tester permission:music.use effect:거부` → 이후 `tester` 역할의 `/music`이 "이 기능을 사용할 권한이 없습니다 (`music.use`)"로 막히고, 최고 관리자는 막히지 않는다.
4. `/permission rules`에 규칙이 보이고 `/permission audit`에 `grant` 기록이 남는다.
5. `/permission revoke role:@tester permission:music.use` → `/music`이 다시 동작한다(기존 검사로 복귀).
6. `grant ... permission:agent.use effect:허용` → 저장은 되지만 **기존 `/agent` 접근 규칙은 그대로**다(허용 규칙이 권한을 더하지 않는다, `permissions.md` 6절). 역할이 `/agent access`에 없으면 여전히 거부되어야 한다.
7. `grant ... permission:music.*` 와일드카드 거부 → `music.use`가 막힌다.
8. 존재하지 않는 권한(`foo.bar`), 잘못된 역할, `minutes:0`은 오류 메시지로 거부된다.
9. `/permission reset` → 규칙이 모두 사라지고 기본 동작으로 돌아온다. `audit`에 `reset`이 남는다.
10. `minutes:1`로 거부 규칙을 만들고 1분 뒤 `/music`이 다시 동작한다(만료).

### 4.3 로컬 상한과 HIGH 권한

HIGH 선언 권한은 현재 코드에 없으므로 서비스 단위 테스트(`PermissionServiceTest`의 high risk 항목)가 검증한다. 수동으로는 `security/discord/permission-ceiling.json`을 `{"maxRisk":"LOW"}`로 바꾸고 앱을 재시작한 뒤 `/music`은 되지만 `/agent`, `/cloud-agent`가 "위험도 상한(LOW)을 넘는 권한" 메시지로 막히는지, **최고 관리자도 막히는지** 확인한다. 확인 후 파일을 원래대로(`MEDIUM`) 되돌린다. Discord 명령으로는 이 파일을 바꿀 수 없어야 한다(그런 하위 명령이 없다).

### 4.4 장애 격리

시험용 코드 없이 재현하기 어렵다. 단위 테스트 범위: 레지스트리의 `close`가 한 모듈의 실패와 무관하게 전부 닫는다(`DiscordModuleRegistryTest`). 가능하면 `DiscordMusic`을 일부러 실패시키는 임시 브랜치로 `/music` 오류가 "음악 기능에서 오류가 발생했습니다"로만 나오고 `/mc`와 `/agent`가 계속 동작하는지 본다.

## 5. 보고서 형식

```text
환경: OS / JDK / 커밋 해시
2.1 컴파일: 성공|실패
2.2: 통과 N / 26
2.3 DiscordTest: 통과 N / 7 (OS), 실패 시 원인
2.4 전체: 통과 N / 실패 M / 건너뜀 K — 새 실패 여부, beta.9와 비교 결과
3.1~3.4: 각 [통과|실패] + 명령 출력
4.1~4.4: 항목별 [통과|실패|미실시] + 관찰한 메시지
발견한 버그: 재현 순서 / 기대 / 실제 / 의심 파일
```

## 6. 하지 않을 것

푸시, 태그, 릴리즈, `main` 변경, 코드 수정(버그는 보고만), 운영 서버·계정·토큰 사용. 4.3의 상한 파일 수정은 시험용 설치에서만 하고 반드시 원복한다.

## 7. GPT에게 줄 프롬프트

````text
너는 Kotlin/Spring 프로젝트 "Server Manager"의 테스트 담당자다. 코드를 고치지 말고 검증하고 보고만 한다.

대상: 브랜치 `feat/0.7.0-beta.10-modules` (워크트리 경로는 내가 알려준다). 먼저 `docs/testing/beta10-test-guide.md`와 `docs/testing/beta9-test-guide.md`의 2~3절을 끝까지 읽고 그 순서를 따른다. 구조 문서는 `docs/architecture/`에 있다.

해야 할 일
1. `git rev-parse --short HEAD`, `git log --oneline -6`, `git status --short`를 기록한다.
2. 가이드 2.1~2.4를 실제로 실행한다. Windows라면 beta9 가이드의 임시 완화를 쓰되 반드시 원복하고 `git status`로 증명한다. DiscordTest는 맥/리눅스에서만 의미가 있다. 불가하면 미실시로 쓴다.
3. 가이드 3절의 구조 검증 네 가지를 명령으로 확인한다.
4. 새 실패가 있으면 `release/0.7.0-beta.9`를 별도 워크트리로 체크아웃해 같은 테스트를 돌려 비교한다.
5. Discord 서버와 봇 토큰이 있는 환경이면 4절을 수행한다. 없으면 미실시로 쓴다. 운영 서버는 쓰지 않는다.
6. 5절 보고서 형식으로 작성한다.

특히 의심하며 볼 곳
- 모듈 분배 후 `/mc`, `sm:` 버튼, `/music`, `/agent`(+`qa:` 버튼), `/assistant`, `/local-llm`이 이전과 똑같이 동작하는가.
- `/permission`이 최고 관리자 이외에 모두 거부되는가. 상한을 Discord에서 올릴 방법이 없는가.
- 거부 규칙이 `music.use`/`agent.use`를 막고, 허용 규칙은 기존 검사를 우회하지 못하는가.
- 최고 관리자도 로컬 상한 초과 권한과 비활성화 권한을 못 쓰는가.
- 폴더 이동 뒤 `git log --follow`가 이력을 보존하는가. 옛 경로 참조가 남지 않았는가.

규칙: 푸시·태그·릴리즈·main 변경 금지, 소스 수정 금지(임시 완화 제외, 원복 필수), 실제 토큰·운영 서버 금지, 읽기만으로 통과 보고 금지(출력 숫자를 붙인다), 막히면 추측하지 말고 어디서 왜 막혔는지 보고한다.
````
