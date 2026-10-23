상태: 구현·기능 검증 완료, v0.7.0-beta.24 게시
# feat/log-blocks
기본 펼침·최대 300줄 구간으로 StackTrace를 하나의 record로 묶고 동일 50ms 시간 구간 안에서 logger별 표시. 실제 Minecraft tick ID가 없으므로 틱 번호라고 표시하지 않는다. 초 단위 타임스탬프에는 1..20을 만들지 않는다. 하위 프레임 timestamp 상속, 원문 순번 유지, 프레임별 Mixin 상세, 최대 2000줄 주변 원문 확장. 원문 전체 가상 스크롤/검색 모드를 별도로 유지한다. 모바일 가로 스크롤, 5초 로그 파일 재탐색 및 최신 크래시 보고서 열기/다운로드 안내.

커밋 후 테스트: 실제 최신 로그 형식, INFO/time 순서, STDERR 프레임/원인/억제/생략 프레임, 출처 보존, 같은 50ms 내 logger 묶기/초 단위, 모바일 overflow, crash 새 파일 알림, Mixin 프레임 연계, 1200줄 crash와 원문 전체 모드. UI 빌드.

Beta24 게시 완료. 관련 기능80통과/조건부1미실행, browser19통과, 전체 suite는 사용자 지시로 생략. 게시자산9개와CI39893980700성공을 메타데이터로 확인했고 게시 후 재다운로드는 하지 않았다. 상세: https://github.com/yoonseo6399/ServerManager/blob/release/0.7.0-beta.24/docs/testing/operations-controls.md
