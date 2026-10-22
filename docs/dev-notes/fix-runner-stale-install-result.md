상태: 실연결 복구 관측 · 삭제된 설치 결과 보호 수정/시험 계획 선커밋
# fix/runner-stale-install-result
- Beta23 태그 기준 단독 수정. 실제 Mac Mini 실행기와 iMac 관리 서버의 반복 단절 조사. 원격 로그에서 remoteInstallResult→runtime→instance의 없는 ID 예외로 WebSocket 종료 확인.
- 실행기에는3일 전 OK install-result가 남았고 관리 서버에 해당 인스턴스가 없었다. 재연결/heartbeat마다 전송되어 반복 종료. 원문 한 건을 사용자 홈의 private quarantine/install-results 폴더로 원자 이동/원문 동일 확인, 운영 앱/실행기/게임호스트 재시작 없음. 이후 원격 종료 오류가 멈추고 TLS 연결 유지 관측.
- remoteInstallResult가 없는 인스턴스/런타임을 무시하고 같은 런타임 잠금 안에서 삭제/교체 여부를 다시 검사한다. 기존 runner/ticket/동기화 검증 유지. 네트워크/인증 설정 변경 없음.
- 선커밋 시험 계획: 실제 ServerManager+Spring 예외 decorator에 없는/삭제된 결과3회 후 heartbeat-ack/소켓 미종료/경로 미재생성 확인. 결과가 런타임 잠금에 대기 중 삭제되는 경쟁을 강제로 재현. 기존 파일 동기화 시험에 만료 ticket 거부/현재 ticket 보존 추가. 관련 회귀와 전체 suite/bootJar 검증. 실제 운영 연결은 같은 PID·고정 TCP 세션·서버 오류 마지막 시각을 관측한다.
- 새 릴리즈 게시나 운영 바이너리 교체는 이 디버깅 단계에서 수행하지 않는다. 수정은 별도 브랜치에 커밋·푸시하고 재발 방지 적용 상태를 구분해 보고한다.
