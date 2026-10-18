# 서버 제어 API (0.7.0-beta.10)

Discord 쪽은 Minecraft 서버를 **`ServerControlPort`** 하나로만 제어한다. 이 문서는 (A) 지금 구현된 프로세스 안 인터페이스와 (B) 봇을 별도 프로세스로 분리할 때 같은 인터페이스를 제공할 인증 HTTP API 설계를 설명한다. **B는 설계이며 아직 구현되지 않았다.**

## A. 구현됨: `ServerControlPort`

파일: `ServerControlPort.kt`. 구현체 `ServerManagerControl`이 `ServerManager`에 위임한다. `DiscordMinecraftModule`이 이 포트를 통해 서버를 제어하며 `DiscordBot`은 조립·연결·주기 호출만 맡는다.

| 메서드 | 의미 | 원격 API 대응 (B) |
|---|---|---|
| `dataRoot` | 봇 설정 폴더의 기준 | 대응 없음. 분리된 봇은 자기 데이터 폴더를 가진다. |
| `list()` | 서버 목록과 상태 요약 | `GET /servers` |
| `instance(id)` | 서버 설정 하나 | `GET /servers/{id}` |
| `view(id)` | 상태, 접속자, 절전 등 | `GET /servers/{id}/status` |
| `serverDir(id)` | 서버 폴더 경로(충돌 리포트 첨부용) | `GET /servers/{id}/crash-report` (경로 대신 내용) |
| `startAs(id, actor)` | 시작 | `POST /servers/{id}/start` |
| `stop(id, force)` | 중지 | `POST /servers/{id}/stop` |
| `restartAs(id, actor)` | 재시작 | `POST /servers/{id}/restart` |
| `onSignal(listener)` | 시작·중지·충돌 등 신호 | `GET /events` (SSE) |

규칙

1. 봇 코드(모듈 포함)는 `ServerManager`를 직접 호출하지 않는다.
2. `Instance`, `StartActor`, `ServerSignal` 같은 반환 타입은 원격화할 때 JSON으로 직렬화할 수 있는 형태만 포함해야 한다. 새 필드를 추가할 때 파일 경로나 내부 객체 참조를 넣지 않는다.
3. 서버별 권한(누가 어떤 서버를 시작할 수 있는가)은 서버 관리자 쪽이 최종 판단한다. 지금은 `DiscordAccess`가 그 역할을 하고, 봇은 판단 결과(허용/거부 메시지)를 표시만 한다.

## B. 설계: 분리된 봇용 HTTP API (미구현)

목표: 봇을 별도 실행 파일로 옮겨도 모듈 코드는 그대로이고, 봇이 재시작해도 Minecraft와 관리 웹은 계속 돌아간다. 같은 컴퓨터(loopback)를 기본으로 하고 다른 컴퓨터는 명시적으로 허용한다.

### B.1 인증

- 서버 관리자가 **봇 연결 토큰**을 발급한다(관리 웹에서 한 번만 표시, 서버에는 해시만 저장). 토큰 하나는 봇 하나에 대응한다.
- 요청: `Authorization: Bearer <토큰>`. 토큰은 범위(scope)를 가진다: `servers:read`, `servers:control`, `permissions:read`, `permissions:write`.
- 토큰 회수/재발급 시 이전 토큰은 즉시 무효. 재발급은 관리 웹에서만 가능(Discord 명령으로 불가).
- loopback이 아닌 접속은 TLS 필수, 허용 주소를 설정에 명시. 인증 실패는 일정 횟수 후 지연(rate limit).
- 봇 프로세스는 Discord 봇 토큰을 서버 관리자에게 보내지 않는다. 서버 관리자도 봇 토큰을 몰라도 된다.

### B.2 공통 규약

- 기본 경로 `/api/bot/v1`. **메이저 버전이 다르면 연결을 거부**하고, 마이너 추가 필드는 클라이언트가 무시한다(모르는 필드 무시 규칙).
- 시작 시 `GET /api/bot/v1/hello`로 `{apiVersion, minBotVersion, features:[...]}`를 교환한다. 호환되지 않으면 봇이 명령을 등록하지 않고 상태 메시지로 알린다.
- 오류: `{ "error": { "code": "FORBIDDEN|NOT_FOUND|CONFLICT|BAD_REQUEST|UNAVAILABLE", "message": "사용자에게 보여 줄 수 있는 문장" } }`. 메시지에는 경로, 토큰, 내부 예외 문구를 넣지 않는다.
- 변경 요청(`POST`)은 `requestId`(UUID)를 받아 **멱등**하게 처리한다. 같은 ID의 재시도는 같은 결과를 돌려주고 두 번 실행하지 않는다(현재 `DiscordBot.execute`의 중복 방지와 같은 의도).
- 모든 응답은 `Cache-Control: no-store`.

