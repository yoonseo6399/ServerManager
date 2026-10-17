# 0.7.0-beta.9 테스트 가이드

대상 브랜치: `feat/0.7.0-beta.9-result-store` (기반: `release/0.7.0-beta.8`). 이 문서는 변경을 직접 확인하는 사람이나 다른 AI(예: GPT)가 같은 순서로 재현할 수 있게 쓴 것이다. 코드 읽기만으로 "통과"라고 보고하지 말고, 아래 명령의 실제 출력과 숫자를 보고서에 붙인다.

## 1. 무엇이 바뀌었나

| # | 기능 | 핵심 파일 | 자동 테스트 |
|---|---|---|---|
| 1 | 긴 명령 출력 보관 + `read_result` 도구 | `ProjectAgentResults.kt`, `ProjectAgentTools.kt` | `ProjectAgentResultsTest` 6 |
| 2 | 실시간 개입(작업 중 소유자 메시지 반영) | `ProjectAgentEngine.steer`, `DiscordProjectAgent.message` | `ProjectAgentSteerTest` 3 |
| 3 | `edit_file`도 "저장 후 다시 읽어서 검증" 대상 | `ProjectAgentEngine.kt` | `ProjectAgentEditVerifyTest` 1 |
| 4 | 요구사항 장부 + 완료 게이트 | `ProjectAgentLedger.kt`, `ProjectAgentEngine.kt` | `ProjectAgentLedgerTest` 7, `ProjectAgentLedgerEngineTest` 4 |
| 5 | 작업 노트(이전 요청 이어받기) | `ProjectAgentStore.notes/saveNotes`, 엔진 `pinned()` | `ProjectAgentLedgerEngineTest`(follow up) |
| 6 | `rg` 명령과 `search` 도구 | `ProjectAgentShell.rg`, `ProjectAgentTools.searchArgv` | `ProjectAgentSearchTest` 6 |
| 7 | 입력 예산 환경변수 `PROJECT_AGENT_INPUT_BUDGET` | `ProjectAgentContext.budget` | (수동, 6.5절) |
| 8 | 월드 전송 준비 속도 개선(`bbcc7f7`)과 스캔 진행 순서 | `FileInventory.kt`, `RunnerMain.kt` 등 | `FileInventoryCacheTest` 2, `TransferPrepProgressTest` 2, `DeltaTransferTest` 6, `RunnerTransferTest` 12 |

## 2. 준비

- JDK 21, 저장소 루트에서 `./gradlew --offline ...`. 프런트엔드는 이번 범위에서 바뀌지 않았다.
- 작업 트리는 이 브랜치 그대로 쓴다. **푸시, 태그, 릴리즈, `main` 변경은 하지 않는다.**
- 실제 토큰·비밀 파일을 테스트 폴더에 넣지 않는다. 테스트는 모두 `@TempDir`를 쓴다.
- Windows에서는 `GeminiKeyPool.writePrivate`의 POSIX 권한 설정(`Files.setPosixFilePermissions` 두 줄)이 예외를 내서, 설정 저장소를 쓰는 테스트가 전부 실패한다(기존 문제, 이번 변경과 무관). 아래 3.2절의 임시 완화를 쓰고 **반드시 원복**한다. macOS/Linux에서는 완화가 필요 없다.

## 3. 자동 테스트

### 3.1 컴파일

```sh
./gradlew --offline compileKotlin compileTestKotlin
```

기대: 오류 없음(경고 `w:`는 무시).

### 3.2 Windows 임시 완화 (macOS/Linux는 건너뜀)

`src/main/kotlin/local/servermanager/GeminiKeyPool.kt`의 `writePrivate` 안 두 줄을 `runCatching { ... }`로 감싼다.

```kotlin
directory.createDirectories();runCatching{Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))}
atomicText(path,text);runCatching{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))}
```

테스트가 끝나면 `git checkout -- src/main/kotlin/local/servermanager/GeminiKeyPool.kt`로 원복하고 `git status`에 이 파일이 없는지 확인한다. 원복하지 않은 채 커밋하지 않는다.

### 3.3 이번 변경의 테스트만

```sh
./gradlew --offline test \
  --tests '*ProjectAgentResultsTest' --tests '*ProjectAgentSteerTest' --tests '*ProjectAgentEditVerifyTest' \
  --tests '*ProjectAgentLedgerTest' --tests '*ProjectAgentLedgerEngineTest' --tests '*ProjectAgentSearchTest' \
  --tests '*FileInventoryCacheTest' --tests '*TransferPrepProgressTest' --tests '*DeltaTransferTest' --tests '*RunnerTransferTest'
```

기대: 위 표의 개수만큼 모두 통과(6+3+1+7+4+6+2+2+6+12 = 49개). 결과 숫자는 `build/test-results/test/TEST-*.xml`의 `tests= failures= errors=`로 확인한다.

### 3.4 회귀 확인(관련 기존 테스트)

```sh
./gradlew --offline test --tests '*ProjectAgent*' --tests '*AgentAccessTest' --tests '*LocalModelsTest' --tests '*CsrfRefreshTest' --tests '*DiscordMusicPlaybackTest' --tests '*LogFilesTest'
```

