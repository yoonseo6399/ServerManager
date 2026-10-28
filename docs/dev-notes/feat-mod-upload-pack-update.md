상태: 구현 및 회귀 테스트 작성 중 · 코드와 테스트 계획 선커밋 후 범위 검증 예정 · 미배포
# feat/mod-upload-pack-update

- 기준: 게시된 v0.7.0-beta.26. 단독 작업, 별도 워크트리. 앞선 음악 수정은 별도 브랜치 그대로 보존.
- 모드 탭: JAR 드래그·선택 업로드, 휴지통 아이콘 삭제, Shift 클릭 시 삭제 확인 생략. 실행기 원본 API를 사용. 파일 탭은 기존 드래그 업로드와 이동 유지, 공통 하단 floating progress bar를 추가.
- 공통 업로드: 실제 XHR 업로드 바이트 진행률, 다중 파일 성공/실패 개수, 한 파일 실패 후 남은 파일 계속 전송.
- 기존 인스턴스 mrpack 업데이트: 별도 중앙 모달, 비동기 사전 검증, 모드 추가/덮어쓰기/삭제 목록, merge/replace 선택, config 덮어쓰기와 클라이언트 모드 제외 체크박스. 월드·데이터·server.properties는 허용 경로에 포함하지 않음. 관리 보조 Sable bridge 보존. 같은 Minecraft·로더 종류만 지원하며 로더 버전은 교체하지 않음.
- 적용은 서버 정지·전송 완료 후만 가능. 모든 영향 파일 사전 백업, revision 검증, 실패 시 복구. 중단 시 import.pending으로 시작 차단; 원본은 pack-updates/<token>/backup, 요청·대상 목록은 update.json에 보관. 원격은 중앙 복사본이 아닌 실제 RunnerWorkspace에 반영.
- 신규 mrpack의 클라이언트 제외 UI는 이미 존재하므로 중복 생성하지 않음. 미확인 파일 때문에 모든 제외를 취소하던 결함 수정. pack 선언/Modrinth/Fabric environment=client 근거로 제외하며 알려진 필수 의존성은 유지, 미확인 모드는 유지.
- 폴더 ZIP: 월드 등 폴더와 서버 루트 다운로드. 압축 작업과 파일/바이트 진행률을 하단에 표시하고 준비되면 브라우저 다운로드 시작. HTTP 전송 진행 자체는 브라우저가 표시(메모리에 ZIP 전체를 모으지 않음). 32 GiB·100,000항목 제한, 서버 실행 중 일관성이 필요하면 정지 권장.
- 원격 원본과 최근 중앙 복사본 선택 가능. 중앙 복사본은 실행기 오프라인이어도 읽기만 허용하며 복사 완료 시각 기록(기존 복사본 미기록 표시). symlink와 경로 탈출 차단.
- VIEWER는 조회/다운로드/ZIP 준비 가능. 업로드·삭제·팩 적용은 ADMIN만 가능. ZIP 준비 POST도 CSRF 필요.

## 구현 커밋 이후 실행할 테스트

- Backend: PackUpdatesTest (로컬 merge, 실제 RunnerWorkspace replace, 월드·데이터 바이트 보존, config 선택, 클라이언트 제외, stale preview/실행 중 거절, 백업·실패 복구), ImportAndMetricsTest, FolderDownloadsTest (ZIP 원문·빈 폴더·한글·링크 제외·오프라인 중앙 복사본), FileTransferAccessTest (VIEWER 허용/관리 작업 거절/CSRF/다른 서버 격리), RunnerWorkspaceTest 및 관련 모드 회귀.
- React TypeScript/Vite 빌드 후 Playwright: 모드 drag/drop·삭제·Shift, 파일 탭 floating bar, 중앙 업데이트 모달의 옵션·변경 목록·payload, VIEWER 다운로드/최근 복사본 옵션. 데스크톱·모바일 레이아웃 확인.
- 전체 테스트, 운영 데이터 변경, 게임/봇 재시작, 공개 릴리즈는 하지 않음.
