# Pi 승인 정책·Discord 상태창 기능 검증 계획

대상 구현 커밋: `44ed67f` (`fix/pi-reviewer-authority`, 게시된 Beta20 기준).

사용자 지시에 따라 구현을 먼저 커밋하고 테스트 계획도 커밋한 후 아래 범위만 검증한다. 전체 테스트, 다른 OS, 운영 Discord/서버 재시작과 릴리즈 자산 재다운로드·해시 검증은 하지 않는다.

## 테스트할 내역과 통과 기준

1. **독립 모델 판정 보존** — backend UNKNOWN인 복합 Bash, fetch --all --prune, branch 조회, checkout -b, for/pipe가 ALLOW/LOW를 ASK/MEDIUM으로 바꾸지 않는다. 정적 명령 미지원만으로 실행을 막지 않는다.
2. **자동/명시 승인** — 정확한 원 요청에 대한 userAuthorized=true LOW/MEDIUM은 자동 모드에서 재확인하지 않는다. 명시 모드는 Bash/변경을 확인한다. 동의 없는 MEDIUM/ASK/HIGH는 사람에게 묻고, 모델 DENY/검토 실패·사용자 거절은 실행하지 않고 이유를 모델에 전달한다. Gradle trust가 검토를 우회하지 않는다.
3. **실제 Pi·Git 연쇄 실행** — 격리한 임시 Git 저장소와 localhost HTTP 원격에서 fetch --all --prune → branch 조회/grep → ff-only pull → beta21 로컬 브랜치 생성 → status를 수행한다. 결과 파일이 BEFORE→AFTER이고 beta21 ref가 존재하며 재승인 카드가 없어야 한다. 운영 저장소는 변경하지 않는다.
4. **범위와 권한** — OWNER의 native 파일/셸 작업은 경로/파일명/fingerprint만으로 백엔드가 거절하지 않는다. 독립 모델의 외부 효과 ASK/DENY는 유지한다. READ/EDIT 역할의 Bash·범위 제한, 다른 사용자의 승인 거절, 설정 변경/중단 처리는 유지한다.
5. **스크립트 내용 참고** — 독립 검토에 실행 파일 실제 내용과 해시·누락 여부가 전달되고, 위험한 내용 DENY 후 시험 표식이 생성되지 않아야 한다. 정적 fingerprint veto 제거로 내용이 바뀔 가능성은 별도 제한으로 명시한다.
6. **선택 답변 표시** — 원 질문 메시지에 고른 선택지와 응답자 ID가 남고 버튼이 제거된다. 자유 답변 최대2000자는 필드 분할 후 원문과 일치한다. 답변은 실행 승인으로 사용하지 않는다. 버튼 즉시 갱신 코드는 검토하고 실제 Discord 클릭은 미실행으로 구분한다.
7. **상태창 수명·순서** — 고정 상단은 요청/모델/진행/토큰·메모리·속도 요약을 유지한다. 응답 메시지 새 청크 뒤 하단 상태창을 재전송한다. 승인/도구 실행 때 같은 메시지 ID가 카드로 전환되고 다음 actor 턴은 새 하단 상태창을 사용한다. 지연된 이전 상태 갱신이 완료 도구 카드를 덮어쓰지 않는다. 다음 thinking 이벤트에 usage가 없더라도 상단 마지막 토큰·메모리·속도 수치를 지우지 않는다. 중지 버튼은 진행 중 유지·종료 후 제거된다.
8. **표시 회귀** — thinking·마크다운 답변은 일반 메시지, 파일 변경은 diff/원문 버튼, 긴 출력 원문 보관/읽기 및 정확한 소스 원문, read_result가 새 작업 임베드를 만들지 않는 기존 동작을 확인한다.
9. **소스·문서 일치** — 프롬프트 exporter --check 및 git diff --check. 제품 소스/문서에는 폐기한 static Bash gate를 현행 정책으로 설명하지 않아야 한다.

## 실행 예정 명령

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test \
  --tests local.servermanager.PiIntentOutputTest \
  --tests local.servermanager.PiExecutableEvidenceTest \
  --tests local.servermanager.PiAgentRuntimeTest \
  --tests local.servermanager.QwenDisplayTest \
  --tests local.servermanager.QwenStreamingTest \
  --tests local.servermanager.ProjectAgentFileChangesTest \
  --tests local.servermanager.ProjectAgentResultsTest --console=plain
python3 scripts/export-agent-context.py --check
git diff --check
```

Pi는 고정 실제 SDK1.3.0을 사용하며, 회귀 테스트의 모델은 재현 가능한 판정 fixture다. 이것을 실제27B 판단 품질 검증이라고 표현하지 않는다. 실제27B 검토는 별도 opt-in 시험이며 실시하면 시간·원문·성공/실패를 따로 기록한다.

## 선행 실행 기록

사용자의 커밋 우선 지시 전에 시작했던 실행은 결과를 보존한다. 초기 Kotlin 컴파일에서 JDA6 API의 버튼 조회 속성 오류를 발견해 event.button으로 수정했다. 이후 승인·실제 Pi 관련32개 통과. 다음 UI 포함47개 실행은46개 통과/1개 실패: 새 UI 테스트 mock이 setComponents 인자를 Collection으로만 처리해 ClassCastException 발생. 해당 fixture의 Collection/vararg 처리 수정과 delegated-role ACL 보존 보완도 먼저 커밋한 뒤 재검증한다. 이 결과는 현재 최종 구현 전체의 통과를 의미하지 않는다.

## 최종 결과

아직 미실행. 후속 검증 커밋에서 실제 결과·미실행 범위를 기록한다.
