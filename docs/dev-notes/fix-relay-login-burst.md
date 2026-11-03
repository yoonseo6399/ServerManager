상태: 운영 beta29 로그인 버스트의 32항목 false backpressure 확인, 재현 시험 선커밋 후 검증 예정
# fix/relay-login-burst

- 게시된 v0.7.0-beta.29 기반, solo. 운영 서버/중계 읽기 전용 조사, 재시작 없음.
- 2026-11-03 KST13:38~39 로그인5회가 hub ENDPOINT_BACKPRESSURE로 약1.1~2초 만에 종료. queuedBytes103~114KiB로512KiB 이내, lastWriteAge3~4ms. 바이트/시간 제한에 앞서32개 queue entry 상한에 걸리는 beta29 회귀.
- 정상 TCP 로그인 버스트의 작은 fragment들을 bounded chunk로 합쳐서 항목 수 오판을 제거한다.512KiB/전역16MiB·10초 쓰기 한도, stream 격리 및 바이트 순서·EOF drain은 유지한다.

## 검증 계획 (선커밋)
- 먼저 기존 코드에서1초 미만 receiver pause +1000×256B fragment(256KiB)를 보내32항목 거부가 재현되는지 확인한다.
- 수정 뒤 원문 전체 바이트 일치·예산 회수, 기존 정체/상한/쓰기 timeout·다른 접속 유지·실제 encrypted EOF 회귀를 실행한다.
- 관련 범위 테스트·빌드만 실행, 전체 suite/다른OS 실기 생략. 운영 Minecraft/호스트 재시작은 아직 미승인으로 수행하지 않는다.
