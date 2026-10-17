# 소스 폴더 구조 (0.7.0-beta.10)

소스는 기능별 폴더로 나뉘어 있다. **패키지는 모두 그대로 `local.servermanager`** 이고 폴더만 나눴다. 그래서 `import`를 바꿀 필요가 없고 동작도 달라지지 않는다(폴더 이동은 `git mv`로 했으므로 `git log --follow <파일>`로 이력을 볼 수 있다). 테스트는 `src/test/kotlin/local/servermanager/` 아래에 같은 폴더 이름으로 대응된다.

| 폴더 | 역할 | 주요 내용 |
|---|---|---|
| `app/` | 앱 뼈대와 웹 API | 웹 서버 시작(`Application`), REST 컨트롤러(`Api`, `AuthApi`), 로그인·세션·보안(`AuthStore`, `Security`), 데스크톱/맥 시작 관리, 앱 업데이트와 롤백, 시스템·서버 로그 |
| `server/` | Minecraft 서버 관리 | 서버 실행·상태·재시작(`ServerManager`, `AutoRestart`, `StopWatchdog`, `PowerSaving`), 실행자 표시(`StartActor`), 서버 제어 인터페이스(`ServerControlPort`), 버전 목록, 상태 조회 프로토콜 |
| `mods/` | 모드 관리 | 모드 파일·목록·검색, Modrinth 연동과 다운로드, mrpack 가져오기 |
| `network/` | 게임 네트워크 | 로컬 게임 호스팅, 중계(`DirectRelay*`, `TcpRelayPeer`, `Sable*`), 게임 네트워크 |
| `runner/` | 서버 실행기 | 다른 컴퓨터에서 서버를 돌리는 실행기(`RunnerMain`)와 관리 앱 쪽 연결(`RunnerGateway`, `RunnerRegistry`), 원격 파일, 실행기 업데이트 |
| `transfer/` | 파일 전송과 동기화 | 폴더 가져오기, 변경분 전송(`DeltaTransfer`), 파일 목록·검증, 전송 작업, WebDAV 동기화 |
| `discord/` | Discord 봇 본체와 모듈 계층 | Discord 연결·`/mc` 패널(`DiscordBot`), 설정(`DiscordSettings`), 모듈 인터페이스와 레지스트리(`DiscordModule`), 기존 기능 어댑터(`DiscordModules`), `/permission`, 권한 서비스(`PermissionService`) |
| `discord/music/` | 음악 모듈 | 재생(`DiscordMusicPlayback`), 패널, 채널·라이브러리 저장소, 명령(`DiscordMusic`) |
| `agent/` | 프로젝트 에이전트 | Qwen 프로젝트 에이전트 엔진·도구·셸·장부·결과 보관(`ProjectAgent*`), 역할별 접근(`AgentAccess`), Discord 명령(`DiscordProjectAgent`, `DiscordCodeAgent`), 서브 에이전트 |
| `ai/` | AI 모델 연동 | 로컬 모델(`LocalLlm`, `LocalModels`, `Qwen*`), 클라우드 모델(`Gemini*`, `OpenAI*`), API 키 저장·설정 |
| `assistant/` | 파일 비서 | 웹 소유자 계정 기반 파일 비서(`Assistant*`), 구글 읽기 전용 연동, Discord 명령(`DiscordAssistant`) |

## 폴더별 파일

### `app/` (13)

`Api.kt`, `AppUpdates.kt`, `Application.kt`, `AuthApi.kt`, `AuthStore.kt`, `DesktopManagement.kt`, `LogFiles.kt`, `LogFilters.kt`, `MacStartup.kt`, `Security.kt`, `SystemLogs.kt`, `UpdateCoordinator.kt`, `UpdateRollback.kt`

### `server/` (13)

`AutoRestart.kt`, `Launchers.kt`, `MinecraftHandshake.kt`, `MinecraftQuery.kt`, `PowerSaving.kt`, `ProcessMetrics.kt`, `QueryEndpoint.kt`, `ServerControlPort.kt`, `ServerExit.kt`, `ServerManager.kt`, `StartActor.kt`, `StopWatchdog.kt`, `VersionCatalog.kt`

### `mods/` (8)

`ModFiles.kt`, `ModInventory.kt`, `ModManagement.kt`, `ModSearch.kt`, `ModSources.kt`, `Modrinth.kt`, `ModrinthDownloads.kt`, `MrpackImporter.kt`

### `network/` (9)

`DirectRelay.kt`, `DirectRelayManager.kt`, `DirectRelayRunner.kt`, `GameHost.kt`, `GameHostLink.kt`, `GameNetwork.kt`, `SableRelay.kt`, `SableRunner.kt`, `TcpRelayPeer.kt`

