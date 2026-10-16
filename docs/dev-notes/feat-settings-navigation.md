상태: 설정 메뉴 분리와 관리 앱 로그 UI 완료, 통합 대기
# feat/settings-navigation
- 기준 ad2bc9c (origin/main에서 시작 후 release/0.5.1 fast-forward).
- 내 계정, 사용자와 권한, 실행기·네트워크, AI 비서·에이전트, 연동 서비스, 시스템으로 분리. 소유자 페이지는 렌더링과 메뉴에서 제한; 서버 인증 변경 없음.
- #security는 내 계정으로 호환. 전역 설정도 history에 보존. 서버 없는 경우 설정 방문 기록이 덮어써지던 결함 수정.
- 초대와 권한 초안 SaveBar 이동 경고. 기존 서버·설정 SaveBar 경고 보존.
- SystemLogPanel: 실제 /admin/system-logs 계약, 2초 조회/숨김 중지/일시정지/필터/1000건 한도/현재 표시 내용 내보내기. 세션 변경 시 after=0 재조회.
- DesktopSettings: 실제 update API를 2초 조회하고 checking/message 표시. 가상 진행률 없음.
- 검증: bundled Node로 TypeScript tsc -b 및 Vite build 통과. NODE_BINARY=<bundled node> node node_modules/@playwright/test/cli.js test -c playwright.settings.config.ts: 4 passed (메뉴 분리/역할/뒤로앞으로/미저장권한경고/로그 빈결과·오류·복구·일시정지·세션전환).
- 초기 로그 오류 테스트 fixture error 필드를 message로 수정; 초기 빈 서버 history 결함은 수정 후 통과.
- 통합 담당: GoogleConnectionSettings를 내 계정에, AssistantPanel을 AI 페이지에 통합해야 함(각 담당 브랜치 파일 필요). 기존 MainAgentSettings/AgentKeySettings는 AI 페이지에서 그대로 렌더.
- 기존 브라우저 테스트의 '계정과 보안'/'서버 실행기' 사이드바 선택자는 새 메뉴명으로 조정 필요. 전체 회귀/실제 관리 서버 검증은 미실행; 운영 환경 변경 없음.
