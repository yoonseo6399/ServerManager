상태: 직접 중계·포트포워딩·실행기 업데이트 상세 UI 완료, 통합 대기
# feat/direct-relay-settings-ui
- 기준 feat/settings-navigation 092bc79; 완료된 제공자/Google 브랜치 d8b615a 병합. GoogleConnectionSettings는 내 계정에 연결.
- Claude API 계약 origin/feat/direct-tcp-relay 3e542dc docs/direct-relay-api.md 기준. 해당 backend 소스는 이 브랜치에 병합하지 않음.
- network 첫 화면 DirectRelaySettings: 직접 경로, 동의/활성/준비 구분, 승인 없는 실행기 비활성, 준비 후 READY 전환, 취소, 연결 영향 확인 후 즉시 차단.
- 공유기 외부 포트/내부 주소/내부 포트 그대로 표시. UNVERIFIED는 미검증, 검사는 관리 서버 위치에서의 검사라고 표시. 공유기/DNS 자동 변경 없음.
- 기존 관리 Mac 경유 설정은 접힌 별도 legacy 영역으로 표시. 공통 게임 포트/DNS 설정은 기존 API 유지.
- 설정 초안은 최초 조회 revision을 보존하며 충돌 시 API 오류를 표시; 초기화하면 최신 revision 사용.
- RunnerUpdateStatus: 실제 update-status 컴포넌트/대기사유/에러/host mode/지속 로그, after cursor 재조회; 500건 제한. 오프라인/구형 available:false 구분. MANAGER_APP placeholder는 성공 상태처럼 표시하지 않음.
- 검증: TypeScript tsc -b + Vite build 통과; Playwright settings config 6개 통과 (기존 4 + 준비/전환/차단확인/미허용/미검증/409 충돌 + 오프라인/대기사유/로그 cursor 이어읽기).
- 실제 다중 컴퓨터 중계·공유기·Google OAuth·Windows 실행은 미검증. 운영 앱/게임 변경 없음. AssistantPanel 연결은 해당 담당자 완료 이후 통합 필요.
