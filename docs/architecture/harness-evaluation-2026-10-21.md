# 2026-10-21 하네스 결함과 대체 엔진 검토

## 범위와 현재 상태

사용자 결정: 현재 결함을 먼저 수정하고 Pi/OpenCode를 같은 과제로 비교한 뒤 기본 엔진 전환 여부를 결정한다. 혼자 구현하고 전체 테스트·다른 OS 실행은 생략한다. 운영 Discord, 게임, 실행기 설정은 수정하지 않는다. Hermes는 일반 비서 후보로 공식 문서만 검토했다.

저장·압축 결함을 수정한 현재 Kotlin 엔진도 실제 네 파일 삭제 시험에 실패했다. 따라서 단위 검사 통과를 근거로 모델 품질이 해결됐다고 주장하지 않는다. 기본 엔진 교체와 릴리즈는 아직 수행하지 않았다.

## 확인한 결함과 수정

| 확인한 현상 | 이번 수정 | 남는 한계 |
|---|---|---|
| 압축 결과가 목표 요약 대신 function JSON이 됨 | 전용 JSON Schema와 별도 검증; 실패하면 원본 유지; 성공할 때만 완료 표시 | JSON 형식이 맞아도 의미가 정확한지는 보장하지 못함 |
| native 도구 호출/결과가 JVM 재시작 후 사라짐 | 기존 primary 형식은 유지하고 대화 digest·epoch에 묶인 private sidecar로 복원 | 파일 불일치 시 복원하지 않음; 원래 이미 소실된 대화를 재구성하지 못함 |
| 압축 과정에서 원본을 덮어씀 | native 포함 압축 직전 원본을 private transcript에 추가 보관 | 자동 보관 만료/삭제 정책은 아직 없음; reset과 감사 기록 삭제는 다름 |
| `?` 같은 후속 요청이 원래 과제를 대체함 | 원래 요청·계획·미완료 항목·재검증 조건을 유지해 모델/사전 검사/위험 검토에 전달 | 짧은 후속 말의 의도를 항상 정확하게 이해하는 것은 아님 |
| 잘못된 하위 경로의 부재를 근거로 루트 파일도 없다고 결론 | 존재하는 홈의 동명 경로를 오류 데이터로 안내; 계획상 남은 파일 확인 | 경로를 자동 변경하거나 승인 없이 실행하지 않음; 모델이 계속 실패할 수 있음 |
| 봇의 첫 Thinking 단어만 참여자 기록에 저장됨 | 실제 REST 메시지 편집 완료 시 최종 텍스트 기록; 늦은 초기 이벤트의 덮어쓰기 방지 | 실제 Discord 게이트웨이 운용 시험은 미실행 |

이미 도구 JSON으로 손상된 저장 요약은 자동 복구하지 않는다. `/agent context action:reset` 후 원래 요청을 다시 보내도록 안내한다. 프로젝트 정보는 유지된다. 운영 채널을 자동 초기화하지 않았다.

## 실제 시험

Mac M4 Pro, 통합 메모리 24GB, Ollama 0.40.4, 설치된 Qwen3.8 27B Q4_K_M. 운영 모델 원본·메모리 한도는 바꾸지 않았다. 사용자 요청대로 wired limit 20480을 유지했다. 임시 파일만 사용했다.

현재 엔진: 실행 창 8192, Thinking 켬, 기본 주 모델 출력 예약 4096, 압축 기준을 1024로 강제. 원래 네 파일 삭제 계획을 저장한 뒤 `?`로 재개. 531.078초 동안 압축 8회, list_files 2회와 읽기 전용 sh 1회만 실행. 삭제 승인 요청 0회, 네 대상 파일 모두 남음. keep.txt의 CRLF 포함 바이트는 유지. 작업 미완료로 종료했다. **실패**다. 작은 압축 기준이 부담을 늘린 시험이며 일반 사용 속도나 모델 자체 성능으로 일반화하지 않는다.

Pi/OpenCode 비교 과제:

1. note.txt를 읽고 PATCH_TARGET=old 한 곳만 new로 대치한 뒤 재조회. 파일 전체가 기대 바이트와 동일해야 통과한다. CRLF·원문·별도 keep.txt를 보존한다.
2. 홈의 a.txt/aqp.txt/aqq.txt/qq.txt를 나열하고 삭제 계획을 세운 뒤 강제 압축. 짧은 후속 요청으로 네 파일을 모두 삭제하고 목록으로 확인. 네 파일 부재와 keep.txt 원문 바이트를 독립적으로 검사한다. Luonotar 하위는 대상이 아니다.