기대(Windows, 3.2 적용 시): **약 130개 중 실패는 아래 환경 문제뿐이다(위 108개 + 음악·로그 테스트 약 24개).** 새 실패가 있으면 이번 변경의 회귀일 가능성이 높으니 원인을 조사한다.

| 알려진 환경 실패(Windows) | 이유 |
|---|---|
| `ProjectAgentTest` 4개: git diff condensed, build/test tiny Java, symlink escape, hard link | `/bin/sh`·javac·심볼릭/하드 링크 권한 |
| `ProjectAgentWorkflowTest` 1개: failed build is inspected | 같은 셸/javac 이유 |
| `LocalModelsTest` 1개: star validator | `/usr/bin/python3` 없음 |
| `LogFilesTest` 1개: 심볼릭 링크 | 링크 생성 권한 |

macOS/Linux에서는 위 항목도 통과해야 한다. 가능하면 macOS에서 전체 `./gradlew test`를 한 번 돌려 결과를 보고한다(CI와 같은 조건).

## 4. 항목별 수동 확인 (Discord 없이 가능한 것 포함)

각 항목은 "무엇을 하고, 무엇이 보여야 하는지"를 적었다. 단위 테스트가 이미 같은 동작을 검사하므로, 수동 확인은 **실제 모델(Qwen 30B)** 에서 의도대로 쓰는지 보는 목적이다.

### 4.1 긴 출력 보관 (`read_result`)

1. 프로젝트 채널에서 "다음 명령을 실행하고 399번째 줄 값을 알려줘: `seq 1 400 | sed 's/^/row-/'`"처럼 출력이 2,400자를 넘는 `run_command`를 시킨다(Windows 실행기는 `for /l %i in (1,1,400) do @echo row-%i-padding`).
2. 도구 결과에 `출력이 길어 원문을 보관했습니다 ... out-<16자 hex>` 안내가 보이고, 모델이 `read_result {id,start_line,lines}`를 호출해 값을 찾아야 한다.
3. 확인: 모델 요약(`요약 실패`/`종료 코드:` 뒤 700자 요약)이 아니라 원문 줄 번호(`399: row-399`)가 답에 쓰인다. 다른 채널에서 같은 ID로 `read_result`를 요청하면 "결과를 찾을 수 없습니다"가 나온다.
4. 보관 폴더: `<데이터>/project-agent-logs/results/<채널ID>/out-*.txt`. 24시간 지난 파일과 채널당 20개 초과분은 다음 저장 때 지워진다.

### 4.2 실시간 개입

1. 오래 걸리는 요청을 시작한다(예: 빌드가 포함된 요청).
2. 실행 중에 **같은 채널에 소유자 계정으로** 새 메시지("아니, README 말고 docs/에 써줘")를 보낸다.
3. 기대: 메시지에 🧭 반응이 붙고, 진행 표시에 `🧭 개입 반영: ...`이 나온다. 다음 모델 호출에 `mid_task_instruction`이 들어가 방향이 바뀐다. 모델이 막 최종 답을 낸 순간에 도착해도 끝나지 않고 이어진다.
4. 거부되어야 하는 경우: 소유자가 아닌 사람의 메시지(개입으로 받지 않음, 기존 동작 유지), 개입 6개 이상 연속(대기 5개 제한), 빈 메시지. 개입 메시지로 승인 버튼이 눌리거나 권한이 바뀌면 안 된다.

### 4.3 요구사항 장부와 완료 게이트

1. 40자 이상이고 여러 부분이 있는 요청을 한다. 예: "a.txt를 만들어 hello를 넣고, b.txt에는 설명을 쓰고, 마지막에 결과를 알려줘."
2. 기대: 시작할 때 `📋 요구사항 N개를 추적합니다: R1 ... / R2 ...`가 보인다(모델 추출 실패 시 줄/문장 분리로 대체).
3. 모델이 일부만 하고 "완료"라고 하면 `미완료 요구사항 N개 · 이어서 확인합니다`가 나오고 작업이 계속된다. 최대 3번 재요청 후에는 남은 항목이 `⬜ ... 미완료`로 최종 보고에 표시된다.
4. 최종 메시지 끝에 `요구사항 점검:` 목록(✅ 완료 + 증거, ⛔ 막힘 + 사유, ⬜ 미완료)이 붙는다.
5. 부정 확인: 파일을 만들거나 고치는 항목은 변경 도구가 성공하기 전에는 모델이 "done"이라고 해도 닫히지 않는다. 질문에 되묻는 `status:"clarify"` 응답은 게이트를 통과해 끝날 수 있다.
6. 짧은 요청("계속해")은 장부를 새로 만들지 않고, 이전 요청에서 남은 항목이 있으면 이어받는다(4.4).

### 4.4 작업 노트(이어받기)

