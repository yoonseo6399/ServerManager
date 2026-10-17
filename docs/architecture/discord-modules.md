# Discord 봇 모듈 만들기 (0.7.0-beta.10)

Discord 봇의 기능(음악, AI 에이전트, 권한 관리 …)은 **모듈**이다. 봇(`DiscordBot`)은 Discord 연결과 `/mc` 서버 제어 패널만 직접 갖고, 나머지는 모두 `DiscordModule`로 등록된다. 이 문서는 모듈의 계약, 이벤트 라우팅, 권한 연동, 새 모듈을 추가하는 순서를 설명한다.

## 1. 구조

```text
JDA 이벤트 ─▶ DiscordBot ─▶ DiscordModuleRegistry ─▶ 모듈 (music / agent / ai / assistant / permission)
                 │               │  1. 이름·접두사로 모듈 찾기
                 │               │  2. PermissionService로 권한 게이트
                 │               │  3. 모듈 호출 (예외는 이 층에서 격리)
                 └─ /mc, sm: 버튼 (아직 모듈이 아님, DiscordAccess 사용)
```

- 코드 위치: `src/main/kotlin/local/servermanager/discord/` (패키지는 `local.servermanager`, 폴더 구조는 `docs/architecture/source-layout.md`). 핵심 파일은 `DiscordModule.kt`(인터페이스와 레지스트리), `DiscordModules.kt`(기존 기능 어댑터), `DiscordPermissionModule.kt`(`/permission`).
- 등록 위치: `DiscordBot`의 `modules` 초기화 블록 한 곳. 새 모듈은 여기에 `registry.add(...)` 한 줄을 더한다.
- 기존 기능 클래스(`DiscordMusic`, `DiscordProjectAgent` …)는 그대로 두고 얇은 어댑터가 `DiscordModule`로 감싼다. 동작은 바뀌지 않는다.

## 2. 모듈 계약 (`DiscordModule`)

| 멤버 | 필수 | 설명 |
|---|---|---|
| `id` | 예 | 소문자·숫자·밑줄, 영문자로 시작, 31자 이하. 이 모듈이 선언하는 모든 권한 ID의 앞부분과 같아야 한다. |
| `label` | 예 | 오류 메시지에 쓰는 사람용 이름. |
| `commands` | 예(비어 있어도 됨) | 이 모듈이 소유한 최상위 슬래시 명령 이름. 다른 모듈과 겹치면 등록 시 거부된다. |
| `componentPrefixes` | 아니오 | 버튼·선택 메뉴 ID 접두사(`"music:"`). 다른 모듈과 포함 관계면 거부된다. |
| `permissions` | 아니오 | 이 모듈이 게이트를 원하는 권한 선언 목록. 선언은 허가가 아니다. |
| `register(jda, guildId)` | 예 | 길드 하나에 슬래시 명령을 등록. **연결할 때마다** 호출되므로 여러 번 호출돼도 안전해야 한다. |
| `onCommand(event)` | 예 | `commands`에 속한 명령 처리. |
| `onButton`, `onSelect` | 아니오 | `componentPrefixes`에 속한 컴포넌트. |
| `onAutoComplete` | 아니오 | `commands`에 속한 명령의 자동완성. |
| `onMessage(event)` | 아니오 | **모든** 길드 메시지가 모든 모듈에 전달된다. 관심 없는 메시지는 즉시 반환해야 한다. |
| `commandPermission(command)` | 아니오 | 이 명령을 실행하기 전에 확인할 권한 ID. `null`이면 모듈이 자체 검사만 한다. |
| `messagePermission` | 아니오 | `onMessage` 처리 전에 확인할 권한 ID. 거부되면 조용히 건너뛴다. |
| `disconnect()` | 아니오 | Discord 연결이 끊기거나 교체될 때(음성 연결, 진행 중 작업 정리). |
| `close()` | 아니오 | 앱 종료 시. 레지스트리가 **등록의 역순**으로 호출하고, 한 모듈이 실패해도 나머지를 계속 닫는다. |

## 3. 레지스트리가 보장하는 것

1. **라우팅**: 슬래시 명령은 이름으로, 버튼·선택 메뉴는 ID 접두사로 모듈을 찾는다. 모듈은 자기 것이 아닌 이벤트를 받지 않는다.
2. **권한 게이트**: 명령 실행 전 `commandPermission`을 `PermissionService.check`로 확인한다. 명시적 거부, 로컬 상한 초과, 기본 거부(`DEFAULT DENY`) 권한은 모듈이 실행되기 전에 막힌다. 미설정이면 모듈의 기존 검사에 맡긴다. 자세한 규칙은 `docs/architecture/permissions.md`.
3. **장애 격리**: 모듈 안의 예외는 로그에 남기고 사용자에게 "○○ 기능에서 오류가 발생했습니다"를 임시 메시지로 보낸다(이미 응답했다면 보내지 않는다). 다른 모듈이나 JDA 스레드로 전파되지 않는다.
4. **등록 검증**: 중복 모듈 ID, 이미 쓰는 명령 이름, 겹치는 컴포넌트 접두사, 다른 모듈의 권한 선언, 잘못된 ID는 `add` 단계에서 `IllegalArgumentException`으로 거부되며 부분 등록이 남지 않는다.

레지스트리가 하지 **않는** 것: 버튼 클릭에 대한 권한 게이트(버튼은 명령에서 만들어진 패널이므로 모듈이 직접 확인한다), `/mc` 처리, Discord 역할의 해석(역할은 이벤트의 멤버 정보에서 읽는다).

## 4. 새 모듈 추가 순서

예시로 `/hello` 명령과 "다시" 버튼을 가진 `hello` 모듈을 만든다.

