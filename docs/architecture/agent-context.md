# AI에게 전달되는 문맥과 구성 — Pi

Pi SDK 1.3.0이 기본 엔진이다. Beta 11의 custom pinned 요구사항/영수증/요약 loop는 더 이상 생산 경로에서 사용하지 않는다. [현재 구조·권한·이관·압축](pi-agent-runtime.md), [앱이 주입하는 실제 지시문](agent-context-prompts.md)을 참고한다.

| 구성 | 실제 담당·내용 |
|---|---|
| system | Pi의 native 프로젝트 도구 지시문 + 앱의 루오노타르/루오 역할, 다단계 실행/검토, 실행 위치, 승인 경계, 원문 보존 지시 |
| tools | 역할에 따른 Pi read/edit/write/bash 및 앱의 파일 검색·프로젝트·첨부 부가 도구 schema |
| user | 현재 요청자 표시 이름·Discord ID·현재 요청 원문을 JSON 데이터로 전달 |
| history | native Pi 세션의 user/assistant/thinking/tool 결과. 프로젝트/모델 변경에 따라 앱이 다시 짜지 않는다 |
| migration | 최초 한 번의 부분 레거시 대화 안내. 원본은 private archive에 보존. 과거 요청은 자동 재개하지 않음 |
| compression | 사용자가 정한 유효 임계값에 따른 Pi native 자동/수동 압축. 이름·식별자·정정·미완료 작업 보존 지시 |
| risk reviewer | actor history·도구 없이 별도 호출. 현재 사용자 요청 + 정확한 동작·정규화 대상·scope만 검토 |
| UI memory | 짧은 대화 transcript는 채널 라우팅/호환 표시용. 정식 실행 문맥은 private Pi JSONL |

파일 읽기 원문을 별도 요약 모델에 보내지 않는다. SDK가 범위 제한한 긴 도구 출력은 읽지 않은 부분을 읽은 것으로 취급할 근거가 아니다. Discord에 보이는 표시용 출력 길이 제한과 모델 입력은 구분한다.

실행창 8K/16K, 출력 예약, 압축 기준, 모델 자체 최대 한도는 다른 값이다. 공급자의 실제 사용량과 Pi의 문맥 추정치를 표시하며 압축 후 실제 토큰은 다음 공급자 응답 전까지 추정치일 수 있다. 모델 최대 한도까지 메모리를 할당했다는 뜻은 아니다.

순수 inference backend만 다른 실행기로 보낼 수 있다. 실제 파일·명령 도구는 관리 앱 컴퓨터에서 실행한다. 봇 채널 접근/요청자/역할·명시적 승인과 경로 검사는 모델 밖에서 수행한다.
