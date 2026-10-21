상태: Beta17 게시 완료 · 공개 자산9개·다운로드 JAR/Pi 구성·고정 Ed25519 서명 검증 완료
# feat/pi-agent-runtime

- 사용자 요청: Pi를 실제 기본 엔진으로 넣고 새 Beta 릴리즈. 단독 작업, 전체 테스트·다른 OS 실행 생략.
- Beta16에서 분기. stable/main/latest 보존. 기존 Strata 시작 실패 수정 fix/strata-ready-lifecycle은 별도 릴리즈 통합. custom actor 보강 fix/agent-session-integrity는 제외.
- Pi 1.3.0 공식 SDK의 native loop/read/edit/write/bash/SessionManager/압축으로 custom actor·자체 요약 생산 경로를 교체. 앱은 요청별 인증 공급자, 원격 capability, 역할/경로/독립 위험 검토·명시적 승인, 수명 관리만 담당.
- Node24.22.1 공식 SHA-256, npm lock integrity 고정, ignore-scripts, private HOME 및 확장/skills 자동 로드 금지. 최초 자동 준비 약375MB(이 Mac). 캐시의 bridge 갱신은 원자적으로 수행.
- READ/EDIT/OWNER 분리, 설정/Pi 상태·비밀 파일 차단, 외부 자동 승인 금지, 검토·RPC 실패/거절/승인 후 변경은 차단. 중지는 실제 native shell과 자식을 종료하고 재요청 허용. 임의 셸은 OS sandbox가 아니며 소유자 명시적 승인 범위임.
- Pi 정식 세션 재개·모델 변경 보존·generation 초기화. 기존 UI 대화 원본 private archive와 명시된 부분 최초 이관. native 읽기 원문에는 요약 모델을 끼우지 않음. 자동/수동 압축 실패·미대상 상태를 구분.
- 관리웹 Pi 준비 상태, Discord 일반 메시지/턴·로그 스레드/실시간 출력·토큰·메모리/중지 유지. 모델 공급자 텍스트만, 이미지 불가.
- 검증: PiAgentRuntimeTest9, StrataLlmTest4, RunnerGatewayTest10, QwenStreamingTest4, LocalContextBudgetTest5 =32 통과. 실제 pinned SDK/Node를 자동 설치하여 검증. 관리웹 TypeScript/Vite 빌드 통과. 현재 프롬프트 export --check 통과.
- 실제 Qwen3.8 27B Q4_K_M,16K/output2048/Thinking off: read→bash→edit→read→bash,105.98초, 약12.6~13.1tok/s. 바이트 정확 비교로 원문/CRLF/마지막 개행·지정 위치 삽입 통과. 입력은 가짜 fixture만, 자동 승인 대신 테스트 콜백의 명시적 승인. 요약 증거 docs/benchmarks/pi-runtime-qwen27-20261021.json.
- live 중 메모리 주의, 스왑 약6.37→9.23GiB. 테스트 모델 해제 후 전용 Ollama11435 종료, 운영11434 및 wired20480 보존. 운영 앱/게임/실행기 설치·재시작 안 함.
- 미실행: 전체 테스트, 다른 OS, 실제 Discord 네트워크/버튼, 실제 여러 컴퓨터 Pi end-to-end, Flash 전체 Pi loop. Strata provider는 모의 HTTP 검증이며 시작/정지는 별도 수정 브랜치 검증.
- 구조/주입문서: docs/architecture/pi-agent-runtime.md, agent-context.md, agent-context-prompts.md. 예전 actor 소스는 호환·실험용이며 기본 엔진 fallback이 아님.

- 게시: https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.17 · CI39605313470 성공, 전체 suite skipped 확인. stable/latest v0.6.3 보존. 실제 운영 서비스 업데이트/재시작 안 함.
- live 조건 보충: 전용 Ollama11435, KV q8_0/Flash Attention on/parallel1. 운영 Ollama의 KV 설정은 바꾸지 않았으며 동일 속도·메모리를 보장하지 않음. telemetry snapshots에는 독립 위험 검토도 포함.
- 원시 fixture/통합 검증 보관: 프로젝트 benchmark-evidence/pi-runtime-beta17-20261021.tar.gz, SHA256 38c29433afb2668219486d172d37dda14f60fa523bdb13252543d0b90c13323f.
