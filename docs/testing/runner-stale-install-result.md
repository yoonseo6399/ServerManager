# 실행기 설치 결과 재전송으로 인한 제어 연결 단절 (2026-10-22)

실제 운영 Mac Mini 실행기와 iMac 관리 웹서버(Beta23)에서 확인했다. 웹서버는 연결 직후 `install-result`를 처리하다 `IllegalArgumentException: 인스턴스를 찾을 수 없습니다`로 WebSocket을 종료했다. 스택은 RunnerGateway.handleTextMessage → ServerManager.remoteInstallResult → runtime → instance였다. 실행기에는3일 전 설치 성공 결과 한 건이 남았으나 관리 서버에는 그 ID가 없었다. heartbeat/재연결 때 저장된 결과를 재전송하므로 연결 종료가 반복됐다.

## 운영 복구

정확히 그 결과 파일 한 건을 private quarantine/install-results 폴더로 원자 이동하고 원문 동일·백업 보존을 확인했다. 인스턴스 폴더나 게임 파일은 삭제하지 않았다. 관리 앱/실행기/게임호스트는 같은 PID로 유지했다.

서버의 마지막 해당 오류는22:05:06.543 KST였다.22:15:07까지 새 오류가 없었으며, 실행기는 정상 heartbeat 대기 스택을 보였다.22:08 무렵부터22:15:11까지 같은 외부 TCP 연결과 로컬 게임호스트 연결도 관측했다. 이 유한 관측을 영구 안정성 보장으로 확대하지 않는다. 현재 운영 복구는 큐 파일 분리 효과이며 아래 새 바이너리는 운영에 설치하지 않았다.

## 재발 방지 코드

`remoteInstallResult`에서 없는 인스턴스/런타임을 무시하고 런타임 잠금 획득 뒤에도 같은 객체가 유효한지, 삭제 상태가 아닌지 확인한다. 기존 실행기 ID·설치 ticket·동기화 분기를 유지한다. 잘못된 결과로 현재 설치를 완료시키지 않으며 삭제된 경로를 재생성하지 않는다. 인증/네트워크 설정이나 다른 예외를 일괄 무시하는 방식은 추가하지 않았다.

## 검증

코드·시험 계획 d27abfe 선커밋 후 Java21 전체 test/bootJar 실행:2분23초,686보고=657통과/29조건부 미실행/실패0. 집계 원문은 runner-stale-install-result.json.

- 실제 ServerManager와 Spring ExceptionWebSocketHandlerDecorator에 없는/삭제된 ID 결과를3회 전달하고 동일 세션 heartbeat-ack와 미종료·경로 미재생성을 확인했다.
- 결과가 실제 런타임 잠금에 대기하는 동안 삭제를 강제로 수행해 예외·상태 복원이 없는 것을 확인했다.
- 다른 실행기/만료 ticket은 거부하고 현재 ticket/설치중 상태를 보존하며 정상 결과는 완료되는 기존 파일 동기화 시험을 통과했다.
- 관련 RunnerInstallResult2개, FileSync2개, RunnerGateway10개 모두 통과했다. 위 전체 집계에 포함되며 별도14개를 합산하지 않는다.

새 바이너리의 운영 교체/신규 릴리즈 게시, 운영 Minecraft 재시작은 수행하지 않았다. 수정 브랜치는 fix/runner-stale-install-result이다.
