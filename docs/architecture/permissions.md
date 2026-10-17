# 권한 서비스 (0.7.0-beta.10)

Discord 기능의 권한은 **서버 관리자가 정하고 Discord는 그 인터페이스**다. `/permission` 명령은 정책을 보고 바꾸는 창구일 뿐이고, 결정은 `PermissionService`가 한다. 이 문서는 모델, 판정 순서, 저장 형식, 명령, 기존 기능과의 호환, 한계를 설명한다.

## 1. 원칙

1. **선언과 부여를 분리한다.** 모듈은 자기가 게이트를 원하는 기능(권한)을 선언한다. 선언은 아무에게도 권한을 주지 않는다.
2. **상한은 로컬이 정한다.** 관리 컴퓨터의 소유자만 올릴 수 있는 "위험도 상한"이 있다. Discord 명령은 상한을 올릴 수 없다. Discord 서버장이 컴퓨터 소유자와 같다는 보장이 없고, 탈취된 Discord 계정이 컴퓨터 권한을 스스로 열면 안 되기 때문이다.
3. **판단과 집행을 나눈다.** 서비스는 `check`로 "허용/거부/미설정"만 답한다. 실제로 막는 곳은 모듈 레지스트리와 각 모듈이다.
4. **거부가 항상 이긴다.** 같은 사람이 허용 역할과 거부 역할을 함께 가지면 거부가 적용된다.
5. **새 권한은 모듈이 정한 기본값을 따른다.** 위험한 새 권한은 `DENY`(부여 전까지 거부), 이미 쓰이던 기능은 `LEGACY`(기존 검사 유지).
6. **모든 변경은 기록한다.** 누가 무엇을 언제 바꿨는지 감사 로그에 남는다.

## 2. 모델

```kotlin
enum class PermissionRisk { LOW, MEDIUM, HIGH }

data class PermissionDecl(
    val id: String,        // "모듈.동작", 예: "agent.use". 모듈 ID로 시작해야 한다.
    val label: String,     // 1~80자, /permission list에 표시
    val risk: PermissionRisk,   // 코드에 고정. 모듈이 낮춰 선언해 우회할 수 없다.
    val module: String,
    val default: String = "LEGACY")   // "LEGACY" 또는 "DENY"

data class PermissionSubject(val platform: String = "discord", val kind: String = "role", val id: String = "")
data class PermissionGrant(val guildId: String, val subject: PermissionSubject, val permission: String,
    val effect: String /* ALLOW | DENY */, val expiresAt: Long? , val by: String, val at: Long)
data class PermissionCeiling(val maxRisk: PermissionRisk = MEDIUM, val disabled: Set<String> = emptySet())
data class PermissionDecision(val allowed: Boolean?, val reason: String)   // null = 미설정
```

- **주체(subject)** 는 `{platform, kind, id}`다. 지금은 `discord/role`만 쓰지만, 웹 계정이나 다른 메신저를 추가해도 저장 형식을 바꿀 필요가 없다.
- **와일드카드**: 규칙의 권한에 `모듈.*`을 쓸 수 있다(`music.*`). 선언된 권한 중 하나라도 해당해야 저장된다.
- **위험도 기준**
  - LOW: 읽기, 재생처럼 되돌릴 수 있고 데이터를 바꾸지 않음.
  - MEDIUM: 서버 상태 변경, 파일 수정(승인 포함), 외부 API 사용.
  - HIGH: OS 명령 실행, 비밀 접근, 되돌리기 어려운 삭제.

## 3. 판정 순서 (`PermissionService.check`)

```text
1. 선언되지 않은 권한                      → 거부
2. 위험도가 로컬 상한을 넘음                → 거부 (최고 관리자도)
3. 로컬 설정에서 비활성화(disabled)          → 거부 (최고 관리자도)
4. 최고 관리자 역할                         → 허용
5. 이 길드·이 역할·이 권한(와일드카드 포함)의 유효한 규칙 중
   5a. DENY가 하나라도 있음                 → 거부
   5b. ALLOW가 하나라도 있음                → 허용
6. 규칙 없음
   6a. 선언의 기본값이 DENY                 → 거부 ("기본 거부")
   6b. 기본값이 LEGACY                      → 미설정(null): 호출자가 자기 검사를 한다
```

