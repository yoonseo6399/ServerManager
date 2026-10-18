# Claude 모듈 설계의 현재 구현 상태

사용자는 2026-10-19에 **현재 모듈 구조 보완부터 진행**을 선택했다. 별도 봇 프로세스·인증 API·설치 분리는 이번 범위가 아니다.

| 설계 항목 | 상태 | 구현 / 후속 |
| --- | --- | --- |
| DiscordModule 계약·레지스트리·권한 선언·예외 격리 | 기존 구현 유지 | DiscordModule.kt |
| Minecraft 실제 명령·패널·알림 구현의 모듈 소유 | 완료 | DiscordMinecraftModule.kt. DiscordBot의 기존 공개 호출·주기 함수는 위임만 수행 |
| 서버 제어 단일 포트 | 기존 구현 유지 | ServerControlPort. 현재 프로세스의 서버별 DiscordAccess도 유지 |
| 모듈 활성/비활성 및 관리 화면 | 완료 | DiscordModuleSettings, /api/admin/discord/modules, DiscordModulesSettings.tsx |
| 음성 상태 이벤트의 모듈 전달 | 완료 | onVoiceState. 비활성·권한 거부가 정리를 차단하지 않으며 예외 격리 |
| 꺼진 모듈의 진입 차단·수명 정리 | 완료 | 명령/버튼/선택/자동완성/메시지 차단, 음악 연결 정리, 프로젝트 에이전트 작업 취소 |
| 권한 서비스로 모든 기존 역할 규칙 이전 | 후속 | LEGACY 의미를 바꾸는 별도 마이그레이션. 이번에 권한 확대 없음 |
| 권한 상한 UI·사용자/채널별 규칙·변경 DM | 후속 | permissions.md의 알려진 한계이며 이번 모듈 수명·활성 설정과 구분 |
| 폴더별 패키지/Gradle 분리 | 후속 | 문서대로 프로세스 분리 직전 단계. 현재 같은 패키지 유지 |
| 인증 HTTP 제어 포트·SSE·토큰 발급 | 범위 제외 | server-control-api.md B.2 이후 |
| 별도 봇/에이전트 작업 프로세스·설치·개별 서명 업데이트 | 범위 제외 | 사용자 선택으로 후속 유지 |

기능별 회귀·실제 LLM 실패 재현과 미검증 범위는 [후속 검증 보고](../testing/beta12-followup.md)에 있다. 현재 소유자 도구 실행을 OS 샌드박스라고 주장하지 않는다.
