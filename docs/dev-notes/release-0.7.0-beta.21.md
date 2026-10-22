상태: Beta21 통합 빌드 통과 · 서명 게시 CI 예정
# release/0.7.0-beta.21
- v0.7.0-beta.20 기준 fix/pi-reviewer-authority53a5249 통합. 단독 작업.
- 포함: 정적 native 실행 판정 제거·독립 검토/사용자 승인, 선택 답변 기록, 상단 요약/하단 상태창/도구 카드 전환.
- 기능54개 통과 및 마지막 표시 수정 후 관련7개 재확인 통과. docs/testing/pi-reviewer-authority.md에 선커밋 테스트 계획/선행 실패/실행 결과 보존.
- 통합 뒤 프런트 production 빌드 및 compileKotlin/bootJar 통과. npm이 기본 PATH에 없어 기존 Node24 경로로 실행. 설치 패키징/runner-setup/격리 bootstrap/서명/게시 CI 예정. 전체 테스트는 사용자 요청으로 생략.
- stable/main/latest·기존 게시 버전 유지. 운영 설치/재시작·게시 후 다운로드/해시 대조 없음.