1. 위 4.3에서 일부러 턴 수를 작게 해(`/agent run turns:2`) 항목을 남긴다.
2. 이어서 "계속해줘"만 보낸다.
3. 기대: 모델에게 `previous_task_notes`(직전 요청·결과·바뀐 파일·미완료)와 `requirements`(이전 미완료 항목)가 고정으로 전달되어 되묻지 않고 이어간다. 저장 위치: `<데이터>/project-agent-notes.json`(별도 파일).
4. `/agent context reset`을 하면 그 채널의 노트도 지워진다.

### 4.5 `rg`와 `search`

- `sh {"command":"rg -n TODO -g '*.kt' src"}`, `rg -C 2 pattern`, `rg --files -g '*.md'`, `cat x | rg y`가 동작한다. 기본이 재귀·줄 번호이고 `build`, `.git`, `node_modules`, `.env` 등은 제외한다.
- 거부되어야 하는 옵션: `--hidden`, `--no-ignore`, `-g '!x'`, `--pre`, 알 수 없는 `-t`. 오류에 지원 옵션 목록이 나온다.
- `search` 도구: `{pattern, path?, glob?, type?, context?, max?, ignore_case?, fixed?}` 또는 `{files:true, glob?}`. 따옴표·세미콜론이 든 패턴도 데이터로만 취급해야 한다(셸 해석 없음).
- 프로젝트 밖 경로(`../x`)는 친구 단계에서 계획 단계에서 거부된다.

### 4.6 `edit_file` 재조회

"x를 고치고 저장한 다음 다시 읽어서 검증해"라고 요청한다. `edit_file` 후 모델이 `read_file`을 하지 않고 끝내려 하면 `저장 후 재조회 검증 요청`이 나온다.

### 4.7 입력 예산

기본값은 3600(8K 창 기준)이며 바뀌지 않았다. 더 큰 창을 쓰는 실행기에서만 `PROJECT_AGENT_INPUT_BUDGET=6000` 같은 값(3600~12000)을 설정해 시험한다. **측정 후에만 올린다**: 같은 긴 대화로 (a) 응답 시간, (b) Ollama 메모리, (c) 앞부분 요구사항을 잊는지를 기본값과 비교한다. 이 값을 올리는 것을 기본으로 바꾸지는 않았다.

### 4.8 월드 전송 준비 (`bbcc7f7`)

실행기 폴더 가져오기에서 진행 단계가 `1/3 → 2/3 → 3/3` 순서로 보이고, 두 번째 실행에서는 이전에 검사한 파일을 다시 읽지 않는다(`reused` 숫자가 늘고 `hashed`가 줄어든다). 이 변경은 전송 준비(목록 작성·비교) 단계의 속도와 표시만 바꾼다. 실제 월드로 시험하지 말고 임시 복사본을 쓰고, 전송 전후 원본 폴더의 파일 목록과 해시가 같은지도 확인한다.

## 5. 실제 Qwen 30B 시험 (선택, 가장 가치 있음)

`ProjectAgentHarnessLiveTest`는 환경변수가 있을 때만 실행된다.

```sh
PROJECT_AGENT_LIVE_TEST=true PROJECT_AGENT_LIVE_MODEL=30b ./gradlew --offline test --tests '*ProjectAgentHarnessLiveTest'
```

Ollama가 로컬에서 돌고 `qwen3:30b`가 설치되어 있어야 한다. 이 시험이 추가한 항목(장부, 개입)을 직접 검사하지는 않으므로, 4.2~4.5를 실제 모델로 손으로 한 번씩 돌려 보고 시간, 도구 호출 수, 실패 사례를 기록한다. 모델이 JSON 형식을 어기거나 `requirements` 필드를 쓰지 않는 경우가 얼마나 되는지가 핵심 지표다(쓰지 않으면 게이트가 3번 재요청한 뒤 미완료로 보고한다).

## 6. 보고서 형식

아래 틀로 결과를 남긴다. 숫자와 명령 출력을 생략하지 않는다.

```text
환경: OS / JDK / Ollama·모델 / 커밋 해시(git rev-parse --short HEAD)
컴파일: 성공|실패 (오류 있으면 첫 5줄)
3.3 신규 테스트: 통과 N / 전체 M  (클래스별 숫자)
3.4 회귀: 실패 목록 — 알려진 환경 실패와 일치하는지, 새 실패가 있는지
4.x 수동 확인: 항목별 [통과|실패|미실시] + 근거(관찰한 메시지/로그)
5. 실제 모델: 시나리오, 소요 시간, 도구 호출 수, 장부 필드 사용 여부, 실패 사례
발견한 버그: 재현 순서 / 기대 / 실제 / 의심 파일
원복 확인: git status에 GeminiKeyPool.kt 변경이 없음
```

## 7. 하지 않을 것

- 푸시, 태그, GitHub 릴리즈, `main` 병합.
- 코드 수정(버그를 찾으면 보고만 한다. 수정이 필요하면 별도로 요청받는다).
- 실제 운영 서버·월드·계정·토큰 사용.
- 통과하지 못한 항목을 "환경 문제"로 단정하지 않는다: 3.4의 표에 없는 실패는 먼저 이번 변경이 원인인지 `git stash`가 아닌 **별도 워크트리의 이전 커밋(`release/0.7.0-beta.8`)에서 같은 테스트를 돌려** 비교한다.
