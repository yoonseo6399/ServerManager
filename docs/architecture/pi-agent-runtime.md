# Pi 기본 에이전트 엔진

`/agent`의 행동·도구 선택·부분 수정·정식 세션 저장·압축은 **@earendil-works/pi-coding-agent 1.3.0** SDK가 담당한다. 기존 custom actor JSON parser, select_tools, requirement ledger, 재시도 보정 loop와 자체 요약기는 생산 경로에서 제거했다. 호환 DTO와 이전 테스트/실험용 소스는 남지만 기본 엔진으로 돌아가는 fallback은 없다.

## 경계

```text
Discord 인증·역할·채널 → ProjectAgentEngine (권한/수명/전송)
  → 검증된 Node + 얇은 bridge.mjs → 공식 Pi SDK 세션/loop/압축
  → 127.0.0.1의 요청별 Bearer 인증 OpenAI 호환 공급자
  → 기존 LocalLlm(Ollama) / StrataLlm / 동의한 원격 실행기
  → 모델의 공식 function tool_calls
  → Pi tool_call hook → 백엔드 독립 위험 검토/사용자 승인
  → Pi native read/edit/write/bash 또는 앱의 부가 도구
```

도구는 **관리 앱 컴퓨터**에서 실행된다. 추론을 다른 실행기로 보내도 파일·셸 실행 위치가 그 컴퓨터로 바뀌지 않는다. 임의 명령은 OS 사용자 권한이며 **OS 샌드박스가 아니다**. 소유자의 셸 승인은 명령의 하위 프로세스와 부작용까지 허용한다. 디렉터리 격리·시크릿 이름 차단이 승인된 임의 셸을 OS 수준에서 가두지는 못한다.

## 설치·공급망

- 사용자는 `/agent`를 그대로 사용한다. 최초 요청에서 private agent profile의 `pi-runtime/`에 자동 준비한다. Node/npm 시스템 설치나 config 직접 수정은 필요 없다.
- Node **24.22.1** 공식 아카이브는 `src/main/resources/pi/node.json`의 SHA-256으로 검증한다. SDK는 package-lock integrity로 고정하고 npm ci --ignore-scripts로 설치한다. `latest`를 실행하지 않는다.
- 공식 Node 출처: https://nodejs.org/dist/v24.22.1/SHASUMS256.txt
- Pi 출처: https://github.com/earendil-works/pi (packages/coding-agent), MIT. Node와 설치 의존성은 각각의 라이선스가 적용된다. lock과 출처는 JAR에도 들어간다.
- SDK와 Node는 최초 인터넷 접근·디스크 공간이 필요하다. 이 Mac 설치는 약 375MB였다. 실패하면 `pi-runtime/last-setup.log`를 확인한다. 검증 완료 후 원자적으로 활성화하고 불완전 설치는 격리한다.
- 개인 HOME/환경에서 API 키를 상속하지 않는다. 프로젝트/global 확장·MCP·skills·AGENTS 자동 로드는 끄고 앱의 인라인 승인 확장만 사용한다. 프로젝트의 실행 스크립트가 안전하다는 뜻은 아니다.

## 권한

- 기존 Discord 모듈/역할·요청자 확인, 명시적/자동 승인 설정을 유지한다. READ: 읽기만, EDIT: 읽기/부분 수정/쓰기, OWNER: 추가 셸 도구. 부가 도구도 기존 역할 허용 목록에 한정한다.
- native read/edit/write는 앱의 정규화된 경로·symlink·변경 fingerprint 검사 후 실행한다. 관리 앱 인증/설정/Pi 상태와 비밀 파일을 native 파일 도구로 열 수 없다.
- 실행 가능한 셸은 scope UNKNOWN이므로 자동 모드에서도 사람의 승인이 필요하다. 기존 제한된 Gradle 신뢰 규칙은 별도로 유지한다.
- read-only로 검증된 내부 읽기는 즉시 허용한다. 변경은 독립 모델의 새 세션에서 위험 검토한다. 파싱/전송/권한 RPC 실패는 허용으로 처리하지 않는다.
- 외부/불확실 경로, 높은 위험, ASK는 사람 승인. 다른 사용자·기한 초과·설정 변경·승인 후 파일 변경은 차단한다. 거절은 그 요청의 남은 도구 실행도 차단한다.
- native read 결과는 모델에 원문으로 전달한다. 앱이 파일 원문을 별도 모델로 요약하지 않는다. 긴 출력의 범위/잘림·원문 임시 파일은 Pi 기본 도구 정책을 따른다. UI에 보내는 snapshot의 60K 제한은 모델이 받는 도구 결과를 바꾸지 않는다.

