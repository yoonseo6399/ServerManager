상태: Mac 런타임 보강·전체 백엔드·별도 실행기 JVM·launchd·부트스트랩 검증 완료, 통합 대기
# feat/host-update-validation

기준: origin/main 2bf9368에서 별도 워크트리/브랜치를 만들고 Claude 완료 브랜치 origin/feat/direct-tcp-relay 3e542dc를 fast-forward. 기존 release/0.5.1과 Claude 변경을 보존한다. VERSION·릴리즈 문서는 직접 변경하지 않는다.

## 변경
- DRP1 핸드셰이크의 입력은 읽기 앞당김 없는 DataInputStream을 사용한다. 응답+첫 암호화 프레임이 한 소켓 쓰기에 합쳐져도 바이트가 유실되지 않는다.
- 일시 WSS 오프라인은 영구 권한 철회가 아니다. 지속 등록·서버 할당에 따라 기존 터널 정책을 유지하고 새 티켓은 여전히 현재 연결/권한을 검사한다. 실제 grant 제거 시 정책에서 제외되어 터널을 닫는다.
- 플레이어 소켓을 인계한 후 타임아웃·전송 예외가 나도 finally에서 stream과 소켓을 닫는다. Mac 서버 포트는 SO_REUSEADDR로 정상 종료 뒤 TIME_WAIT 중 재바인딩 가능하다. 포트 충돌 검사는 동일 주소의 실제 리스너로 검증한다.
- Mac 호스트는 nohup 자식 대신 home별 별도 임시 launchd submit job으로 시작한다. 제어 계층 launchd job이 끝나거나 제거되어도 호스트와 가짜 게임은 살아 있다. 영구 LaunchAgent 설치/운영 작업 변경 없음.
- Spring Boot jar:nested 코드 경로에서 실제 외부 JAR를 찾는 공통 resolver로 실행기와 관리 앱의 상주 모드 탐지를 수정했다.
- 호스트 교체는 인증된 prepare-replacement 승인을 먼저 받는다. 실제 게임이 있거나 relay role이 초기화되었거나 응답을 확인하지 못하면 교체를 보류한다. 준비 승인 후에는 새 게임 시작·중계 초기화를 거부한다. 릴레이 유휴 서비스 교체도 보수적으로 보류한다(호환 가능한 제어 계층만 교체 가능).
- 호환 불가·접속 불가 기존 호스트가 살아 있으면 새 실행기/관리 앱은 건강 신호를 내지 않고 실패하여 supervisor rollback 경로로 돌아간다. 실행기 ready 파일은 실제 첫 host 초기화·adoption 성공 뒤 기록한다. 미등록 대기 실행기는 별도 host를 필요로 하지 않는다.
- AppUpdates의 MANAGER_APP에 실제 CHECKING/DOWNLOADING/VERIFYING/WAITING/APPLYING/RECONNECTING/DONE/FAILED와 내구성 로그를 연결했다. /api/admin/updates 응답의 detail은 UpdateDetail이다. 기존 응답 필드는 유지한다.
- 관리 앱 업데이트는 입증된 로컬 HostedProcess 및 호환 host만 실행 상태로 허용한다. remote SERVICE 보고+현재 직접 중계 연결이 필요하며 중앙 제어 계층에 남은 TCP stream, legacy 프로세스, 진단/설치/전송/미완료 handoff는 대기한다. COMPLETE 전송 기록은 작업 중으로 오판하지 않는다. 저장 위치 변경은 기존 완전 유휴 조건 그대로다.
- tests는 bootJar에 의존하여 clean CI에서도 최신 packaged fixture를 실행한다. JAR 미생성 때문에 핵심 런타임 fixture가 조용히 skip되지 않는다.

## 검증 (Mac만, 임시 폴더·루프백·가짜 게임)
- ./gradlew test bootJar 전체 성공: 341개 / 실패0 / skip7. Sable 보조 모드 포함 정상 빌드. 초기 -x sableBridgeBuild 실행의 누락 오류는 최종 검증에서 해소했고 통과로 세지 않았다.
- 이후 실행기 첫 초기화 뒤 건강 신호 기록 수정: RunnerHostIntegrationTest/GameHostSpawnTest/HostLifecycleTest/RunnerUpdateTest 범위 재실행 및 bootJar 성공.
- 마지막 추가: 호환 불가 relay-only host 초기화가 health callback 전에 실패하는 별도 JVM 회귀 및 test->bootJar 의존성은 RunnerHostIntegrationTest/GameHostSpawnTest 범위 4개 성공. 실패 건강 신호 회귀1개와 최종 Mac 호스트 분리2개도 재검증 성공.
- python3 scripts/test-bootstrap.py 성공: 임시 키의 서명 업그레이드, 변조 거부, 시작 실패 rollback, 프로필 선택. 운영 키 사용 없음.
- 실제 분리 JVM 증거: DirectRelayProcessTest가 25.6MB 게임 전송 중 manager control 939바이트만 전송.
- MAC-LAUNCHD-PROOF: host PID62769가 임시 controller job ef7c485a… 종료/제거 뒤 인증 재접속 유지.
- RUNNER-HOST-PROOF: 실제 bootJar launchd host PID63031, 가짜 game PID63032, RunnerAgent JVM PID63034→63039 교체 후 같은 game PID·콘솔 replay/seq·TCP 연결 유지. WSS 전송만 stdin/stdout fixture로 대체했고 실제 유료/공개 서버를 사용하지 않았다.
- fixture는 동일한0.5.1 packaged 클래스의 제어 JVM 재접속이다. 임시99.0.0 staging은 재시작 대기 경로와 버전 불일치 rollback 보고를 검증하며 새로운 실제 버전 binary 업데이트 성공이라고 주장하지 않는다. 서명·rollback 패키지 검증은 별도 bootstrap/UpdateCoordinator/RunnerUpdate 테스트가 담당한다.

## 제약·인계
- 다른 OS 검증은 사용자 지시에 따라 수행하지 않았다. 기존 Windows/Linux 코드는 유지했으며 검증했다고 표시하지 않는다.
- 기존 제품 제약: shared/direct 게임 네트워크는 등록 실행기를 필요로 한다. runnerId 없는 관리 Mac 로컬 게임을 직접 중계 대상으로 광고하지 않는다. 같은 Mac에서도 실행기를 등록하면 해당 경로를 사용한다. 독립 로컬 게임의 관리 앱 업데이트 보호는 별도로 검증했다.
- 실제 외부 컴퓨터·공유기·DNS·인터넷 플레이어 연결은 미검증이다. 실제 Minecraft나 운영 앱/계정/데이터를 조작하지 않았다. 영구 launchd 항목 설치와 릴리즈 게시 없음.
- 통합 담당은 본 브랜치를 release에 merge. MANAGER_APP UI 계약은 settings_ui 담당에게 전달했다. build.gradle.kts의 test dependsOn bootJar 한 줄은 Qwen JNA 의존성 추가와 함께 보존한다.

## 최종 통합 회귀 후 보정
- 통합0.6.0 실행에서 idle-cleanup 테스트만 expected -1 / actual101('e')로 실패했다. 소켓 누수 결함이 아니라 handshake echo 뒤 별도 payload echo가 남을 수 있는 정상 TCP 분할을 테스트가 무시했다. EOF까지 남은 응답을 bounded timeout 안에서 모두 읽고 내용과 양쪽 stream 해제를 확인하도록 수정했다. 시간 제한을500ms로 두어 loaded CI의100ms 의존성도 줄였다. 제품 런타임 변경 없음.
