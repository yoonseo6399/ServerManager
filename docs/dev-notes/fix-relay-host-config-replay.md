상태: 설정 재전송 및 호스트 교체 회귀 구현, 선커밋 후 기능 테스트 예정
# fix/relay-host-config-replay

- beta34 기반. 운영에서 상주 호스트만 교체된 뒤 같은 실행기 제어/WSS 세션의 sentAgent 캐시가 남아 agents={} 상태를 복구하지 못하는 문제를 수정한다.
- 원하는 설정 본문이 같더라도 최신 실행기 보고에 대상 agent 역할이 없으면 drp-agent-config를 다시 보낸다. 보고된 역할이 있으면 CONNECTING/BACKOFF/REJECTED 상태도 자체 재연결 루프에 맡겨 중복 전송을 억제한다. 로컬 동의·권한·정책·티켓 검증을 우회하지 않는다.
- 선커밋 테스트 계획: DirectRelayManagerTest의 별도 게임 호스트 역할 교체/동일 설정 재전달·정상/대기/거절 상태 중복 억제, HostedRelayTest의 동일 장비 hub+agent 상주 서비스 교체/동일 관리 연결/실제 TCP echo 복구를 검증한다.
- 기존 DirectRelayTest, HostedRelayTest, DirectRelayManagerTest, RelayIsolationTest, DirectRelayProcessTest, VoiceChatRelayTest 중 존재하는 중계 관련 클래스들을 실행한다. 전체 suite·다른 OS·운영 호스트 교체는 생략한다.
- 운영 복구는 앞선 제어 프로세스 재시작으로 완료되어 이번 코드 수정에서 운영 프로세스/Minecraft/월드/DNS는 변경하지 않는다. VERSION/출시 문서/공개 자산은 변경하지 않는다.
