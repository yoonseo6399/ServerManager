상태: Beta21 공개 게시 완료 · 수동 CI 성공 · 중복 자동 CI 전체 테스트 실패 기록
# release/0.7.0-beta.21
- v0.7.0-beta.20 기준 fix/pi-reviewer-authority53a5249 통합. 단독 작업.
- 포함: 정적 native 실행 판정 제거·독립 검토/사용자 승인, 선택 답변 기록, 상단 요약/하단 상태창/도구 카드 전환.
- 기능54개 통과 및 마지막 표시 수정 후 관련7개 재확인 통과. docs/testing/pi-reviewer-authority.md에 선커밋 테스트 계획/선행 실패/실행 결과 보존.
- 통합 뒤 프런트 production 빌드 및 compileKotlin/bootJar 통과. npm이 기본 PATH에 없어 기존 Node24 경로로 실행. 수동 CI39718701309에서 설치 패키징/runner-setup/격리 bootstrap/서명/게시 성공. 해당 실행의 전체 테스트 단계는 skipped.
- stable/main/latest·기존 게시 버전 유지. 운영 설치/재시작·게시 후 다운로드/해시 대조 없음.

- 게시 소스/태그 bd0c5c259381905dcd9625838512e53477ab50ec,2026-10-22T03:51:05Z 공개 prerelease/draft=false. 자산9개 uploaded 메타데이터만 확인. stable/latest v0.6.3 보존.
- 사용자 생략 지시에도 태그 refs API 생성이 push CI39718700977을 별도로 시작했다. 재개 시 이미 종료되어 취소하지 못했다. 이 자동 실행은 전체665개 중38실패/23스킵으로 실패했으며 전체 테스트 통과를 주장하지 않는다. 37개는 레거시 actor/harness 기대를 쓰는 클래스에 있고,1개는 PersonalServices의 강제 ASK 기대다. 변경 전 기준선 비교/전체 실패 원인 확정은 미실행이다. 실패를 모두 환경 탓/기존 실패라고 단정하지 않는다.
- 로그: /private/tmp/sm-beta21-unexpected-full-tests.log. 중복 자동 실행은 JAR/패키징/게시 단계 전에 실패했고, 이미 성공한 수동 게시 자산을 수정하지 않았다.
- 공개 릴리즈 설명에 위 전체 테스트 결과를 추기한다. 향후 생략 릴리즈는 태그 API도 push workflow를 유발함을 고려해야 한다. 이번에 워크플로/게시 태그/자산을 덮어쓰지 않는다.
- https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.21
- https://github.com/yoonseo6399/ServerManager/actions/runs/39718701309