- "최고 관리자"는 관리 웹의 Discord 설정에 등록된 최고 관리자 역할이다. 최고 관리자가 `/permission`으로 자기 역할을 거부해도 4번에서 허용되므로 잠기지 않는다. 상한(2)과 비활성화(3)는 예외 없이 적용된다.
- 규칙은 **길드별**이다. 다른 길드의 규칙은 영향을 주지 않는다.
- 만료된 규칙(`expiresAt` ≤ 지금)은 무시된다.

## 4. 저장 위치와 형식

경로는 데이터 폴더의 `security/discord/`. 임시 파일에 쓴 뒤 원자적으로 교체하고 가능하면 `rw-------`로 저장한다.

| 파일 | 내용 | 수정 주체 |
|---|---|---|
| `permission-grants.json` | 권한 규칙 목록 | `/permission`(최고 관리자) |
| `permission-ceiling.json` | 위험도 상한과 비활성화 목록 | **로컬만** (파일 직접 수정 또는 이후 관리 웹) |
| `permission-audit.jsonl` | 감사 로그(한 줄에 항목 하나) | 서비스가 자동 기록 |

`permission-ceiling.json` 예:

```json
{ "maxRisk": "MEDIUM", "disabled": ["ai.cloud"] }
```

`permission-grants.json` 예:

```json
{ "grants": [
  { "guildId": "100000000000000001",
    "subject": { "platform": "discord", "kind": "role", "id": "200000000000000001" },
    "permission": "agent.use", "effect": "ALLOW",
    "expiresAt": null, "by": "discord:300000000000000001", "at": 1760700000000 } ] }
```

- 파일이 깨졌거나 없으면 "규칙 없음, 상한 MEDIUM"으로 시작한다(실패 시 안전한 쪽).
- 상한 파일을 바꾸면 **앱을 재시작**해야 반영된다(현재는 시작 시 읽는다). 서비스 API의 `setCeiling`은 관리 웹이나 로컬 코드에서 쓰도록 남겨 둔 것이고 Discord 명령에는 연결되어 있지 않다.
- 한도: 규칙 500개, 감사 로그 1,000줄(초과 시 오래된 줄부터 삭제), 만료 시간 1~43,200분.

## 5. `/permission` 명령 (최고 관리자 전용)

| 명령 | 동작 |
|---|---|
| `/permission list` | 선언된 권한을 모듈별로 보여 준다. 위험도, 기본 거부 여부, 내 현재 상태, 로컬 상한을 함께 표시. |
| `/permission rules` | 이 서버의 규칙 목록(허용/거부, 역할, 만료). |
| `/permission grant role permission [effect] [minutes] [confirm]` | 규칙 저장. `effect` 기본 ALLOW. 같은 (역할, 권한) 규칙은 교체된다. |
| `/permission revoke role permission` | 규칙 삭제. |
| `/permission audit [count]` | 최근 변경 기록(기본 10, 최대 50). |
| `/permission reset` | 이 서버의 모든 규칙 삭제(비상 스위치). |

`grant`의 제약:

- 최고 관리자만 변경할 수 있다.
- 선언되지 않은 권한은 거부된다. 위험도가 로컬 상한을 넘는 권한의 ALLOW는 저장 자체가 거부된다(와일드카드는 저장되지만 판정 때 상한이 다시 적용된다).
- 위험도 HIGH 허용은 `confirm:true`와 24시간(1,440분) 이하의 `minutes`가 모두 필요하다.
- DENY는 제약이 없고 상한과도 무관하다.
- 모든 결과 메시지는 임시(본인만 보임) 응답이고 멘션을 허용하지 않는다.

권한 ID는 자동완성된다(`agent.use`, `music.*` …).

## 6. 현재 연결 상태