### B.3 엔드포인트

| 메서드 경로 | 범위 | 설명 |
|---|---|---|
| `GET /hello` | 없음(인증 필요) | 버전·기능 교환 |
| `GET /servers` | servers:read | 봇이 볼 수 있는 서버 목록과 상태 |
| `GET /servers/{id}/status` | servers:read | 상태, 접속자, 절전 정보 |
| `POST /servers/{id}/start` `/stop` `/restart` | servers:control | 본문 `{requestId, actor:{platform:"discord", id, displayName}, guildId, roles:[...]}`. **서버 관리자가 역할을 기준으로 허용 여부를 판단**하고 거부 시 `FORBIDDEN`과 이유를 반환한다. |
| `GET /servers/{id}/crash-report` | servers:read | 최신 충돌 리포트 텍스트(크기 제한) |
| `GET /events` | servers:read | SSE 스트림. 이벤트 `signal` 데이터는 `{instanceId, kind, ...}`. 연결이 끊기면 `Last-Event-ID`로 재개하고, 놓친 신호가 많으면 `resync` 이벤트로 목록을 다시 받게 한다. |
| `POST /permissions/check` | permissions:read | `{guildId, roles, permission, superAdmin}` → `{allowed: true|false|null, reason}` (`PermissionService.check`) |
| `GET /permissions/declarations` · `PUT /permissions/declarations/{module}` | permissions:write | 봇 모듈이 권한 선언을 올림. 서버 관리자는 위험도를 코드 기준으로 고정한다. |
| `GET/POST/DELETE /permissions/grants` | permissions:write | `/permission` 명령이 쓰는 규칙 조작. 서버 관리자가 최고 관리자 여부를 다시 확인한다. |

역할 목록은 봇이 Discord에서 읽어 보내지만, **서버 관리자는 그 값을 신뢰하지 않는다.** 서버 관리자가 별도로 보유한 최고 관리자 역할 설정과 대조하고, 위험도 HIGH 같은 변경은 봇이 보낸 값만으로 허용하지 않는다(관리 웹 확인 필요).

### B.4 권한 확인의 위치

```text
Discord 사용자 ─▶ 봇(모듈)
   1. 봇이 사용자의 현재 역할을 Discord에서 읽음
   2. POST /permissions/check  ─▶ 서버 관리자가 판정 (상한·규칙·기본값)
   3. 허용/미설정이면 모듈이 실행되고, 서버 제어는 POST /servers/{id}/start 로
   4. 서버 관리자가 서버별 역할 규칙(DiscordAccess)을 최종 확인
```

봇이 탈취돼도 서버 관리자의 상한과 서버별 규칙을 넘을 수 없게 하는 것이 목적이다. 단, 봇이 거짓 역할을 보내는 공격은 서버 관리자가 막을 수 없으므로(서버 관리자는 Discord를 직접 조회하지 않음) 고위험 동작은 B.1의 범위 제한과 관리 웹 확인에 의존한다.

### B.5 실패 모드

| 상황 | 동작 |
|---|---|
| 서버 관리자에 연결 불가 | 봇은 서버 제어·권한 변경 명령에 "관리 앱에 연결할 수 없습니다"로 응답한다. 권한 확인이 실패하면 **거부**(fail closed). 음악 같은 `LEGACY` LOW 기능은 마지막 성공 결과 캐시(최대 N초, 기본 30)로 유지하는 옵션을 둔다. |
| 봇 재시작 | Minecraft 서버와 관리 웹에 영향 없음. 재연결 후 `hello`와 `resync`. |
| API 버전 불일치 | 봇이 명령을 등록하지 않고 상태에 이유를 표시. |
| 토큰 회수 | 즉시 401. 봇은 재시도 대신 "토큰이 회수되었습니다"를 표시하고 멈춘다. |

### B.6 단계별 이행 계획

1. (완료) `ServerControlPort` 도입, `DiscordBot`이 이를 통해서만 서버를 제어.
2. `ServerControlPort`의 HTTP 구현체와 서버 쪽 컨트롤러 추가. 설정으로 "내장 / 원격" 선택. 기본은 내장(현재 동작).
3. 봇을 별도 실행 파일로 빌드(같은 저장소, 별도 `bootJar`/모듈). 설치기는 둘을 함께 설치하는 옵션을 제공.
4. 권한 서비스 API(`/permissions/*`) 원격화와 짧은 캐시.
5. 에이전트 모듈을 별도 작업 프로세스로 분리(OS 명령 실행 권한을 서버 관리자와 분리).
6. 서명된 개별 업데이트와 API 버전 호환표.

각 단계는 이전 단계가 안정된 뒤에 시작하고, 단계마다 `docs/testing/`에 시험 절차를 추가한다.
