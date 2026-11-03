# 직접 중계 지연과 동시 접속 종료 진단

Beta29부터 상주 게임호스트와 실행기 제어 계층이 별도 JSONL 로그를 남긴다. 새 실행기 제어 JAR만 적용하고 기존 상주 호스트가 살아 있으면 호스트의 전송 개선은 아직 적용되지 않는다. 관리웹의 구성요소 업데이트 상태에서 GAME_HOST 버전을 확인하고, 게임을 정상 종료한 뒤 상주 호스트 업데이트를 적용한다. 게임 플레이 중 강제 교체하지 않는다.

## 로그 위치와 읽는 법

실행기 홈(기본 `~/.servermanager-runner`) 아래:

- `logs/relay-host.jsonl`: 실제 중계·게임 소켓, 송수신량, 큐와 쓰기 지연, 연결 종료.
- `logs/relay-runner.jsonl`: 중앙 WebSocket 열림/종료 코드/오류 원인 타입·재접속 실패. 레거시 모드에서는 실제 전송도 여기 기록한다.
- 회전 백업 `.1.jsonl`부터 `.4.jsonl`까지, 파일당 약5MiB. POSIX 파일권한0600. 현재+백업 합계 로그 종류당 약25MiB.

`at`은 UTC ISO8601이다. 한국 시각은9시간을 더한다. `connectionId`로 한 터널의 양쪽 stream 이벤트를 식별하고, `side`(hub/agent), `stream`, `instanceId`를 함께 비교한다. 서로 다른 머신의 connectionId는 서로 다르다.

```
tail -f ~/.servermanager-runner/logs/relay-host.jsonl
```

주요 이벤트:

| 이벤트/사유 | 의미 |
|---|---|
| `stream_closed` / `ENDPOINT_BACKPRESSURE` | 특정 플레이어/게임 소켓의 쓰기 큐 한도 초과. 해당 접속만 종료 |
| `stream_closed` / `ENDPOINT_WRITE_TIMEOUT` | 특정 소켓에60초 동안 쓰기 완료 불가. 해당 접속만 종료 |
| `tunnel_slow_write`, `endpoint_slow_write` | 완료된 쓰기가1초 이상 지연 |
| `tunnel_write_failed` / `TUNNEL_WRITE_TIMEOUT` | 공통 물리 터널 쓰기가60초 이상 정체. 이 터널의 접속들은 종료·재연결 대상 |
| `tunnel_backpressure` | 제한된 터널 송신 큐 대기 기한/제어 큐 초과 |
| `tunnel_datagram_dropped` | UDP/음성 큐 혼잡. 손실형 패킷을 버리고 TCP 데이터와 구분, 첫1회와100회 단위로 기록 |
| `tunnel_snapshot` |60초 주기 전송량/마지막 송수신 나이/큐 바이트·프레임/쓰기 진행 시간 |
| `relay_snapshot` |60초 주기 전체 접속/UDP 상태·drop/연결 상태 |
| `POLICY_REVOKED`, `TUNNEL_REPLACED`, `HUB_SHUTDOWN` | 관리자 정책·새 터널·서비스 종료에 의한 명시적 정리 |
| `manager_socket_error`, `manager_connect_failed` | 중앙 제어 연결 오류. 중계 터널 이벤트와 시각 비교 |

`stream_closed`에는 연결 수명·양방향 바이트·종료 직전 큐·마지막 읽기/쓰기 나이가 포함된다. 예외는 최상위와 원인 클래스만 최대5단계 기록한다. 키/티켓/인증 헤더/패킷/게임 채팅/예외 메시지 원문은 기록하지 않는다. 비동기 진단 큐가 넘치면 게임 전송을 막지 않고 다음 이벤트에 `droppedLogEvents` 수를 남긴다. 디스크 기록 불가 안내는 stderr에 분당 최대1회다.

## 전송 격리와 한계

- 플레이어/게임 소켓별 독립 쓰기 태스크와16MiB 한도, 역할당 전체128MiB 큐 예산. 공유 터널 수신 루프는 endpoint write를 하지 않는다.
- 터널 송신은 stream별 FIFO, stream 간 round-robin이며 데이터16MiB/stream4MiB 한도다. endpoint reader만 제한된 시간 동안 큐를 기다릴 수 있다. 공유 수신 루프는 큐 대기하지 않는다.
- 정상 버스트에 TCP backpressure를 적용하며 DATA 뒤 CLOSE는 큐를 비운 뒤 소켓을 닫는다. 인증 철회는 플레이어 소켓을 즉시 정리한다.
- 한 물리 TCP 터널 자체의 패킷 손실/회선 정체는 여전히 여러 접속에 영향을 준다. 이번 수정이10/31~11/2 실제 장애의 원인을 확정하거나 Wi-Fi/공유기 문제를 없애는 것은 아니다.

Beta31: 로그인·차원 이동·청크 전송을 같은 경로로 취급한다. 조각 수 제한 대신 최대32KiB chunk 병합과 여유 있는 바이트 예산을 사용하며, 터널 송신 큐의 항목 상한도4096으로 늘렸다. 버퍼는 필요할 때만 사용하고 예산을 넘긴 무한 적재는 허용하지 않는다. 실제 물리 회선 단절과 게임 자체 keep-alive 제한은 별개다.