각 엔진에 같은 기능의 4개 임시 도구(list/read/edit/delete)를 제공했다. 도구는 임시 프로젝트 바깥과 심볼릭 링크를 거부하며 셸·외부 네트워크 실행을 제공하지 않는다. Pi 기본 도구·확장·스킬·프로젝트 자동 로딩은 사용하지 않았고 OpenCode 기본 도구는 deny, fixture MCP만 allow다. 운영 계정·cloud API 키를 제공하지 않았다.

Ollama OpenAI API의 요청 body `options.num_ctx`는 실행 창 설정 수단이 아니다. 초기 Pi 시험은 metadata 8K와 달리 `/api/ps` 실제 4096이었다. 공식 방식대로 기존 blob을 공유하는 임시 num_ctx8192 모델 별칭을 만들고 Pi 실행 중 `/api/ps`의 8192를 확인했다. OpenCode도 같은 별칭을 사용했지만 실행 중 실제 창을 별도로 채집하지는 않았다. 비교용 localhost 전송 어댑터에서 두 엔진 모두 `reasoning_effort:none`, temperature 0, max_tokens 1024를 강제했다. 운영 모델을 재정의하지 않았다.

| 엔진 | 부분 수정 | 압축 후 네 파일 삭제 | 비고 |
|---|---|---|---|
| Pi 1.3.0 | 통과, 34.035초 | 통과, 95.478초 | read→edit→read 및 list→delete 4회→list 실행 |
| OpenCode 1.19.3 | 통과, 36.741초 | 통과, 98.101초 | read→edit→read 및 list→delete 4회→list 실행; 실제 compaction 항목 확인 |

초기 Pi 압축 설정 512 토큰에서는 대화가 짧아 `Nothing to compact`로 두 번 거부됐다. fixture의 keepRecentTokens를 64로 낮춰 실제 압축을 유도했다. 이 두 번을 모델 작업 실패나 성공으로 세지 않는다. 이후 실제 4K 과제는 통과했지만 위 동일 조건 비교에는 넣지 않는다. 최초 실패 및 원시 결과는 로컬 임시 증거에 보존했다.

이 비교는 엔진별 단일 작은 과제다. 현재 엔진은 Thinking·프롬프트·동적 도구 선택·위험 검토·압축 정책이 다르므로 표의 시간으로 현재 엔진 대비 배수 성능을 주장하지 않는다. 외부 엔진이 이 과제에 성공했다는 것은 이 모델이 제한된 조건에서 해당 도구 연쇄를 수행할 수 있다는 근거다. 장기 작업·모든 모델·권한 정확성·Gmail/캘린더 품질은 검증하지 않았다.

47개 관련 backend 회귀 검사가 통과했고, Discord 최종 텍스트 콜백 변경 후 표시 검사 2개도 다시 통과했다. 실제 모델 실패는 별도 opt-in 검사와 JSON 증거로 남겼다. 전체 테스트·다른 OS·운영 Discord는 미실행이다.

## 후보별 연결 비용

| 후보 | 활용 범위 | 현재 제품에 연결할 때 필요한 작업 |
|---|---|---|
| Pi | 코드 작업·세션·압축을 제공하는 SDK | Node 런타임 배포, custom tool bridge, Kotlin 권한/독립 검토/Discord 승인을 유일한 실행 경로로 연결. 기본 도구와 자동 자원 로딩 차단. Pi 자체를 sandbox로 취급하면 안 됨 |
| OpenCode | 별도 서버와 HTTP API로 세션·도구·승인 제어 | 실행기별 고정 버전 바이너리 수명 관리, localhost 인증, 채널 세션 매핑, Kotlin 도구/권한 bridge와 승인 API 연결. 설정 자동 검색·플러그인·추가 모델 호출 통제 |
| Hermes Agent | 기억·스킬·Discord·MCP를 포함한 일반 AI 비서 | 기존 봇/채널/신원/승인/runner 추론과의 중복 소유권 해소. 8K 입력 예산·압축·모델 도구 성능·Python/runtime 설치·권한 경계 검증. 실제 시험은 아직 없음 |
| Aider | 저장소 지도·편집 포맷·코딩 작업 | 일반 비서가 아닌 코딩 전용 도구 후보. 빌드·파일 권한·변경 검토를 기존 backend에 연결해야 함. 이번 실제 비교에는 사용하지 않음 |

