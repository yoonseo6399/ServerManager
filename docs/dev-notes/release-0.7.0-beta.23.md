상태: CI fixture 보완 후 전체655통과/29스킵 · TCP 철회3회 통과 · 미게시 태그 CI 재시도 준비
# release/0.7.0-beta.23
- Beta22 태그5015c95 기반, origin/feat/turboquant-context ad049a7 통합. 단독 구현/검토. stable/main/latest 보존.
- 변경·실측·미해결14B 검토 오판은 한국어 릴리즈 노트와 원문 증거에 보존.
- 선커밋 시험 계획: npm ci/build, Java21 compileKotlin/test/bootJar. pinned YouTube 준비, Pi Node24 fixture. 새 버전 태그는 전체 suite·runner-setup·패키징·격리 bootstrap·서명·게시 CI1개를 자동 실행. 중복 dispatch/skip_full_tests 없음. 실제 모델 재점유 시험은 반복하지 않음.
- 운영 설치/재시작, 다른OS 실행, 게시 자산 재다운로드/해시 비교는 하지 않음.

- 통합 브라우저 시험 계획: 기존 Qwen 설정 시험을 새 저장 payload에 맞추고 기본꺼짐→GPU실행기 고정준비→토글저장/재조회→모델전환/메모리해제를 검증한다. API는 fixture이며 실제 Discord나 엔진 설치를 브라우저에서 실행하지 않는다. 선커밋 후 Playwright 해당 시험 실행.

- 통합 검증: npm ci/TS/Vite 성공, Java21 compileKotlin/test/bootJar 성공(2m20s). JUnit137리포트684보고=655통과/29조건부 미실행/실패0. qwen-settings Playwright1통과(2.7s), 실제 DOM 토글 기본값/선택GPU 준비 payload/모델전환저장/메모리해제 확인. API fixture이므로 실Discord/원격엔진 설치 검증과 구별한다.
- 원문 집계 integration-full-suite.json 보존. 시험 종료시 Ollama models[]·native/Strata시험프로세스없음·wired20480 확인, 기존 앱은 종료하지 않음.

- 최초 태그 CI39746909392 게시 전 실패: 683완료/2실패/28스킵. DirectRelayManagerTest:203은 ConnectException을 기대했으나 SocketException이 전달됨. 연결이 성공하면 계속 실패하게 두고 connect 자체의 SocketException(ConnectException 포함)을 검증, 명시적1초 connect 한도. SocketTimeoutException은 허용하지 않으며 로컬 철회/원격 재허용 거부/재동의 복구 검증 유지.
- LocalModelsTest:41의 양성 별 출력 검증 실패는 최초 로그에 이유가 없어 원인 미확정. 반환 출력/시간초과 사유를 assertion에 포함. /usr/bin/python3 시작과 생성코드 실행의 합3초 한도를 시작여유15초+생성코드 SIGALRM3초로 분리, AST/출력정확비교/거부 사례 유지. 제품/모델 검증 로직 변경 없음.
- 최초 수정 스크립트는 기본 Python의 stdin 인코딩 오류로 쓰기 전에 실패. 그 상태에서 focused20개는 기존 코드 그대로 통과했으므로 수정 후 결과로 집계하지 않는다. UTF-8 Python으로 파일 수정 후 선커밋/재검증.
- 선커밋 시험 계획: 두 클래스 focused 및 TCP 철회 반복, 전체 CI 재실행. 아직 공개되지 않은 Beta23만 수정된 검증 소스로 태그 갱신하며 기존 공개 버전/태그/파일은 건드리지 않는다. 재게시 전 공개 여부부터 별도 확인.

- 수정 후 focused20개 통과(두 클래스, skip0). 실패 로그가 예외 타입/줄수만 보이는 기본 Gradle SHORT이므로 FULL stack/message로 전환한다. 새 opt-in 실모델/설치 측정도 기존 벤치와 마찬가지로 UP-TO-DATE 캐시 재사용을 금지한다. 추가 모델시험을 자동으로 켜지는 않는다. 선커밋 후 같은 focused와 TCP 철회 반복, 통합 전체 suite 재검증.

- c36771c 전체 회귀 재검증684보고=655통과/29조건부미실행/실패0(1m52s), bootJar성공. --rerun으로 실제 TCP철회 단일시험3회 각각성공(각2초), 캐시된 test결과 아님. 기존 공개버전과 운영소켓은 변경하지 않음. 최초Python실패의 구체 원인은 여전히 미확정이며 CI재시도에서 수정한 경계/진단을 검증한다.
- GitHub API Beta23 release 조회404 확인(아직게시없음). 다음커밋을push 후 미게시 태그만 갱신, 새push CI1개 실행.

- 사용자 링크39749229293/job119725290135는 Windows 의존성준비62행의 저장소POM/module 고정checksum 실패(네트워크다운로드 전). 동일커밋Mac installer는성공. .gitattributes가 shell만LF고정하므로 메타데이터에도LF를고정한다. SHA256 pins/검증을 완화하거나CRLF임의정규화하지 않는다.
- 추가 선커밋 시험 계획: 임시Git index에 고정6메타데이터와 수정전/후attributes를넣고 core.autocrlf=true/core.eol=crlf로 checkout-index. 원본규칙에서checksum불일치재현, 수정규칙에서모든6해시일치·CRLF없음을검증. 실제WindowsOS실행과구별한다. 이후Windows/macOS installer workflow 및전체releaseCI재실행.
- 현재아직미게시(API404)인Beta23 release CI39749275529는 이수정을포함하기위해중단. 모델/제품변경아님. 기존공개태그/자산보존.
