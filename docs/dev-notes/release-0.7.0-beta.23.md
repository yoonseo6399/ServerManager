상태: Beta23 통합655통과/29조건부 미실행 · 프런트/브라우저/JAR 통과 · 태그 서명 CI 예정
# release/0.7.0-beta.23
- Beta22 태그5015c95 기반, origin/feat/turboquant-context ad049a7 통합. 단독 구현/검토. stable/main/latest 보존.
- 변경·실측·미해결14B 검토 오판은 한국어 릴리즈 노트와 원문 증거에 보존.
- 선커밋 시험 계획: npm ci/build, Java21 compileKotlin/test/bootJar. pinned YouTube 준비, Pi Node24 fixture. 새 버전 태그는 전체 suite·runner-setup·패키징·격리 bootstrap·서명·게시 CI1개를 자동 실행. 중복 dispatch/skip_full_tests 없음. 실제 모델 재점유 시험은 반복하지 않음.
- 운영 설치/재시작, 다른OS 실행, 게시 자산 재다운로드/해시 비교는 하지 않음.

- 통합 브라우저 시험 계획: 기존 Qwen 설정 시험을 새 저장 payload에 맞추고 기본꺼짐→GPU실행기 고정준비→토글저장/재조회→모델전환/메모리해제를 검증한다. API는 fixture이며 실제 Discord나 엔진 설치를 브라우저에서 실행하지 않는다. 선커밋 후 Playwright 해당 시험 실행.

- 통합 검증: npm ci/TS/Vite 성공, Java21 compileKotlin/test/bootJar 성공(2m20s). JUnit137리포트684보고=655통과/29조건부 미실행/실패0. qwen-settings Playwright1통과(2.7s), 실제 DOM 토글 기본값/선택GPU 준비 payload/모델전환저장/메모리해제 확인. API fixture이므로 실Discord/원격엔진 설치 검증과 구별한다.
- 원문 집계 integration-full-suite.json 보존. 시험 종료시 Ollama models[]·native/Strata시험프로세스없음·wired20480 확인, 기존 앱은 종료하지 않음.
