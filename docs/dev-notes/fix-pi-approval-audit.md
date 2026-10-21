상태: 승인·개인 서비스·diff 기능 검증 완료, 실행 스크립트 내용 위험 검토 추가 검증 중 · solo · 전체 테스트 생략
# fix/pi-approval-audit

Beta17 태그 기준 전용 작업. 운영 앱·실행기·게임호스트는 재시작/설치하지 않았다. 아직 새 릴리즈하지 않았다.

- 독립 위험 검토는 선택한 주 모델(현재27B)의 fresh 세션, 도구/이력 없음, Thinking off.4B 다운로드 비교 후 사용자 선택에 따라 제품 경로에서는 제외.4B는 다운로드만 남고 모델은 모두 해제함.
- 좁은 리터럴 Bash와 && 경로 검사/자동 LOW, 나머지 UNKNOWN·Git/gh 게시 명시 승인, 일반 외부 파일의 명시 승인, 거부 원인/미실행 피드백, call ID별 명령·종료코드·출력/검토 이유 보존.
- ask_user 선택지와 자유 답변, 정확한 pending 작업에 소유자 ‘하라고/실행해’ 등의 자연어 승인. 정보 질문은 실행 권한을 주지 않음.
- 요청별 /agent run window와 기존 채널별 context window. 추론 호스트의 위험 메모리 중단 + 10분/소유자 한정 한 번 재시도 버튼. 원격 실행기도 AGENT_SAFETY_PROFILE_V1 필요. wired20480은 보존.
- 최고 관리자의 수동 채널/포럼 허용 목록, 원문 기록·포럼 게시글 조회. 모델이 허용 목록을 바꿀 도구 없음. 실제 선택/운영 Discord fetch는 아직 하지 않음.
- 일회성 schedule_task create/list/cancel, /agent schedules. 영속 상태, 기한·지연·busy·중복 실행·재시작 처리, 실행 시 권한/설정 재검사. 미래 도구 포괄 승인 아님.
- 모든 도구 종료의 공통 파일 diff/전후 원문 자세히, bounded 프로젝트 Bash 변경 관측. 원문은 소유자/채널/epoch/10분 한정 ephemeral 첨부. 출력 코드 블록 분할/중첩 fence 보존. 임의 셸의 모든 외부 파일 수정 추적은 아님.

## 검증

- 관련9개 클래스53개 테스트 통과(`/private/tmp/sm-pi-approval-tests9.log`), 이후 추가 Discord diff·원문 버튼 렌더링 테스트와 변경된 개인 서비스/Pi 테스트25개 통과(`/private/tmp/sm-pi-approval-final-render2.log`). 서로 다른 기능 테스트 총54개.
- 관리 웹 build 및 qwen-settings Playwright1개 통과. 전체 테스트는 사용자 요청으로 미실행.
- 실제27B16K Pi1.3.0: read→bash→edit→read→bash, 원문 CRLF/마지막 개행 보존 및 지정 위치 한 줄 삽입 최종 바이트 일치.504.775초에는 사용자 동시 호출 영향 포함. 생성12.11~13.19tok/s. 모델 해제/wired20480 확인.
- 직접 chat:8K13.056tok/s,16K13.023tok/s;16K10861입력 표지3/3회수,11.877tok/s. 압력1/2만 관측. 잘못 큰 초기 generate fixture 실패도 보존. raw/보고서: docs/benchmarks/pi-approval-qwen27-20261022.md.
- 실제 위험4로 시스템을 몰아넣지 않고 샘플 주입 테스트. 실제 Discord 네트워크의 버튼·포럼·정시 실행, 원격 호스트/다른 OS 미실행. 문서 export --check 통과.

## 후속

- 실행 파일 검토 보완 완료. 아래 최종 보완 및 raw 보고서 참조.
- 릴리즈 시 이 브랜치와 보완 커밋을 통합하고 관리자/추론 실행기를 함께 업데이트해야 함. VERSION·docs/releases·docs/DEVELOPMENT는 수정하지 않음.

## 최종 보완
- 실행 파일 내용/해시 실제 독립 검토·승인 직전 재검증. 실제27B+Pi: 미승인 실행 없음, 이유 전달, 임시 외부 파일 원문 유지. 실제 OS 파일 미수정. raw 보고서 보존.
- 교차 Discord 출처→소비 서버/채널→요청자 ACL. 제공자/봇 권한 재검사. READ/EDIT 사용자도 명시 위임된 기록만 사용. 운영 Discord fetch 미실행.
- 관리 웹 타임아웃1~240분(기본20), rollback 호환 별도 저장, Pi/undici/Ollama/Strata/원격 예산 일치. 기능60개 최종통과: /private/tmp/sm-pi-final-feature-tests.log. 타임아웃 웹1개 및 frontend build 통과.
- push는 자동 검토 명시 승인 요구 후 사용자가 푸쉬를 승인하여9d3bbf0부터 올림. 후속 승인에 따라 보완도 push하고 Beta18 출시. 게시 후 재다운로드·해시 대조는 생략.

게시 완료: https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.18 · 릴리즈 커밋d8b01f8 · CI39648514436 성공 · 자산9개 메타데이터 확인 · 게시 후 재다운로드/해시 검사 미실행.