### 4.1 모듈 클래스

`src/main/kotlin/local/servermanager/discord/DiscordHelloModule.kt` (기능이 커지면 `discord/hello/` 같은 하위 폴더를 만든다)

```kotlin
package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.buttons.Button

class DiscordHelloModule : DiscordModule {
    override val id = "hello"
    override val label = "인사"
    override val commands = setOf("hello")
    override val componentPrefixes = setOf("hello:")
    override val permissions = listOf(
        PermissionDecl("hello.use", "인사 명령 사용", PermissionRisk.LOW, "hello"))
    override fun commandPermission(command: String) = "hello.use"

    override fun register(jda: JDA, guildId: String) {
        jda.getGuildById(guildId)?.upsertCommand(Commands.slash("hello", "인사하기"))?.queue({}, {})
    }
    override fun onCommand(event: SlashCommandInteractionEvent) {
        event.reply("안녕하세요!").addActionRow(Button.primary("hello:again", "다시")).setEphemeral(true).queue({}, {})
    }
    override fun onButton(event: ButtonInteractionEvent) {
        event.reply("또 만났네요!").setEphemeral(true).queue({}, {})
    }
}
```

### 4.2 등록

`DiscordBot`의 `modules` 블록에 한 줄 추가한다.

```kotlin
registry.add(DiscordHelloModule())
```

### 4.3 점검 목록 (PR 전에 모두 확인)

- [ ] `id`가 권한 ID의 앞부분과 같다(`hello.use` → `hello`).
- [ ] 위험도를 정직하게 선언했다. 파일 수정·명령 실행·외부 전송이 가능하면 MEDIUM 이상, OS 명령 실행이나 비밀 접근이 가능하면 HIGH. HIGH는 기본 로컬 상한(MEDIUM)에서 아예 열 수 없다.
- [ ] 위험한 권한은 `default = PermissionService.DENY`로 선언했다(부여 전까지 거부). 이미 쓰이던 기능을 모듈화할 때만 기본값 `LEGACY`를 쓴다.
- [ ] Discord 역할은 이벤트에서 읽고, 서버 제어는 `ServerControlPort`로만 한다. `ServerManager`나 설정 파일을 직접 만지지 않는다 (`docs/architecture/server-control-api.md`).
- [ ] 데이터 파일은 `security/<모듈>/` 아래에 두고, 권한 있는 파일은 소유자만 읽도록(`rw-------`) 저장한다.
- [ ] `register`는 여러 번 호출돼도 안전하다.
- [ ] `onMessage`는 관심 없는 메시지에서 즉시 반환한다. 오래 걸리는 일은 별도 스레드에서 한다.
- [ ] `disconnect()`와 `close()`에서 스레드·음성 연결·진행 중 작업을 정리한다.
- [ ] 명령 이름, 컴포넌트 접두사가 기존 것과 겹치지 않는다(`/mc`, `sm:`는 봇 본체가 예약).

### 4.4 테스트

레지스트리와 권한은 Discord 없이 시험할 수 있다. 가짜 모듈 패턴은 `DiscordModuleRegistryTest`를 참고한다. 새 모듈은 최소한 다음을 시험한다.

1. `permissions`의 ID가 모듈 ID로 시작하고 `PermissionService.declare`가 통과한다.
2. `DiscordModuleRegistry.add`가 충돌 없이 성공한다(명령 이름, 접두사).
3. 순수 로직(포맷, 계산)은 JDA 객체 없이 시험할 수 있게 분리한다. 예: `PermissionText`, `DiscordPermissionModule.run`.
4. JDA 이벤트가 필요한 부분은 실제 Discord 서버의 수동 시험 목록으로 남긴다(테스트 가이드 참고).

## 5. 현재 모듈

| 모듈 ID | 명령 | 컴포넌트 접두사 | 선언한 권한 | 비고 |
|---|---|---|---|---|
| `music` | `/music` | `music:` | `music.use`(LOW) | 메시지 처리 포함. 오디오를 시작하지 못하면 안내 메시지. |
| `agent` | `/agent` | `qa:` | `agent.use`(MEDIUM) | 개인 프로젝트 채널의 메시지도 처리(`messagePermission`). |
| `ai` | `/cloud-agent`, `/sub-agent`, `/agent-keys`, `/local-llm` | 없음 | `ai.cloud`(MEDIUM), `ai.local`(LOW) | |
| `assistant` | `/assistant`, `/assistant-link`, `/assistant-google` | `as:` | `assistant.use`(MEDIUM) | 비서 기능이 설정된 경우에만 등록. |
| `permission` | `/permission` | 없음 | 없음 | 최고 관리자 전용. 자기 자신은 게이트하지 않는다. |
| (본체) | `/mc` | `sm:` | 없음 | `DiscordAccess`(서버별 역할 규칙)로 판단. 모듈화는 다음 단계. |

기존 기능은 모두 `LEGACY` 기본값이라 **업데이트 직후 동작이 바뀌지 않는다.** `/permission`으로 명시적 거부 규칙을 만들 때만 새 동작이 생긴다.

## 6. 앞으로의 방향 (미구현)

1. `/mc` 제어 패널을 `minecraft` 모듈로 옮기기. 서버별 권한 판단은 서버 관리자 쪽(`ServerControlPort`)에 남긴다.
2. 봇을 별도 프로세스로 분리. `ServerControlPort` 구현을 HTTP 클라이언트로 바꾸면 모듈 코드는 그대로다.
3. 모듈별 활성/비활성 설정과 설정 화면. 외부 플러그인 설치는 신뢰 모델(서명, 위험도 상한)이 정해진 뒤에 다룬다.