### `runner/` (9)

`RemoteFiles.kt`, `RunnerFolders.kt`, `RunnerGateway.kt`, `RunnerMain.kt`, `RunnerRegistry.kt`, `RunnerTransfer.kt`, `RunnerUpdater.kt`, `RunnerUpdates.kt`, `RunnerWorkspace.kt`

### `transfer/` (9)

`DeltaTransfer.kt`, `FileInventory.kt`, `FolderImport.kt`, `TransferBatch.kt`, `TransferTasks.kt`, `TransferWait.kt`, `VerifiedInventory.kt`, `WebDavSettings.kt`, `WebDavSync.kt`

### `discord/` (8)

`DiscordBot.kt`, `DiscordErrorSummary.kt`, `DiscordModule.kt`, `DiscordModules.kt`, `DiscordPanelStore.kt`, `DiscordPermissionModule.kt`, `DiscordSettings.kt`, `PermissionService.kt`

### `discord/music/` (6)

`DiscordMusic.kt`, `DiscordMusicChannelStore.kt`, `DiscordMusicGateway.kt`, `DiscordMusicLibraryStore.kt`, `DiscordMusicPanel.kt`, `DiscordMusicPlayback.kt`

### `agent/` (21)

`AgentAccess.kt`, `CodeSubagent.kt`, `DiscordAgentText.kt`, `DiscordCodeAgent.kt`, `DiscordProjectAgent.kt`, `IsolatedAgentProject.kt`, `ProjectAgentCompletion.kt`, `ProjectAgentEdit.kt`, `ProjectAgentEngine.kt`, `ProjectAgentFiles.kt`, `ProjectAgentFormats.kt`, `ProjectAgentGradle.kt`, `ProjectAgentJobs.kt`, `ProjectAgentLedger.kt`, `ProjectAgentMemory.kt`, `ProjectAgentReply.kt`, `ProjectAgentResults.kt`, `ProjectAgentRouting.kt`, `ProjectAgentShell.kt`, `ProjectAgentStore.kt`, `ProjectAgentTools.kt`

### `ai/` (10)

`AgentApiKeyStore.kt`, `AgentKeyPoolApi.kt`, `GeminiKeyPool.kt`, `GeminiMainAdapter.kt`, `LocalLlm.kt`, `LocalModels.kt`, `MainAgentSettingsApi.kt`, `OpenAIMainAgent.kt`, `QwenSettings.kt`, `QwenStreaming.kt`

### `assistant/` (8)

`AssistantApi.kt`, `AssistantGoogle.kt`, `AssistantHarness.kt`, `AssistantIdentity.kt`, `AssistantMacFiles.kt`, `AssistantWorkspace.kt`, `DiscordAssistant.kt`, `GoogleReadonly.kt`

## 새 파일을 둘 곳

1. 기능이 속한 폴더에 둔다. 모르겠으면 그 파일이 가장 자주 호출하는 쪽의 폴더.
2. Discord 봇 모듈은 `discord/` (기능이 커서 파일이 여러 개면 `discord/<모듈>/` 하위 폴더, 음악이 예).
3. 파일 이름을 소스 이름과 테스트 이름이 대응되게 짓는다(`Foo.kt` ↔ `FooTest.kt`). 테스트는 같은 폴더 이름의 `src/test/...` 아래에 둔다.
4. 새 최상위 폴더가 필요하면 이 문서의 표에 한 줄을 추가한다.

## 알아 둘 점

- **CI 경로 필터**: `.github/workflows/runner-installers.yml`의 `paths:`에는 소스 파일 경로가 들어 있다. 파일을 옮기거나 새 파일을 필터에 넣을 때는 새 경로를 쓴다(글롭은 `src/main/kotlin/local/servermanager/**/Mod*.kt` 형태). 태그 릴리즈(`v*`)는 경로 필터와 무관하다.
- **폴더 간 의존**: 아직 폴더 경계를 컴파일러가 강제하지 않는다(같은 패키지). 의존 방향의 목표는 `discord/*`·`agent/*` → `server/*`(`ServerControlPort`)이고 반대는 없어야 한다. 위반은 코드 리뷰에서 막는다.
- **다음 단계(미구현)**: 폴더에 맞춰 패키지를 나누면(`local.servermanager.discord` 등) 의존 방향을 컴파일러가 검사하고 별도 모듈(Gradle 서브프로젝트)로 분리할 수 있다. import 변경이 크므로 봇 프로세스 분리 직전에 한꺼번에 한다 (`docs/architecture/server-control-api.md` B.6 참고).
