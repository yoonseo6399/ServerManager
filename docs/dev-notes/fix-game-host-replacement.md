# fix: GAME_HOST 교체가 WAITING에 머무는 문제 · 업데이트 대기 표시

상태: 구현·컴파일 확인 완료. **회귀 테스트(`HostReplacementTest`)는 작성만 했고 실행하지 않았다.** 아래 수동 확인이 남아 있다.

## 원인 (최신 코드 재확인 결과)
1. `RunnerMain.confirmActivation()`은 호스트 버전이 다르면 `GAME_HOST` WAITING(`HOST_REPLACED_WHEN_IDLE`)만 기록했다.
2. 실제 교체 경로는 시작 시 `preflight.reason=="HOST_WILL_BE_REPLACED"` 분기뿐이었는데, `HostLifecycle.preflight()`는 그 값을 반환하는 곳이 없어 **도달할 수 없는 코드**였다. 실행 중 재시도 경로도 없었다.
3. 설령 호출해도 `replaceIfIdle()`은 제어 계층이 이미 붙은 호스트에 두 번째 연결을 열어 `CONTROLLER_ACTIVE`로 거부됐다 (컨트롤러는 1개만 허용).
4. 호스트의 `prepare-replacement`는 `relayRole!=null`이면 `RELAY_SERVICE_ACTIVE`로 거부했는데, 제어 계층이 매 관리 세션마다 `relay-session`을 보내 역할을 만들기 때문에 **중계를 쓰지 않아도 영구 보류**였다.

## 변경
- 호스트(`GameHost.kt`): `prepare-replacement`는 게임 유무 확인과 차단 플래그 설정을 게임 시작과 같은 락 안에서 한 번에 수행한다. 이후 신규 게임 시작·중계 제어 메시지는 `HOST_REPLACEMENT_PENDING`으로 거부하고, 중계 허브는 **신규 플레이어 수신만 닫는다**(`pauseGame`). 기존 연결은 끊지 않고 스스로 끝나기를 기다린다(`state=DRAINING`, 연결 수 보고). 연결 0 + 게임 0 + 신규 차단 상태일 때만 `READY`. `cancel-replacement`와 120초 임대 만료가 차단을 풀고 허브 수신을 되살린다 (제어 계층이 사라져도 영구 차단되지 않음). 환영 메시지에 `pid`, `caps=["replace-drain"]` 추가.
- 제어 계층(`GameHostLink.replaceIdleHost`): 현재 연결을 그대로 써서 진행한다. 인증된 호스트 PID와 endpoint PID 일치, 기록된 시작 시각 일치(구 endpoint만 명령행 보조), 게임 없음을 두 번 확인한 뒤에만 정상 종료(`destroy`)한다. endpoint는 방금 종료한 PID일 때만 지운다.
- 실행기(`RunnerMain.tryReplaceHost`): 연결 루프에서 3초마다 시도(단일 실행). 서버 실행·준비·설치·전송·종료 중·파일 인계 대기를 모두 확인하고, 통과하면 `lifecycle` 락 안에서 `hostReplacing`을 올려 신규 시작·설치·전송·워크스페이스 작업을 `RetryTransfer`로 거부한다. 교체 후 현재 활성 JAR로 새 호스트를 띄우고 버전을 확인한 뒤 `DONE`, 릴레이 세션을 다시 알린다. 연결 수가 줄지 않으면 90초 뒤 교체를 취소하고 1분 뒤 재시도한다. 새 호스트 시작이 3회 연속 실패하면 exit 3으로 기존 `RunnerBootstrap` 롤백(이전 버전·이전 호스트)을 요청한다.
- 죽은 분기 `HOST_WILL_BE_REPLACED` 제거.
- 업데이트 대기 표시: `ComponentUpdate.waitDetail`(예: "플레이어 연결 3개가 끝나면 자동으로 적용됩니다") 추가, 서버 화면의 `ServerUpdateNotice` 배너와 실행기 상세의 한글 대기 사유.

## 알려진 한계
- **이전 호스트(0.6.0 등 `replace-drain` 미지원)**는 중계 역할이 한 번이라도 만들어졌으면 `RELAY_SERVICE_ACTIVE`로 계속 거부한다. 제어 계층이 안전하게 배출을 보장할 수 없어 일부러 우회하지 않는다. 이 호스트는 **게임·접속이 없을 때 한 번** 운영자가 정상 종료 후 실행기를 재시작해야 하며, 그 뒤부터는 자동 교체된다. (대기 사유에 이 안내가 표시된다.)
- 데스크톱 관리 앱의 로컬 호스트(`HostedLocalGamesService`)는 이번 변경 범위가 아니다.
- 교체 중 제어 계층이 죽으면 호스트가 없는 상태가 되지만, 다음 시작 때 `HostedGames.connection()`이 새 호스트를 띄운다. 게임은 이미 없는 조건에서만 진행한다.

## 수동 확인 절차 (운영 Minecraft를 시작·중지하지 않는 시험 환경에서)
1. 임시 `SERVER_MANAGER_RUNNER_HOME`에 0.6.0 계열 실행기(구 호스트)로 호스트를 띄우고, 새 버전으로 제어 계층만 업데이트한다. `/api/runners/{id}/update-status`에 `GAME_HOST WAITING` + 사유가 보이는지 확인.
2. (신 호스트 기준) 게임 없이 직접 중계 허브를 켜고 플레이어 소켓 1개를 연결 → 대기 사유가 `RELAY_STREAMS_ACTIVE`, 상세에 연결 수가 표시되고 신규 접속은 거부되는지 확인. 소켓을 닫으면 자동으로 교체되고 `DONE`이 되는지 확인.
3. 교체 직후 관리자 화면에서 중계가 준비 완료로 돌아오고 새 플레이어가 접속되는지, TCP 6399 설정·월드·계정이 그대로인지 확인.
4. 교체 대기 중 서버 시작을 눌러 `상주 서비스를 교체하는 중입니다` 거부 후 재시도가 성공하는지 확인.
5. 실행기를 교체 도중 강제로 끈 뒤 재시작해 상태가 `WAITING`으로 일관되게 복구되는지, 호스트 로그(`host.log`)에 중복 교체가 없는지 확인.
6. 서버 화면 상단에 "업데이트 대기 중" 배너가 뜨고 대기가 풀리면 사라지는지 브라우저에서 확인 (프런트엔드는 이 환경에서 빌드하지 못함).
