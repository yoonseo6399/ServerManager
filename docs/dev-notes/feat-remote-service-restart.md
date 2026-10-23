상태: 구현·관련 기능 검증 완료, 릴리즈 통합 준비

# feat/remote-service-restart

- 기존 runner restart API/화면을 재사용하고 관리 앱 동일 버전 재시작 API와 소유자 시스템 패널을 추가한다. fresh passkey와 기존 owner route/runner registrant-or-owner ACL을 유지한다.
- 관리 앱은 lifecycle gate에서 직전 상태를 다시 검사한 뒤 bootstrap exit 75로 재시작한다. 업데이트/위치 변경과 섞지 않으며 임의 JVM 실행 시 미지원이다. UI는 공개 세션의 비밀 아닌 boot UUID가 바뀐 뒤 완료로 표시하므로 인증 세션 소멸 중에도 확인할 수 있다.
- 실행기는 감독 프로세스가 있을 때만 RESTART_V2와 boot UUID를 알리고 요청 토큰을 ACK에 돌려준다. 등록자/소유자 화면에 지원 여부와 요청/재연결/완료/실패를 표시한다. 같은 프로세스 연결 회복을 완료로 오인하지 않으며 구형 V1은 재연결까지만 확인한다.
- 제어 계층의 게임/전송/중계/업데이트 작업이 재시작을 막으면 사유를 표시한다. OS·게임호스트 자체 재부팅 기능은 아니다. 운영 장비를 이번 개발 중 실제 재시작하지 않는다.

## 커밋 후 검증 계획

- Lifecycle: supervisor 유무, fresh passkey, 중복, pending update, 수락 후 새 작업 시작 시 중단, legacy game 거부, 실제 scheduled gate에서 exit callback까지 도달.
- runner: 정확한 ACK 토큰, raw jar/서버 준비 중 거부, supervised exit callback, 중복, 새 boot와 같은 boot 재연결 구별. HTTP security chain으로 owner/등록자/다른 사용자 권한 확인.
- 브라우저: 확인 취소, 승인 요청, 재인증 재시도, 연결 끊김과 boot 변경 완료, 지원 없는 실행기 잠금/재연결 상태.
- 기존 hosted-game 보존/bootstraps와 최종 통합 전체 회귀. 테스트·미실행 결과를 인계 문서에 남긴다.

통합 검증: docs/testing/operations-controls.md 참조. 사용자 최신 지시로 전체 테스트는 생략. 운영 서비스 변경 없이 기능 시험 및 실제 모드 읽기 전용 스캔 수행.
