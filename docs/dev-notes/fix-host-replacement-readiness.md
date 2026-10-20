상태: Beta15 게시·서명 검증 및 로컬 실행기 적용 완료, 호스트 자체 교체는 게임 종료 대기
# fix/host-replacement-readiness
- Beta14 기반, 단독 구현. 전체 테스트와 다른 OS 실행은 생략.
- 실기 로그: 13:35:09Z 구버전13 재접속으로 실패, 다음 시도30초 뒤 실패 직후 새14 호스트 시작. 현재14 호스트와 제어 연결은 정상인데 상태는 FAILED13으로 고착.
- launchctl submit은 keepalive 작업. PID만 종료하면 구버전이 다시 실행됨. 교체 후 service job 제거·정확한 새 프로세스 준비 확인·늦은 복구 상태 정합성 검증 예정.
- 운영 비밀값/원문 로그는 커밋하지 않음. 임시 home의 실제 launchd 작업으로 기능 검증하고 반드시 정리.

- 수정 전 임시 실제 launchd 유휴 교체 테스트 실패(expected113 / actual0)로 keepalive job 잔존 재현. 수정 후 관련27개 통과(실제 launchd 포함, skip0). 정상 새 PID/버전/포트 준비를 최대30초 기다리며 구버전·이전 PID·죽은 endpoint를 성공으로 세지 않음.
- 로컬 실제 호스트14 및 제어 연결은 이미 정상이나 상태13 FAILED 고착 확인. 현재 새 게임이 시작되어 수동 restart는 실행 전 점검에서 중단했고 운영 프로세스는 건드리지 않음. 새 서명 업데이트를 정상 적용 경로로 전달 예정.

- Beta15 source5c9f6b8, CI39434598162 SUCCESS, 공개9assets digest/실제JAR·manifest·pinnedEd25519·내장버전/수정클래스 확인. stable/latest0.6.3 유지.
- 제품 RunnerUpdater.install로 새서명자산을 재검증·예약, 제어계층이 기존gate로 정상자동적용. 로컬 RUNNER_CONTROL15 DONE, 호스트14는 GAME_RUNNING WAITING(target15,errornull). 게임1개와resident PID·start identity 유지 및 설치JAR해시 일치 확인. 실제 운영 host15교체는 게임종료·인계이후이며 아직 미실행. docs/testing/beta15-release-verification.json.
