상태: Beta20 게시 완료 · 기능47개·실제27B 위험 검토3사례 통과 · 운영 설치 없음
# fix/pi-intent-output-budget
- 게시된 Beta19 기반, git fetch 완료, 별도 worktree/브랜치 등록. 같은 파일의 다른 브랜치는 모두 게시 완료 상태.
- 사용자 직접 요청을 모델 요약과 분리해 현재 미완료 작업 단위로 보관. 새 요청/명시 중단/설정·epoch 변경/완료에 오래된 동의가 이어지지 않도록 제한.
- 검증된 프로젝트 내부 동작에 독립 검토가 사전 명시 동의를 확인하면 자동 모드 MEDIUM도 재확인 없이 실행. ff-only pull/fetch는 Git 경로·설정·hook 검사; push/PR/외부·미확인 스크립트는 제외.
- /agent setting tool_output_limit(512..8000자, 기본2400). Pi tool_result/context 훅으로 Bash·기타 도구의 큰 결과를 원문 보관+발췌+read_result 참조로 제한. 기존 큰 결과도 공급자 요청 전에 제한. read/edit/write/read_result/첨부·채팅 원문/정보 질문은 정확성을 위해 제외.
- 기능47개·실제 SDK 통합 테스트 통과. 실제27B 검토는 첫 로딩 취소 실패를 보존했고 재검사에서 사전 pull ALLOW/MEDIUM/INSIDE/동의true, 반복 조회 ALLOW/LOW, 숨은 시스템 쓰기 DENY/HIGH를 확인. 검토만 수행했고 해당 명령/스크립트는 실행하지 않음. 운영 앱 설치/재시작·모델 신규 호출 없음.

- 사용자 추가 요구: read_result 페이지마다 새 임베드 없이 원래 작업 카드에 현재/총 줄 수 갱신. JDA mock으로 새 임베드0회·750ms 병합 편집 검증.
- Git 시스템/글로벌 config 자동 로드 비활성화(격리된 Pi 프로세스에만 적용). config/include/filter/hook/외부 부모 저장소·동적/불명확 범위는 재승인 유지.
- 원문 보관24h/20개/큰 Pi temp1.1MB 부분 표시. 파일 읽기/편집/질문 답변은 별도 정확성 한도 유지. docs/testing/pi-intent-output-20261022.md에 실패/성공·미검증 기록.

- 추가 결함 수정: 긴 단일 줄 결과의2000자 뒤가 조용히 유실되던 read_result를 start_char로 이어 읽도록 보완. 실제 전체 원문은 저장 유지. 마지막 개행의 가짜 빈 줄 제외.
- Discord 실시간 출력과 모든 부가 도구의 전체 원문 경로까지 동일한 발췌 설정 적용. 원문 저장/첨부 실패가 원래 실행의 재시도를 유발하지 않음.

- Beta20 게시 소스8de431c, CI39700677048 성공. 외부 Maven 장애2회 뒤 동일 클래스/리소스의 공식 동일 버전 의존성을 SHA256 고정한 CI 복구는 릴리즈 통합 브랜치에 포함.
- stable/latest v0.6.3 유지. 공개9자산은 메타데이터만 확인; 게시 후 ServerManager 자산 다운로드/해시 대조 및 운영 서비스 설치/재시작 없음.
