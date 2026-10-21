상태: 구현·관련 기능 검증 완료, Beta16 릴리즈 통합 대기
# feat/qwen-new-model-metrics

- 기준: remote 확인 후 게시된 v0.7.0-beta.15. 기존 main/stable 변경 없음. 단독 작업.
- Qwen3.8 27B는 Ollama의 `qwen3.8:27b-q4_K_M`을 사용한다. 기본 `qwen3.8:27b`는 이 Ollama에서 MLX NVFP4를 선택하며 메모리 예산 문제를 보였으므로 혼동하지 않는다.
- Qwen3.8 Flash-Next는 별도 Strata 0.2.1 / control 0.1.46.3 / engine 0.1.46-metal, Q2_0 full-expert SSD 오프로딩. Apple Silicon Mac의 검증된 8K 실행 창만 지원한다. OpenAI chat-completions SSE + native function tool_calls를 기존 승인·권한 하네스로 변환한다. 메인·독립 검토 모두 같은 모델/선택한 추론 실행기를 사용한다.
- 관리웹 환경 확인에 설치 버튼, 설치 진행, RAM·스왑·압력·모델 할당/RSS·현재 wired 한도. 환경 조회 시작 후 10초 간격 갱신. 자동 선택에서 환경 조회는 관리 앱 컴퓨터이며 이를 명시한다. 실제 원격 메트릭은 추론 실행기에서 전달한다.
- Strata 자동 설치는 고정 release tar SHA-256 및 공식 setup의 모델 revision/파일 검증을 사용. 사용자의 클릭으로 약 66.4GB 다운로드를 시작한다. 구성 파일을 직접 편집할 필요 없다. 설치 위치 `~/.servermanager-llm/strata`. LLM 사용 동의와 freshDesktop 설정 검사를 유지한다.
- Strata 실행은 loopback:18080, 동시 모델 실행 방지, 프로세스 간 파일 잠금, 소유한 자식만 종료, 앱 종료 시 정리. macOS 15.1에서 확인한 prompt GEMM 비활성·window·COOP_K4=0·4GB arena 설정. 근사 tail skip/CPU share는 비활성화한다. 기본 3분 대신 Flash-Next에 한해 최대 20분, 중지/연결 취소 유지.
- 턴 임베드 footer에 실제 생성 tok/s + 추론 컴퓨터 메모리 스냅샷. Ollama eval_count/eval_duration, Strata predicted_per_second. 로딩/입력 처리 시간이 포함된 wall time과 구별하며 미수신은 추정하지 않는다. Strata RSS 합계에는 공유 페이지 중복 가능성을 명시한다.
- 관리자 컴퓨터의 iogpu.wired_limit_mb를 사용자 요청대로 20480으로 재설정했다. 이 변경은 현재 OS 설정이며 앱이 임의 변경하거나 재부팅 지속 설정을 설치하지 않는다.
- 전체 테스트/다른 OS 실행은 사용자 지시로 생략. 기능별 결과는 완료 후 추가한다.

## 검증

- 관련 백엔드 38개 통과: 카탈로그/설정·네이티브 도구 변환·SSE 조각 합치기·미완성 응답 거부·메트릭 계산·Discord 임베드·기존 모델 전환·원격 실행기 capability 및 메트릭 전달. 전체 suite 미실행.
- 실제 이 Mac: 두 모델의 read_file 요청 → 실제 파일 결과 전달 → 임의 생성한 원문 값 응답, CRLF 원문 바이트 보존 통과. 27B 41.41초/마지막 생성 12.73tok/s, Flash-Next 167.37초/7.9tok/s. 같은 작은 두 턴 측정이며 일반 성능 비교로 확대하지 않는다. 27B 메모리 압력 주의, Flash 완료 시 정상; 두 모델 완료 후 해제 확인.
- 최종 runtime 코드로 Flash-Next를 다시 시작하고 입력 처리 도중 중지: 호출 반환 0.006초, 소유 프로세스 종료와 파일 잠금 해제 확인. Discord 실제 버튼 클릭은 미실행; 기존 gateway 취소·표시 단위 테스트를 포함한다.
- Playwright 1개 통과: 새 모델 모두 선택/저장, RAM·스왑·wired 표시, 설치 버튼 POST와 설치 후 비활성, 메모리 해제.
- TypeScript/Vite 빌드·bootJar·runner setup 통과. bootstrap 결과는 릴리즈 기록에 남긴다.
- 기존 실험의 원문 보존 편집/검색/merge/별 Python 실행 각각 4개는 두 모델 모두 통과한 별도 native API 측정이다. 이번 변경 후 실제 모델 테스트는 위의 읽기 연쇄 및 중지 기능으로 한정했다. Flash 전체 Discord 하네스·새 컴퓨터에서 66.4GB 첫 다운로드·다른 OS 실행·긴 8K/16K 입력은 미실행이다.