## 전환 판단

이번 패치에서 기본 엔진을 즉시 교체하지 않는다. 기존 엔진의 작업 수행 실패를 미해결로 기록하고 릴리즈를 보류한다. 작은 과제 두 개의 성공만으로 운영 권한 연결을 생략하지 않는다.

후속 제품 통합의 우선 후보는 **OpenCode의 localhost 서버 + 기존 Kotlin 도구 bridge**로 정한다. 두 후보의 단일 시험 시간 차이는 선택 근거로 삼지 않는다. 현재 제품이 Kotlin/JVM과 원격 실행기 구조이므로 HTTP 세션/취소/승인 API를 연결할 수 있고, 별도 Node SDK 호스트를 새로 설계할 필요가 적다는 구조적 판단이다. Pi는 테스트에 성공한 SDK 대안으로 남긴다. Hermes는 사용자의 일반 비서 목표에 잘 맞을 가능성이 있지만 같은 환경의 실측이 없으므로 우수하다고 결론 내리지 않는다.

OpenCode의 실제 기록에는 한 assistant 응답에 삭제 도구 4개가 들어왔다. 현재 Kotlin 어댑터는 복수 tool_calls를 전부 실행하지 않고 단일 호출을 다시 요구한다. 동적 select_tools와 이 제약은 조사할 구조 차이다. 원래 실패 시험의 모든 원시 응답을 계측하지 않았으므로 이번 실패의 단일 원인으로 확정하지 않는다.

모델 파인튜닝이 필수라고 결론 내릴 근거도 없다. 같은 Qwen이 제한된 도구·명확한 세션 관리에서는 실제 작업에 성공했다. 반대로 전체 현재 하네스가 해결됐다고 주장할 근거도 없다.

비교 종료 후 임시 서버/MCP/전송 어댑터를 종료하고 시험 별칭만 삭제했다. 운영 모델은 보존, 로딩 모델 0, 메모리 압력 1, wired limit 20480 유지.

## 엔진 전환 시 지켜야 하는 계약

- Discord 사용자/채널/프로젝트/역할/명시적 승인 설정은 Kotlin이 소유한다. 모델이나 외부 엔진이 설정·승인을 만들지 않는다.
- 외부 엔진은 도구를 제안한다. 기존 backend가 경로·권한·fingerprint·독립 위험 검토·필요한 사용자 승인을 확인하고 실행한다. 읽기/편집/명령의 모든 경로가 같은 계약을 거쳐야 한다.
- 세션·압축의 주인은 한 엔진으로 정해 이중 압축하지 않는다. 원래 요청·실제 실행 영수증은 요약과 분리한다. 원문은 복구 가능한 private 기록에 둔다.
- 최종 텍스트·Thinking 표시·도구 시작/출력/종료·입출력 토큰·취소를 명시적 이벤트로 전달한다. 도구 출력은 기존 read_result 저장을 유지하고 읽기 원문을 임의 요약하지 않는다.
- 세션 reset·모델/권한 변경·승인 철회 시 epoch를 재확인하고 이전 결과를 새로운 세션에 반영하지 않는다. 취소가 실제 추론/소유한 자식 프로세스까지 도달하는지 시험한다.
- 기존 엔진으로 돌아갈 수 있도록 opt-in부터 시작한다. 설치·업데이트·해시·런타임·서버 인증·실제 Discord 승인 왕복을 검증하기 전 기본 엔진으로 바꾸지 않는다.

## 공식 근거

- [Pi SDK](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sdk.md), [compaction](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/compaction.md), [security](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/security.md)
- [OpenCode server API](https://opencode.ai/docs/server/), [permissions](https://opencode.ai/docs/permissions/), [MCP](https://opencode.ai/docs/mcp-servers/), [Ollama provider](https://opencode.ai/docs/providers/#ollama)
- [Hermes 공식 저장소](https://github.com/NousResearch/hermes-agent), [기능 문서](https://hermes-agent.nousresearch.com/docs/), [보안·승인](https://hermes-agent.nousresearch.com/docs/user-guide/security/)
- [Aider Ollama](https://aider.chat/docs/llms/ollama.html), [편집 포맷](https://aider.chat/docs/more/edit-formats.html)
- [Ollama OpenAI 호환: 로컬 문맥 크기와 reasoning 제어](https://docs.ollama.com/api/openai-compatibility), [create API](https://docs.ollama.com/api/create)
