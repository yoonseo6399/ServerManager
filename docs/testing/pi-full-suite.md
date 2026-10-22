# Beta21 전체 테스트 수정 계획과 검증 기록

## 실행 전 계획

기준: 게시된 v0.7.0-beta.21 bd0c5c2. 구현/테스트 변경과 검증할 내용을 먼저 커밋하고 테스트한다. 전체 Gradle test를 실행하며 명시적 외부 opt-in 시험의 스킵을 일반 테스트 성공으로 세지 않는다. 다른 OS, 운영 서비스 재시작·실제 모델 로딩·OS 메모리 설정 변경·게시 파일 재다운로드는 이번 작업 범위가 아니다.

기준선 전체 실행:665개 중39실패/23스킵(로컬 macOS, /private/tmp/sm-full-suite-baseline.log). 실패 원문 요약은 ignored build/full-suite-evidence/baseline-failures.json에 보존한다. CI38실패와 비교해 로컬에는 DirectRelayTest 철회 시험1실패가 추가됐다. 아직 제품 원인으로 단정하지 않는다.

## 수정·이관 내역과 통과 기준

- 공통 PiTestFixture는 **실제 고정 Pi SDK**를 실행하고 native function-call transport만 결정적 fixture로 대체한다. 옛 JSON actor를 Pi처럼 흉내 내는 어댑터를 만들지 않는다.
- ProjectAgentTest: native read/edit CRLF 보존, 새 독립 검토의 두 메시지/actor 이력 격리, 명시 승인 대상/일회성/기한/설정 revision/철회, 모델 LOW가 backend scope에 의해 상향되지 않음, 외부 ASK 거절 후 원문 미전달, 이름/ID/대화 저장, prose를 실행 JSON으로 해석하지 않음을 시험한다. 기본 명령 목록에 history/schedules가 추가된 현재 계약도 확인한다.
- Harness/NativeTools: READ의 도구 노출·수정 거절, 허용 역할 값·턴 한도, native bash 직접 등록과 실행 전 독립 검토/승인. 옛 select_tools/정적 shell/문장 보정 loop 기대를 제거하고 **해당 전송으로 실제 실행이 발생하지 않는** 현재 계약을 검증한다. 순수 레거시 helper/parser 테스트는 그대로 남긴다.
- Routing/EditVerify/Reliability: 실제 read→edit→read 결과·원본 줄바꿈/추가 문구 보존, 원문 무요약, JSON shaped prose 미실행, actor 턴 한도에 따른 반복 종료를 검증한다. Pi가 모델의 허위 성공 문장을 의미적으로 검증해 준다고 주장하지 않는다.
- LedgerEngine: 옛 requirement receipts/미완료 원장/자체 repair를 복원하지 않는다. 같은6개 사례를 실제 다단계 생성·재조회, 턴 초과 시 기존 변경 보존/가짜 done 없음, 텍스트 evidence의 실행 권한 부재, 정보 질문, 중단된 native 이력 재개, 작업 중 개입으로 이관한다. 테스트를 삭제·disable해 통과시키지 않는다.
- Workflow: 실패한 실제 빌드 exit7→원문 읽기→native 부분 수정→재실행 exit0→후속 보고, 이전 Gradle 신뢰가 ASK를 우회하지 않음, SDK compaction JSONL·표식 보존, 현행 라우팅 history를 검증한다.
- Steer/Stop: 모델 대기 중 지시의 native 전달·화면 이벤트·다른 사용자 거부, 세션 생성 전5개 지시 유실 없음/상한, inference 중단 후 쓰기 미실행. 실제로 발견한 준비 중 유실·ACK 부재·준비 후 상한 누락을 제품에서 보완한다.
- PersonalServices: 예약 MISSED/ACL 검증 유지. 폐기한 backend UNKNOWN 강제 ASK 대신 **독립 검토가 ASK한 변경은 명시 승인 필요**라는 현재 계약으로 바꾼다. 별도의 서비스 권한 검증을 제거하지 않는다.
- DirectRelay: 철회 직후 이미 수신 버퍼에 있던 데이터를 새 트래픽으로 오인하지 않게 bounded drain→EOF/reset과 철회 이후 고유 표식 미전달을 확인한다. timeout은 실패다. 중계 제품의 차단 기준을 완화하지 않는다.

## 실행 명령

첫 이관 검증은 관련 Pi/Agent 및 DirectRelay 클래스만 실행하고 실패를 고친 뒤 마지막에 `./gradlew test --console=plain` 전체를 다시 실행한다. 새 테스트/코드 수정도 실행 전에 커밋한다. exporter --check와 diff --check도 확인한다. 전체/부분 결과와 실패 원문을 완료 후 기록한다.

## 이관 후 관련 검증

커밋a7dfa29 이후 관련 ProjectAgent*Test 및 DirectRelayTest 실행은 모두 통과했다. 통계는 아래 최종 결과에 기록한다. 실패했던39개에 해당하는 동작을 skip/delete하지 않고 현행 Pi SDK의 실행/승인/문맥 계약으로 이관했다. 이전 의미 검증·문장 repair·custom ledger가 없는 점은 한계로 명시한다.

준비 완료 후 실제 native inference 대기 중에도 여섯 번째 개입 지시를 거절하는 회귀를 추가한다. 이 시험과 문서 변경을 선커밋한 후 마지막 전체 Gradle test를 실행한다.

## 최종 결과

진행 중.
