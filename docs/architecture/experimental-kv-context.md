# 실험 KV 압축과 긴 실행 창

목표는 27B 64K / Flash-Next 16K이며 현재 **실모델 검증 전**이다. 옵션 지원·코드 전달 검증은 실제 해당 길이 입력을 처리한 것과 다르다. 사용자 요청으로 Illustrator 종료 전 모델 로딩/생성 시험을 하지 않는다.

## 설정
관리웹 로컬 LLM의 **실험 KV 캐시 압축** 토글(기본 꺼짐)을 저장한다. 27B는 **TurboQuant 엔진 준비** 버튼으로 고정 Metal 배포물과 격리 OpenSSL을 설치한다. 설치/지원 검사는 모델을 로드하지 않는다. 파일 직접 편집은 필요 없다. 실제 추론 컴퓨터를 선택하여 준비해야 하며, 자동 선택은 관리 앱의 설치 상태와 다를 수 있다.

- `/agent context action:configure window:65536` : 채널 64K 설정. `limit`으로 압축 시작점을 별도 지정한다. 창 변경은 Pi 세션 설정에 반영되며 기본 창8K는 바꾸지 않는다.
- `/agent run prompt:... window:65536` : 해당 요청만 64K.
- Flash-Next는 `window:16384`. Strata는 8K/16K만 허용하며 32K/64K를 조용히 축소하지 않는다.
- 큰 실행 창 설정만으로 모든 대화를 보존하지는 않는다. 출력 예약·실제 입력 토큰·Pi 압축 기준·문자/전송 상한이 함께 적용된다. 64K에서 입력 예산은 `65536 - output - 256`.

## 실제 실행 경로
토글 꺼짐: 기존 Ollama/Strata(FP16 KV). 켜짐: 27B는 별도 TurboQuant fork 서버의 `q8_0` K + `turbo4` V, Flash-Next는 기존 고정 Strata의 `--kv q4_0`(Hadamard 회전 Q4). **두 구현은 같지 않으며 Flash-Next에 Google TurboQuant를 적용했다고 주장하지 않는다.** 다른 모델에는 토글을 적용하지 않는다. native backend 오류를 일반 모드로 대체하지 않는다.

27B는 Ollama `/api/show`의 로컬 GGUF를 읽어 재사용하며 모델을 다시 받지 않는다. Ollama 파일·OS wired 한도·환경 전역 설정은 수정하지 않는다. 요청 실행 때만 기존 관리 대상 모델을 먼저 내리고 별도 서버를 올린다. `--parallel 1`, 요청한 ctx/fit 최소 창 고정, 자동 GPU 배치, prompt RAM cache 꺼짐. `/props`의 실제 창을 확인하고 불일치면 중단한다. CPU 부분 실행이 가능해도 통합 RAM 총량이 늘어나는 것은 아니다.

Strata는 요청별 임시 config에 max-context/kv를 지정하고 설치 원본 config를 보존한다. 설정이 바뀌면 본인 서비스만 중지/재시작한다. 시작 후 `/health`가 보고한 창이 요청보다 작으면 실패한다.

새 runner capability `KV_CONTEXT_V1`이 있어야 큰 창/압축 요청을 전달한다. 구버전 실행기로 보낼 때 명확히 거절하며, 압축 옵션을 무시하거나 다른 컴퓨터로 대체하지 않는다. Pi provider 입력 한도1MiB, runner 외부 봉투4MiB 등 바이트/문자 상한도 여전히 존재한다.

## 고정 의존성
- TheTom/llama-cpp-turboquant `tqp-v0.4.0` macOS-arm64 Metal tar.gz SHA256 `f38d0c68e2db29b705ec3944360296c1f8003e4f920d60e6740f9968f3f93769`.
- Homebrew 공식 OpenSSL3.5.9 arm64_sequoia bottle SHA256 `82b33ce156b289eaf95657b9ec16e8f49cb49c882cdeaeaf57e3e7d67fa85e25`. macOS15 이상 지원. 시스템 Homebrew에 설치하지 않고 전용 디렉터리와 해당 자식 프로세스 DYLD_LIBRARY_PATH만 사용한다.
- 공개 읽기 GHCR 토큰은 설치 중 메모리에서만 사용. API 서버는127.0.0.1:18081, 매 실행 난수 키, 0600키 파일. 기존 포트를 점유한 다른 서버에 붙거나 종료하지 않는다. 런타임 파일 락으로 여러 앱의 경합을 막는다.

## 미실행 시험
27B64K와 Flash16K의 로딩, 실제 긴 프롬프트, 스왑 증가/압력/다른 앱 응답, 생성속도, tool call, 원문 보존/기억/정확도. 짧은 요청이 큰 창으로 로드된 것과 실제 창 길이까지 채운 것은 나눠 기록해야 한다. 앱 종료 후 한 모델씩 제한 시간·중단 조건을 두고 수행한다.

27B 실제 시작 로그에서 GQA6:1 품질 보호로 K를 Turbo4에서 Q8로 자동 승격하는 것을 확인했다. 이를 숨기거나 보호를 끄는 대신 명시적으로 Q8 K/Turbo4 V를 지정한다. 처음 로드 시험의 자동 승격 결과와 동일한 실제 캐시 구성이다.