| 권한 | 위험도 | 기본값 | 게이트 위치 | 막는 것 |
|---|---|---|---|---|
| `music.use` | LOW | LEGACY | `/music` 실행 전 | 명시적 거부 시 명령 차단 |
| `agent.use` | MEDIUM | LEGACY | `/agent` 실행 전 + 에이전트 채널 메시지 | 거부 시 명령·대화 차단 |
| `ai.cloud` | MEDIUM | LEGACY | `/cloud-agent`, `/sub-agent`, `/agent-keys` | |
| `ai.local` | LOW | LEGACY | `/local-llm` | |
| `assistant.use` | MEDIUM | LEGACY | `/assistant*` | |
| `minecraft.status` | LOW | LEGACY | `/mc` 전체 | |
| `minecraft.control` | MEDIUM | LEGACY | `/mc start·stop·restart`, `sm:` 패널 버튼 | |

중요한 점 두 가지.

1. **ALLOW 규칙은 아직 권한을 "더하지" 않는다.** 모든 현재 권한이 `LEGACY`이므로 ALLOW 규칙이 있어도 모듈의 기존 검사(`/agent access` 역할, 음악 채널 규칙, 최고 관리자 등)가 그대로 적용된다. 지금 `/permission`으로 할 수 있는 것은 **거부 규칙, 비활성화, 상한**이다. 기본값이 `DENY`인 새 권한이 추가되면 그때부터 ALLOW가 실제로 권한을 연다.
2. **서버 제어(`/mc`)**: `minecraft.status`(LOW), `minecraft.control`(MEDIUM, start·stop·restart와 패널 버튼)이 추가 게이트다. 서버별 허가는 여전히 `DiscordAccess`의 역할 규칙이 판단하고, 이 서비스는 거부 규칙·상한만 더한다. `DiscordAccess`를 서비스 규칙으로 옮기는 일은 미구현이다.

## 7. 코드에서 쓰는 방법

모듈이 권한을 선언하고 레지스트리에 맡기는 방법(권장):

```kotlin
override val permissions = listOf(PermissionDecl("report.send", "리포트 전송", PermissionRisk.MEDIUM, "report", PermissionService.DENY))
override fun commandPermission(command: String) = "report.send"
```

명령 하나 안에서 기능별로 나눠야 할 때는 서비스를 직접 호출한다.

```kotlin
val d = permissions.check(guildId, memberRoleIds, "report.send", superAdmin)
if (d.allowed == false) { event.reply("권한이 없습니다: ${d.reason}").setEphemeral(true).queue(); return }
if (d.allowed == null) { /* LEGACY: 모듈 자체 검사 */ }
```

자체 검사를 하는 모듈은 `allowed == true`를 **권한의 증거로 쓰되**, 상한과 거부를 우회하는 용도로 쓰지 않는다.

## 8. 감사 로그

기록 항목: `at`(시각), `guildId`(상한 변경은 `*`), `actor`(`discord:사용자ID` 또는 `local`), `action`(`grant|revoke|reset|ceiling`), `detail`(300자까지). 조회는 해당 길드와 `*` 항목만 반환한다. 명령 실행 자체(음악 재생 등)는 기록하지 않는다.

## 9. 알려진 한계와 다음 단계

- 규칙 대상은 **역할**만 지원한다(사용자·채널 단위 규칙 없음).
- 권한 확인 결과를 캐시하지 않는다. 봇이 서버 관리자와 같은 프로세스인 지금은 비용이 작지만, 프로세스를 나누면 짧은 캐시와 변경 알림이 필요하다. 회수 지연 시간은 그때 문서에 명시한다.
- 상한 변경 UI와 규칙 변경 알림 DM(고위험 확인 버튼)은 아직 없다.
- `/agent access`의 역할 설정과 `DiscordAccess`는 이 서비스로 옮기지 않았다. 마이그레이션은 모듈별로 `LEGACY`를 `DENY`로 바꾸면서 진행한다.
- 서비스가 꺼지거나 파일이 깨지면 규칙 없음으로 동작해 **기본값만** 적용된다. 이 경우 `DENY` 권한은 거부, `LEGACY` 권한은 기존 검사로 돌아간다.
