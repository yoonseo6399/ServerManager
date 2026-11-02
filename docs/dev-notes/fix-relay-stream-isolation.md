상태: 중계 큐·쓰기 제한시간·상세 로그 구현, 선커밋 후 기능 검증 예정
# fix/relay-stream-isolation

- 게시된 v0.7.0-beta.28 기반, solo. 실제 장애 원인은 미확정이며 확인한 공유 수신 루프의 endpoint 동기 쓰기 위험을 개선한다.
- 플레이어/게임 소켓별 bounded writer(512KiB, 전역16MiB), 읽기 루프와 쓰기 분리, 10초 쓰기 기한. 과적체 시 해당 stream만 정리한다.
- 공유 터널은 stream별 FIFO+round-robin bounded 송신 큐(데이터4MiB/stream512KiB), 별도 reader의 제한된 대기 및 공통 socket 쓰기10초 기한. 같은 물리 TCP의 패킷 손실은 여전히 공유 영향이 있다.
- DATA 뒤 CLOSE는 drain 보존, 폐기/인증 철회/업데이트 drain 경로 유지.
- 로그는 상주 호스트 logs/relay-host.jsonl, 제어 계층 logs/relay-runner.jsonl. UTC 시각·PID·stream/instance/connection 식별자·전송량·마지막 송수신 나이·지연·종료 이유·원인 예외 타입·재접속·60초 snapshot. private 권한,5MiB×현재+4백업,512항목 비동기 로그 큐. 패킷/키/티켓/예외 원문 제외.

## 검증 계획 (구현과 함께 선커밋)
- RelayIoTest: blocked endpoint 격리/쓰기 deadline/overflow budget회수/순서와 CLOSE drain/round-robin/버스트 backpressure/GOAWAY drain.
- RelayDiagnosticsTest: 로그 회전·0600·시각/원인 타입·비밀 예외 메시지 비노출.
- DirectRelayTest/DirectRelayProcessTest/DirectSableTest/DatagramOutboxTest/HostedRelayTest/VoiceChatRelay 관련 회귀. 실제 TCP 다중 플레이어 stall 회귀 추가.
- Kotlin/bootJar 및 릴리즈 프런트 빌드. 사용자 지시로 전체 suite·다른OS 실기·운영 재시작·공개 자산 재다운로드/해시는 생략.
- 상주 호스트를 새 버전으로 교체해야 개선이 적용되므로 게임을 정상 종료한 뒤 업데이트 적용. 본 작업에서 운영 서비스를 자동 재시작하지 않는다.
- 추가 검증 계획: 실제 encrypted Hub+Agent에서 플레이어 미수신/게임 백엔드 미수신을 각각16MiB 전송으로 유도, 해당 stream만 ENDPOINT_BACKPRESSURE로 종료되고 같은 터널의 다른 stream이10회 왕복하는지 확인. UDP 큐 초과는 손실형 datagram으로 처리하고 TCP 종료와 구분해 샘플링 로그를 남긴다.
- 최종 검토 보완: 원격 CLOSE로 drain 중인 socket은 실제 flush/close 전까지 stream count에 유지하여 상주 호스트의 유휴 교체가 꼬리 응답을 자르지 않도록 한다. 실제512KiB 응답 직후 backend EOF의 byte-for-byte 보존과 busy 해제를 추가 시험한다.