## 문맥·수명·호환성

- 채널 소유자/길드/프로젝트/초기화 generation별 private JSONL이 정식 문맥이다. 앱을 재시작하거나 모델/컨텍스트 프로필을 바꿔도 같은 세션을 다시 연다. `/agent` context 초기화는 프로젝트 정보는 유지하고 새 세션을 만든다. 이전 JSONL은 보관한다.
- 최초 전환 시 이전 짧은 UI 대화 기록을 private `legacy-history.json`으로 보존한다. 최대 최근 20,000자의 **부분 이관임을 명시한** custom message를 Pi에 한 번 넣는다. 누락된 내용을 모델이 완전한 기억으로 간주하지 않도록 안내한다. 이전 계획/요약을 권한이나 새 작업으로 주입하지 않는다.
- 새 요청은 `{requester:{display_name,discord_user_id},request}`로 전달한다. 과거 대화·표시 이름·도구 출력은 데이터다. Pi는 전체 native assistant/tool history와 기본 압축을 관리한다.
- 자동 압축 기준은 채널 설정을 쓰되 Pi 시스템/도구 공간을 위해 기본 4K 바닥(물리 입력 한도가 작으면 그 이하)을 적용한다. 설정값과 유효값을 상태에 구분해 표시한다. 8K/16K 실행창·출력 예약과 모델 최대 문맥은 서로 다르다. keepRecentTokens는 SDK에 16..512의 적응값을 넘기며, 16 고정 실험값을 사용하지 않는다.
- 수동 압축도 SDK compact를 사용하며 가짜 사용자 요청을 추가하지 않는다. 압축할 과거가 없으면 완료 대신 그 상태를 알려준다. 요약 정확성이 보장되는 것은 아니다.
- 기존 Discord의 일반 답장, 턴 카드, 상세 스레드, 토큰/메모리/속도 표시와 실시간 메시지 갱신을 유지한다. 중지는 native shell 자식과 공급자 요청을 취소한다. 권한/설정 변경은 조용한 셸 실행 중에도 감시한다.
- 추론 실행기는 `PI_PROVIDER_V1` capability가 필요하다. **관리 앱과 추론 실행기를 함께 업데이트**해야 한다. 구형 실행기는 명확히 거부하며 이전 엔진으로 대체하지 않는다. 일반 LLM 채팅 경로는 도구 없는 기존 방식이다.
- 현재 공급자는 텍스트만 지원한다. Discord 텍스트 첨부 읽기/저장/업로드 도구를 연결했지만 이미지 인식은 추가하지 않았다. Strata의 8K 제한도 그대로다.

## 검증·한계

관련 검증은 `PiAgentRuntimeTest`, `PiAgentLiveTest`, 공급자/실행기 focused 테스트와 브랜치 인계 메모를 참고한다. 실제 SDK를 써서 승인·거절·중단/자식 종료·부분 수정/CRLF·세션 재개·압축/초기화를 확인한다. live test는 `PI_AGENT_LIVE=true`일 때만 로컬 27B로 실행한다.

모델이 잘못 계획하거나 툴을 안 부르는 성질 자체가 완전히 해결됐다는 주장은 하지 않는다. 이번 변경은 검증한 오픈소스 loop로의 교체다. 실제 Discord 네트워크/버튼, 여러 컴퓨터 동시 운용, Flash 전체 행동 loop와 다른 OS 실행, 전체 테스트는 별도로 검증해야 한다.
