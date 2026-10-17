상태: 구현 완료, 관련 단위 테스트 통과(Windows 임시 완화 상태), 실제 Qwen 30B·실제 Discord 미검증

# feat/0.7.0-beta.8-harness-roles

베이스: origin/main e4e688d(0.7.0-beta.7)와 병합. 태그·릴리즈는 하지 않았다. VERSION은 0.7.0-beta.8.

## 변경
- 관리 웹 로그인 "권한이 없거나 요청 검증에 실패했습니다": CSRF 토큰이 HTTP 세션(비활성 30분)에 저장되는데 프런트가 로드 시점 토큰을 계속 써서, 탭을 오래 두면 로그인 요청이 403이었다. `frontend/src/http.ts`가 변경 요청의 403에서 `/api/auth/session`으로 토큰을 한 번 갱신해 재시도한다. 보안 설정은 약화하지 않았다. 회귀 테스트 `CsrfRefreshTest`.
- `AgentAccess.kt`: 역할별 모델·권한 단계(READ/EDIT)·턴 상한·공유 프로젝트. 별도 파일 `agent-access.json`.
- `ProjectAgentShell.kt`: `sh` 읽기 전용 명령줄(grep, &&, 파이프 등). `ProjectAgentEdit.kt`: `edit_file` 세 방식.
- `ProjectAgentTools.kt`: 권한 단계별 도구 허용 목록, 친구 단계는 프로젝트 밖·비밀 파일 거부, `sandboxed` 계획.
- `ProjectAgentEngine.kt`: `ProjectAgentLimits(maxTurns,tier)`, 12턴 고정 제거(엔진 기본값 12는 호환용, Discord는 서버 설정 사용), sandbox 도구는 위험 검토·요약 생략.
- `DiscordProjectAgent.kt`: 최고 관리자 역할을 계정 연결 없이 소유자로 간주, 친구 역할 인증, `/agent access ...`, `/agent turns`, `/agent run turns:`, 턴 표시 `N/M`.

- 행동 예고만 하고 끝나는 응답 방지: `ProjectAgentAnnounce`가 마지막 줄의 "~해보겠습니다/Let me…" 형태를 감지해 도구 호출을 재요청(최대 2회, 턴이 남은 경우). 프롬프트에도 금지 문구 추가. `ProjectAgentHarnessTest` 2개 추가.

## 알려진 한계 · 결정
- 계정 연결 없이도 소유자로 동작하도록 요청받아 `/agent`에서만 바꿨다. `/assistant` 계열(웹 소유자 계정이 필요한 파일 비서)은 그대로다. 연결하지 않은 소유자는 웹 비서 폴더 목록(`roots`)을 쓰지 못한다.
- 친구의 `edit` 단계는 공유 프로젝트를 승인 후 수정할 수 있다. 되돌리기는 프로젝트의 git에 맡긴다.
- 웹 관리 화면에는 아직 친구 역할 설정이 없다. Discord 명령으로만 설정한다.
- 여러 친구의 Qwen 요청은 기존 세마포어로 직렬 처리된다. 사용자별 속도 제한은 없다.

## 검증
- 신규: `AgentAccessTest` 6, `ProjectAgentShellTest` 8(심볼릭 링크 1개 환경에 따라 건너뜀), `ProjectAgentHarnessTest` 9, `CsrfRefreshTest` 1(POSIX 아니면 건너뜀).
- Windows에서는 `ProjectAgentStore`·`AgentAccessStore`가 쓰는 `GeminiKeyPoolStore.writePrivate`의 POSIX 권한 설정이 예외를 내서 저장소를 쓰는 테스트가 전부 실패한다(기존 문제). 검증을 위해 그 두 줄을 임시로 `runCatching`으로 감싸 실행한 뒤 원복했다. 이 상태에서 `ProjectAgentTest`·`ProjectAgentWorkflowTest`·`ProjectAgentStopTest` 포함 54개 중 48개 통과, 1개 건너뜀, 5개 실패는 변경 없는 beta.5에서도 같은 5개가 실패한다(셸 `/bin/sh`, javac, 하드링크, 심볼릭 링크 권한 등 Windows 환경). 실제 macOS CI 결과는 아직 없다.
- 실제 Qwen 30B 시험 `ProjectAgentHarnessLiveTest`를 추가했다. `PROJECT_AGENT_LIVE_TEST=true PROJECT_AGENT_LIVE_MODEL=30b`일 때만 실행된다.
- 프런트엔드 빌드·브라우저 검증, 실제 Discord 슬래시 명령 등록·상호작용은 하지 않았다.
