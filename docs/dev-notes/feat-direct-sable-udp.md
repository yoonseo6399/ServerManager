상태: 직접 Sable UDP·관리 UI 구현 및 범위 검증 완료, Beta14 통합 완료, 게시 대기
# feat/direct-sable-udp

- 기반: published Beta13 + fix/direct-relay-selection a784c43. 단독 구현, 전체 테스트와 다른 OS 실행은 사용자 요청으로 생략.
- 플레이어 UDP → 선택한 중계 실행기 → 기존 인증·암호화 DRP1 TCP 터널 → 게임 실행기 loopback UDP. 관리 서버는 데이터 경로에 없음. 순수 종단 UDP가 아니며 TCP 손실 시 지연 가능.
- 실제 TCP 소켓/인스턴스/동의/정책/티켓 epoch와 토큰을 묶고, 등록 helper는 resident host가 소유. 일반 제어 계층 재접속에도 유지. 지원 범위 NeoForge1.21.1/Sable2.0.6, 실행 중 게임에는 다음 정상 시작부터 helper 적용.
- opt-in 기본 OFF. 새 기능 지원을 resident host의 인증된 보고로 확인한 뒤 허용. UDP 충돌/실패는 TCP fallback, 포워딩은 TCP공유게임포트 + UDP같은포트 + TCP별도실행기포트.
- 관리 UI: 직접 경로/전송 방식 카드 + 공통 주소/포트/DNS 카드. 기존 관리 Mac 경유 방식은 접힌 고급 설정으로 분리.
- 설정 추가 필드는 revision-bound 별도 marker에 저장하여 Beta13 롤백의 기존 settings.json 스키마 유지. UDP OFF wire 필드 생략.
- 관련 백엔드 60개 모두 통과, 후속 UDP bind 재시도 검증 1개 추가. UI6개·프런트 production build·격리 설치기 검증 통과. 상세 docs/testing/beta14-direct-relay.md.
- 실제 공유기/인터넷/설치 Minecraft 검증은 미실행. 최초 기존 TCP 왕복 검사 1회 실패 원인은 확정하지 못함. 이후 전체 범위 실행 및 관리자 테스트3회 연속 통과, 원문 증거 보존.
