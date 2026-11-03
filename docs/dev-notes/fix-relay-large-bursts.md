상태: 구현·관련70개 통과,6MiB 작은 조각 전체 일치·64MiB slow-peer 격리 검증 완료
# fix/relay-large-bursts

- v0.7.0-beta.30 기반, solo. 사용자 추가 요청으로 로그인에 한정하지 않고 큰 버스트를 여유 있게 처리한다.
- endpoint16MiB/역할128MiB 필요 시 버퍼, 터널 데이터16MiB/stream4MiB/4096항목, write 정체60초. byte 상한·backpressure·느린 접속 격리·조각 병합·EOF 유지.
- 검증 계획:6MiB/24576 작은 조각 원문 일치·메모리 회수,64MiB 의도적 미수신 endpoint만 격리 후 다른 접속 유지, 기존 중계 기능69개 회귀. 전체 suite/다른OS는 생략.
- beta30 게시 자산은 보존하고 새 beta31에 통합한 뒤 이미 승인받은 정상 재시작으로 적용한다. 운영은 아직 변경하지 않았다.

- b3947a6 제품 커밋에서70/70통과(64초), 실패/스킵0.6MiB+marker 전체 원문 일치, byte예산0회수·실제 encrypted64MiB 미수신의 offending stream만 종료 후 다른 접속10회 왕복 통과. Kotlin/bootJar 성공.
