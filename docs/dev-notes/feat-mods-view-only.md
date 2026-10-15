상태: 완료, 릴리즈 통합 대기 (feat/back-navigation 위에 쌓임)
# feat/mods-view-only
- 서버 실행 중에도 모드 목록을 볼 수 있고 수정(켜기/끄기/검사/링크)은 막힘, 확인 창 위에 안내 문구 표시
- 모드 탭을 열면 목록을 자동으로 불러오고(버튼 제거), 서버가 정지되면 한 번 다시 불러옴
- 백엔드: ServerManager.modsViewOnly, ModManagement.refresh가 읽기 전용 스캔(viewJob) 사용. 파일 예약 없이 스캔하므로 편집 경로의 기존 검사는 그대로
- 테스트: mods-ui-tests/view-only.spec.ts, mods.spec.ts 통과, Kotlin 컴파일 및 ModSearchTest 통과. 실행 중 읽기 전용 스캔 자체의 Kotlin 테스트는 없음(이 PC에서 ModManagementTest가 Java 경로 문제로 실패하여 추가 못 함)
- 릴리즈 노트: 서버 실행 중에도 모드를 볼 수 있고 목록이 자동으로 불러와집니다
