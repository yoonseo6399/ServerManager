상태: beta34에서 교체 장애2건 재현, 수정 후 중계 관련45개 모두 통과·푸시 완료
# fix/relay-host-config-replay

- beta34 기반. 운영에서 상주 호스트만 교체된 뒤 같은 실행기 제어/WSS 세션의 sentAgent 캐시가 남아 agents={} 상태를 복구하지 못하는 문제를 수정한다.
- 원하는 설정 본문이 같더라도 최신 실행기 보고에 대상 agent 역할이 없으면 drp-agent-config를 다시 보낸다. 보고된 역할이 있으면 CONNECTING/BACKOFF/REJECTED 상태도 자체 재연결 루프에 맡겨 중복 전송을 억제한다. 로컬 동의·권한·정책·티켓 검증을 우회하지 않는다.
- 선커밋 테스트 계획: DirectRelayManagerTest의 별도 게임 호스트 역할 교체/동일 설정 재전달·정상/대기/거절 상태 중복 억제, HostedRelayTest의 동일 장비 hub+agent 상주 서비스 교체/동일 관리 연결/실제 TCP echo 복구를 검증한다.
- 기존 DirectRelayTest, HostedRelayTest, DirectRelayManagerTest, RelayIsolationTest, DirectRelayProcessTest, VoiceChatRelayTest 중 존재하는 중계 관련 클래스들을 실행한다. 전체 suite·다른 OS·운영 호스트 교체는 생략한다.
- 운영 복구는 앞선 제어 프로세스 재시작으로 완료되어 이번 코드 수정에서 운영 프로세스/Minecraft/월드/DNS는 변경하지 않는다. VERSION/출시 문서/공개 자산은 변경하지 않는다.

- 초기 실행45개 중43통과/2실패. 신규 테스트가 빈 역할 보고를 공개 view의 빈 행으로 가정했으나 view는 해당 행을 숨기는 기존 동작이었다(NoSuchElementException). 제품 코드는 그대로 두고 빈 행 부재로 확인하도록 테스트를 수정한다. 원문 실패 요약 docs/testing/relay-host-config-replay-initial.json 보존.

- 최종 검증: DirectRelayManager/HostedRelay/DirectRelay/DirectRelayProcess/VoiceChatRelay5개 클래스45테스트 실패0·스킵0. 동일 제어 세션에서 상주 서비스(같은 장비 hub+agent) 교체 후 새 터널 및 실제 TCP echo 복구, 별도 게임 호스트 역할 교체, 적용된 정상/재연결/거절 역할 중복 억제 통과.
- 추가 원인 검증: 공개 beta34 제품 코드로 동일 신규 회귀2개만 실행하면 각각 `replacement receives cached settings` / `new host tunnel without manager reconnect`로 실패했다. 수정 파일은 finally에서 복원하고45개 전체 관련 검사를 다시 통과했다. 결과 docs/testing/relay-host-config-replay-baseline.json 및 relay-host-config-replay-results.json.
- 운영 재적용·새 릴리즈는 수행하지 않았다. 출시 때 관리웹에 이 브랜치를 통합해야 영구 보완이 적용된다. 실행기 wire format은 바뀌지 않는다.
