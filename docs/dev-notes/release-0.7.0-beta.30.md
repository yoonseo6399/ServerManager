상태: beta30 게시 완료, 추가 요청된 차원 이동 버퍼 완화는 beta31로 준비·운영 적용 대기
# release/0.7.0-beta.30

- origin/release/0.7.0-beta.29 기반, fix/relay-login-burst9be4b25 통합(solo). 운영 로그인 실패32항목 false-positive를 재현하고 bounded chunk coalescing으로 수정했다.
- VERSION·릴리즈 노트·통합 검증 계획 선커밋. 관련 backend69개와 Kotlin/bootJar·TS/Vite, 보안·runner setup·서명/packaging/isolated bootstrap.
- 사용자 재시작 적용 승인 수신. 새 버전 준비 뒤 정상 Minecraft 종료→실행기/상주 중계 교체→같은 서버 재시작. 월드/모드 변경 없음. 적용 전까지 운영 서비스는 유지한다.
- 전체 suite/다른OS 실행/공개 자산 재다운로드 해시검사는 생략. 기존 서명 CI workflow_dispatch skip_full_tests=true. immutable beta30 신규 게시, stable latest/main/이전 공개자산 보존.

## 통합 결과
- bb0728a 제품 커밋에서69/69통과·실패/스킵0(63초), Kotlin/bootJar·TS/Vite·보안11·runner setup 통과.
- 최초 실패 재현과 수정 후 원문 일치 모두 보존. 월드/모드 변경 없음. CI 서명·패키징 후 승인받은 운영 적용 진행.

- CI41580899667 success, 2026-11-03T05:00:04Z immutable beta30 게시. 추가 사용자 요청(로그인뿐 아니라 차원 이동 등 대량 전송도 여유롭게)에 따라 새 beta31을 준비한 뒤 승인받은 재시작을 한 번만 적용한다. beta30 자산은 보존한다.
