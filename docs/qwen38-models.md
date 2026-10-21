# 로컬 모델 선택과 실행 상태

관리웹의 **AI 비서·에이전트 → 로컬 LLM**에서 추론 실행기와 기본 모델을 저장한다. Discord 개인 기본값은 `/agent setting default:` 선택지에서 지정하고, 일회성은 `/agent run`의 모델 옵션으로 고른다. 관리 앱과 추론 실행기를 모두 새 버전으로 업데이트해야 한다.

| 선택지 | 실행 방식 | 실제 모델 |
|---|---|---|
| Qwen3.8 27B (Q4_K_M) | Ollama 공식 `/api/chat`·도구 등록 | `qwen3.8:27b-q4_K_M` |
| Qwen3.8 Flash-Next (Strata Q2_0) | Strata 공식 OpenAI chat-completions·도구 등록, SSD expert streaming | `qwen3.8-flash-next-q2_0` |

27B 설치 태그는 `ollama pull qwen3.8:27b-q4_K_M`이다. `qwen3.8:27b` 기본 태그는 Ollama 버전에 따라 MLX NVFP4를 선택할 수 있어 별개의 실행 경로다. 이번 Mac 테스트에 통과한 명시적 GGUF 태그를 기본으로 고정했다.

Flash-Next는 Ollama 모델로 가장하지 않는다. 선택한 실행 컴퓨터에서 **실행 환경 확인 → Flash-Next 설치**를 누르면 Strata 구성요소와 약 66.4GB 모델을 설치하고, 첫 요청에서 자동 시작한다. 설치 자체에는 모델 로딩이 없으며 설치 로그를 웹에서 확인한다. Apple Silicon Mac만 지원하며 75GB 이상의 여유 공간이 필요하다. 사용자 설정 파일 편집은 필요 없다. 이 컴퓨터에는 실험 파일을 `~/.servermanager-llm/strata`로 옮기고 공식 setup으로 경로를 재생성했다.

Flash-Next는 검증한 8K 실행 창을 사용한다. `/agent context`에 16K가 저장된 채널은 8192로 변경해야 한다. 4GB Metal arena, macOS 15 호환 prompt/window 설정을 사용하며, 약식 expert skip 설정은 끈다. 입력 처리는 SSD·문맥 크기에 따라 오래 걸릴 수 있어 Flash-Next 추론에만 최대 20분을 허용한다. 생성 중지는 기존 에이전트 중지 버튼을 사용한다. 도구 실행은 기존 권한·독립 검토·명시적 승인 경로를 그대로 거친다.

모델을 전환하면 이전 관리 대상 Ollama 모델 또는 앱이 실행한 Strata를 먼저 종료한다. 메모리에서 내리기 버튼은 두 엔진 모두에 적용된다. 다른 앱이 18080 포트를 사용 중이면 강제 종료하거나 접속하지 않고 오류를 표시한다. 같은 사용자 계정의 관리 앱과 실행기가 동시에 별도 Strata를 시작하지 않도록 파일 잠금을 사용한다.

**실행 환경 확인** 후 해당 컴퓨터의 RAM·스왑·메모리 압력·모델 메모리가 10초마다 갱신된다. 자동 선택 상태에서 환경 확인은 관리 앱 컴퓨터를 대상으로 하므로, 원격 컴퓨터를 확인하려면 실행기를 직접 고른다. 턴 임베드에는 실제 추론 컴퓨터에서 수신한 완료 시점 스냅샷과 생성 속도가 표시된다.

- RAM은 OS 총량−여유량으로, 회수 가능한 캐시도 포함할 수 있다.
- Ollama 모델 메모리는 `/api/ps`의 전체 할당량 합계다. 모델 파일 크기나 GPU 전용 메모리와 동일하지 않다.
- Strata 메모리는 소유한 서버·엔진 프로세스 RSS 합계이며 공유 페이지가 중복될 수 있다. 두 엔진의 수치를 직접 비교하지 않는다.
- 생성 속도는 Ollama `eval_count / eval_duration`, Strata `timings.predicted_per_second`를 사용한다. 로딩·입력 처리를 포함한 총 응답 시간으로 나누지 않는다. 아직 값이 없거나 구형 실행기가 보내지 않으면 미수신/측정 대기로 표시한다.
- 앱은 시스템 메모리 한도를 변경하지 않는다. 이번 Mac의 20GiB wired 한도는 사용자의 별도 요청으로 적용한 OS 설정이며 재부팅 지속 설정은 설치하지 않았다.

근거: [Ollama Chat API](https://docs.ollama.com/api/chat), [Strata 0.2.1](https://github.com/Niko1221/Strata/releases/tag/v0.2.1). 자동 설치 구성요소는 고정 SHA-256으로 확인하며 공식 setup의 고정 모델 revision과 파일 검증을 사용한다.
