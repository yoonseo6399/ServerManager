상태: 완료, 릴리즈 통합 대기
# feat/back-navigation
- 서버·탭·파일 폴더·편집기를 history로 기록하고 모달(서버 만들기, 팩 가져오기, 모드 확인, 시도 로그, 로그 상세)은 뒤로가기로 닫힘
- 미저장 변경은 기존 navigateWithUnsaved 확인창을 거침
- 테스트: frontend/nav-tests/nav.spec.ts 3개 x3회 통과. 기존 save-bar, roles-sync(첫 번째) 테스트는 변경 전에도 30초 시간 초과로 실패(변경과 무관)
- 릴리즈 노트: 웹 화면에서 브라우저 뒤로가기/앞으로가기가 동작합니다
