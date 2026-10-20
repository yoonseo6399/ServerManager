상태: TCP 목록·최초 선택·저장 수정 검증 완료, 직접 Sable UDP와 Beta14 통합 완료, 게시 대기
# fix/direct-relay-selection
- Beta13 기반 단독 작업. 등록된 활성 실행기를 보고 전에도 목록에 표시하며 미확인/거부/오프라인을 구분. 최초 선택을 config.relayRunnerId에 저장하고, 허용된 온라인 실행기를 선택해야 활성 저장 가능.
- 기존 활성 대상 변경은 prepare/commit만 허용. 재연결 시 이전 동의를 지우고 최신 보고를 기다림.
- HostedGames가 상주 서비스 재연결 때 중계 출력 callback을 다시 붙이고 제어 계층에서 10초 간격으로 상태 보고 요청을 재시도.
- 늦은 이전 revision 폴링이 저장 결과를 되돌리지 않도록 차단. 공유 게임/SRV 포트와 실행기 연결 포트를 구분 표시.
- 관련 백엔드36개/브라우저5개 통과, production frontend build 통과. 전체/다른OS 생략.
- 최초 브라우저 시험에서 option disabled 검사 방법 오류 수정 후 통과. 무관한 기존 실행기 업데이트 로그 펼침 테스트는 Beta13에서도 hidden 실패 재현, 본 범위 변경 없음. 원문 build/relay-selection-evidence/ 보존.
- 사용자 요청에 따라 로컬 제어 계층3985→85140 정상 재시작, 게임호스트4076 및 기존 게임 PID 유지 확인. 운영 공유 포트/DNS/직접 중계 활성화는 변경하지 않음.
