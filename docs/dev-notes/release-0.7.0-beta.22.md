상태: Beta22 게시 완료 · 전체 CI/패키징/서명 통과
# release/0.7.0-beta.22
- v0.7.0-beta.21 기준 fix/pi-full-suite2df0fda 통합. 단독 작업.
- 독립 검토/요청한 내부 rm·입력 오류/새 지시 회복·steering/역할 검증·Thinking 임시 삭제·중계 종료 경합 및 전체 테스트 Pi 이관.
- 로컬673보고=648통과/25조건부 미실행/실패0, 실제14B 독립 검토3사례 통과. docs/testing/pi-full-suite.md에 초기 실패/대응/코드·계획 선커밋/검증 제한 보존.
- 통합 compile/frontend 검증 뒤 태그 자동 CI1개로 전체 테스트·패키징/runner-setup/격리 bootstrap/서명/게시를 진행. skip_full_tests를 사용하지 않는다.
- stable/main/latest·기존 게시 태그/자산 보존. 운영 설치/재시작·게시 자산 재다운로드/해시 비교 생략.

- 통합 npm ci/frontend build 및 Java21 compileKotlin/bootJar 통과. npm audit은 기존 의존성 high1개를 보고했으며 이번 승인/Thinking 패치에서 의존성을 임의 변경하지 않음.

- 게시 태그/source: v0.7.0-beta.22 / 5015c95c8de6a0f5e74f9dd63a4baf140e6528d3. 게시 후 태그/자산 변경 없음.
- 태그 push CI 단일 실행 성공: https://github.com/yoonseo6399/ServerManager/actions/runs/39724457573 . 전체 백엔드 suite BUILD SUCCESSFUL(6m54s), frontend/JAR/runner-setup/manager·runner 패키징/격리 bootstrap/서명/게시 모두 통과. CI 로그에 개별 테스트 집계는 없어 로컬 648통과/25조건부 미실행과 구별한다.
- 공개 prerelease: https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.22 , 2026-10-22T04:58:08Z 게시, 자산9개 API 메타데이터 확인. latest stable v0.6.3 보존.
- 운영 앱/실행기/게임호스트 설치·재시작 및 게시 자산 재다운로드/해시 검사는 하지 않음.
