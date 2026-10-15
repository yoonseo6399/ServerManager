상태: 로그 API 범위 테스트 5개 통과, 통합 대기
# test/system-log-viewer
- 기준 c6e5ef7; 기능 브랜치의 로그 KDoc에서 `/api/admin/**` 중첩 주석 때문에 compileKotlin 실패 발견. 설명 문구를 변경하여 수정.
- SystemLogsTest: 페이지 순서/필터/시간/검색 cursor 진전, 회전/초과 cursor reset, 세션 식별자, 잘못된 입력 거부, 인증/사용자본문 숨김, URL credential/path/query/fragment 제거, 민감 logger 배제, 출력 길이 제한.
- SystemLogsHttpTest: 실제 SecurityConfig + 격리된 AuthStore + MockMvc 요청. 익명 401, 일반 사용자와 관리자 403, 소유자 200 및 Cache-Control no-store, 비밀값 미노출.
- 실행: JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test --tests '*SystemLogsTest' --tests '*SystemLogsHttpTest' --no-daemon
- 결과: BUILD SUCCESSFUL, 총 5개 통과. 초기 KDoc 컴파일 실패 후 수정한 동일 범위 재실행 통과. 전체 회귀/운영 환경 테스트는 미실행; 운영 프로필/게임 수정 없음.
