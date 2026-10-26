상태: v0.7.0-beta.26 통합·게시 완료, 운영 자동 적용 없음
# feat/server-archive-import

기반: origin/feat/multi-server-ports eb8b49b (Simple Voice Chat·같은 실행기 여러 서버 포트 분리 포함). 원격 fetch 및 현황 확인 후 별도 워크트리에서 혼자 구현한다. VERSION·main·기존 릴리즈·운영 서버는 변경하지 않는다.

## 구현

- mrpack 옆에 **서버 ZIP 가져오기**: 바깥 서버 폴더 인식 → 종류/버전/JAR/Java/메모리/포트 확인 → 새 인스턴스로 가져오기. ZIP만 지원한다.
- ZIP 최대20GiB/해제100GiB/항목10만. 일반 multipart256MiB와 분리된8MiB 바이너리 청크, 위치 검증·오류 후 이어 올리기·업로드 취소·24시간 정리·보관4개·디스크 여유 검사.
- 소유자/전체관리자만 새 서버 생성 가능. 업로드는 사용자에 귀속하며 CSRF·세션·기존 권한 적용. 미리보기·상태 응답 no-store.
- Zip 경로 이탈/중복(case·Unicode)/파일-폴더 충돌/CRC/실제 해제량 검사. ZIP의 링크 속성을 파일시스템 링크로 만들지 않는다. 자동 스크립트·설치기·서버 실행 없음.
- 기존 월드·설정·비활성 모드 보존. 별도 staging 검증 후 새 인스턴스 폴더로 원자적 이동. 기존 서버를 덮어쓰지 않는다.
- 서버 포트/loopback/Query/RCON 설정과 신뢰하는 런타임 메타데이터를 새 설정에 맞춘다. 기존 eula.txt는 보존, 없으면 기존 새 서버 생성 정책을 유지한다.
- 실행 파일이 갖춰지면 STOPPED, 없으면 NEW로 표시하고 공식 런처 설치를 안내한다. 실패는 IMPORT_FAILED·시작 차단. 실행기 선택 후 기존 검증된 전송 경로를 사용한다.

## 커밋 후 테스트 계획

1. ServerArchiveFormatTest: 단일 바깥 폴더·NeoForge/Forge/Fabric/Paper 인식, 알 수 없는 버전 추정 금지, 원문 보존, 경로 이탈·Unicode/case 중복·경로 충돌·다중 서버·과도한 해제량·큰 제어 파일·CRC 손상 거부.
2. ServerArchiveImporterTest: 새 서버만 생성, 월드·비공개/비활성 모드·알 수 없는 설정 보존, 명시적 시작 전 프로세스 없음. 직접 만든 작은 Java 서버 JAR를 가져온 뒤 명시적 start/stop 실제 실행. EULA false 보존·런처 없는 서버·실패 격리·업로드 소유자·위치·청크 한도·취소·만료·잘못된 설정 재시도.
3. 실제257MiB ZIP을8MiB씩 업로드해 기존 multipart256MiB보다 큰 파일의 저장·미리보기 확인(20GiB·100GiB 실용량 시험은 아님).
4. ServerArchiveAccessTest: 실제 Spring Security+MockMvc에서 owner/admin/member/미로그인·다른 업로드 소유자·CSRF·바이너리 청크 경로 검증.
5. 기존 ServerManagerTest, MultiServerPortsTest, ImportAndMetricsTest, LaunchersTest, CsrfRefreshTest 등 관련 회귀만 실행. 전체 테스트는 생략.
6. 프런트 TypeScript/Vite 빌드, Playwright server-archive: 청크 원문/오프셋·이어 올리기·삭제·미인식 수동입력·모바일·생성 권한·가져오기 요청. 기존 multi-server-ports/settings도 범위 회귀. 스크린샷으로 데스크톱/모바일 확인.

## 제외

RAR/7z/tar.gz, Nextcloud 계정 연동·파일 읽기, 운영 설치/재시작·실제 월드 업로드/인터넷 대용량·공유기·다른OS·전체 테스트·릴리즈 게시.


## 실행 결과 (2026-10-25)

- 구현·계획 먼저 `e2c920e` 커밋, 접근성 `e2deaed`, UTF-8/만료 처리 `4bfc910`, 완료 후 임시 정리 `891730a` 커밋 후 검증했다.
- 최종 backend 관련8클래스 **46통과 / 0실패 / 0제외**. `./gradlew test`는 열거한 클래스만 선택했고 전체 suite는 실행하지 않았다. bootJar 성공.
- 실물 ZIP `269484322`바이트(257MiB+)를 `8388608`바이트 한도로33청크에 저장하고 미리보기 확인. multipart256MiB 설정은 변경하지 않았다. 20GiB/100GiB 실용량 시험은 아니다.
- 월드·비활성 비공개 모드·기존 다른 서버·알 수 없는 properties 값·한글 MOTD·false EULA 보존, 수동 start 전 프로세스 없음, 가져온 작은 독립 Java 서버 JAR의 실제 명시적 시작·정상 종료 확인.
- frontend TS/Vite 성공. ZIP+다중 서버+기존 설정 합계10개 통과. 이후 UTF-8/만료 변경 커밋에서 ZIP4개 다시 통과. 데스크톱·390x844 모바일 스크린샷을 직접 확인했다(동일 테스트 중복 합산하지 않음).
- 초기 테스트 실패도 보존: stop(force) 테스트 인자 누락으로 테스트 컴파일 실패 → false 명시. select의 암묵적 label에 옵션 문자열이 섞여 browser2개 실패 → aria-label 보완. HTTP 테스트가 refresh 전 BeanFactory를 접근해1개 실패 → 기존 프로젝트와 같은 BeanFactoryPostProcessor로 등록 후 실제 Security 검사 통과. 실패를 삭제하거나 skip하지 않았다.
- 원시 테스트 산출물은 ignored build/test-results와 frontend/test-results에 있고 집계·업로드 증거는 docs/testing/server-archive-import-results.json에 보존했다.
- 운영 Nextcloud/관리웹·실행기·Minecraft에는 접근·설치·재시작하지 않았다. 별도 릴리즈 요청 없이 기능 브랜치만 푸시한다. 후속 릴리즈는 기반 voicechat/multi-server 변경과 별도 Java 보안 패치 통합 여부를 함께 확인해야 한다.

## 릴리즈 노트 후보

mrpack 옆에 서버 ZIP 가져오기를 추가했습니다. 대용량 분할 업로드·진행률·이어 올리기·취소와 서버 폴더/종류 확인을 지원하며, 월드·모드를 새 서버로 가져온 뒤 직접 시작할 수 있습니다. ZIP 경로·용량·무결성·업로드 소유자 권한을 검사합니다.
