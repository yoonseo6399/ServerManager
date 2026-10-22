상태: 구현·기능 검증 완료 · 테스트 계획 선커밋 · 푸시 인계
# fix/pi-reviewer-authority
- 게시된 Beta20 기반, solo. 원격 최신 refs/작업 현황 확인 후 별도 브랜치에서 진행.
- OWNER native read/edit/write/bash의 정적 명령 목록·경로/비밀 파일명·fingerprint 실행 veto 제거. PiBashAudit/PiReadShell 삭제. UNKNOWN 강제 ASK, LOW→MEDIUM 변환, backend Gradle 예외 제거.
- 독립 모델 새 세션 검토와 인증된 원 요청 ledger·명시 승인 모드 유지. 정확한 사전 동의 LOW/MEDIUM은 자동 허용, 실제 DENY/실패는 차단. 사용자·채널·역할·정보 제공 ACL/취소·본인 확인 유지. OS sandbox가 아니며 정적 파일 변경 재검사도 하지 않는다.
- 선택지/자유 답변을 원 질문에 남김. 버튼은 즉시 갱신, 이벤트는 같은 helper 사용, 답변 원문 최대2000자/응답자 ID·멘션 차단.
- 상단 고정 요청/모델/진행/사용량 요약과 하단 상태창 분리. 새 텍스트 청크 뒤 상태창 재전송, 도구 시작 때 동일 메시지 전환, 다음 모델 턴 새 상태창. 지연 상태 업데이트 취소. 완료 도구 기록 유지·중지 버튼 수명 관리.
- 주입 지시문을 새 정책과 일치시켰고 오래된 export 정규식도 수정.
- 사용자 지시: 구현 먼저 커밋, 이후 기능 테스트. 전체 테스트/타 OS/운영 서비스 재시작/게시 후 다운로드·해시 검증 생략.
- 구현44ed67f → 테스트 계획c0a1ea7 → fixture/ACL 보완716eafd 선커밋 → focused54개 통과. 지표 보완cea32a6 선커밋 → 영향 UI7개 재확인 통과. 프롬프트 exporter --check/diff --check 통과. 실제 Pi SDK/Git 연쇄는 실행했으며 실제27B 신규 호출·운영 Discord는 미실행. 상세 계획/초기 실패/결과: docs/testing/pi-reviewer-authority.md.
- 릴리즈 후보 문구: 독립 모델 판정을 정적 Bash 검사로 상향/거절하지 않도록 변경. 선택 답변 기록과 하단 상태창/도구 카드/다음 턴 표시 개선.
- 아직 출시/운영 적용되지 않음. stable/main/기존 릴리즈/프로세스/GPU 설정 미변경.
