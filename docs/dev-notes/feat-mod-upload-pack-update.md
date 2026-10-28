상태: 구현·백엔드 45개/브라우저 12개 통과·프런트 빌드 성공 · 원격 인계 · 미배포
# feat/mod-upload-pack-update

- 기준: 게시된 v0.7.0-beta.26. 단독 작업, 별도 워크트리. 앞선 음악 수정은 별도 브랜치 그대로 보존.
- 모드 탭: JAR 드래그·선택 업로드, 휴지통 아이콘 삭제, Shift 클릭 시 삭제 확인 생략. 실행기 원본 API를 사용. 파일 탭은 기존 드래그 업로드와 이동 유지, 공통 하단 floating progress bar를 추가.
- 공통 업로드: 실제 XHR 업로드 바이트 진행률, 다중 파일 성공/실패 개수, 한 파일 실패 후 남은 파일 계속 전송.
- 기존 인스턴스 mrpack 업데이트: 별도 중앙 모달, 비동기 사전 검증, 모드 추가/덮어쓰기/삭제 목록, merge/replace 선택, config 덮어쓰기와 클라이언트 모드 제외 체크박스. 월드·데이터·server.properties는 허용 경로에 포함하지 않음. 관리 보조 Sable bridge 보존. 같은 Minecraft·로더 종류만 지원하며 로더 버전은 교체하지 않음.
- 적용은 서버 정지·전송 완료 후만 가능. 모든 영향 파일 사전 백업, revision 검증, 실패 시 복구. 중단 시 import.pending으로 시작 차단; 원본은 pack-updates/<token>/backup, 요청·대상 목록은 update.json에 보관. 원격은 중앙 복사본이 아닌 실제 RunnerWorkspace에 반영.
- 신규 mrpack의 클라이언트 제외 UI는 이미 존재하므로 중복 생성하지 않음. 미확인 파일 때문에 모든 제외를 취소하던 결함 수정. pack 선언/Modrinth/Fabric environment=client 근거로 제외하며 알려진 필수 의존성은 유지, 미확인 모드는 유지.
- 폴더 ZIP: 월드 등 폴더와 서버 루트 다운로드. 압축 작업의 파일/바이트 진행률과 ZIP 스트리밍 전송 바이트를 하단에 표시. 브라우저 메모리에 ZIP 전체를 모으지 않으며, 자동 다운로드가 시작되지 않을 때 쓸 링크도 제공. 최종 파일 저장 상태는 브라우저가 담당. 32 GiB·100,000항목 제한, 서버 실행 중 일관성이 필요하면 정지 권장.
- 원격 원본과 최근 중앙 복사본 선택 가능. 중앙 복사본은 실행기 오프라인이어도 읽기만 허용하며 복사 완료 시각 기록(기존 복사본 미기록 표시). 압축 동안 RunnerTransfer의 공유 읽기 잠금으로 다음 delta commit이 섞이지 않게 하며 업로드 청크는 병렬 유지. symlink와 경로 탈출 차단.
- VIEWER는 조회/다운로드/ZIP 준비 가능. 업로드·삭제·팩 적용은 ADMIN만 가능. ZIP 준비 POST도 CSRF 필요.

## 구현 커밋 이후 실행할 테스트

- Backend: PackUpdatesTest (로컬 merge, 실제 RunnerWorkspace replace, 월드·데이터 바이트 보존, config 선택, 클라이언트 제외, stale preview/실행 중 거절, 백업·실패 복구), ImportAndMetricsTest, FolderDownloadsTest (ZIP 원문·빈 폴더·한글·링크 제외·오프라인 중앙 복사본), FileTransferAccessTest (VIEWER 허용/관리 작업 거절/CSRF/다른 서버 격리), RunnerWorkspaceTest 및 관련 모드 회귀.
- React TypeScript/Vite 빌드 후 Playwright: 모드 drag/drop·삭제·Shift, 파일 탭 floating bar, 중앙 업데이트 모달의 옵션·변경 목록·payload, VIEWER 다운로드/최근 복사본 옵션. 데스크톱·모바일 레이아웃 확인.
- 전체 테스트, 운영 데이터 변경, 게임/봇 재시작, 공개 릴리즈는 하지 않음.

## 최종 검증 (2026-10-29)

- 최초 구현·테스트 계획 `42f8a08`을 먼저 커밋한 뒤 검증. 이후 수정과 추가 테스트도 선커밋 후 실행.
- Java 21.0.13, `./gradlew test`에 아래 7개 클래스만 지정: **45 통과 / 실패 0 / 오류 0 / 미실행 0**. Kotlin 컴파일과 테스트 의존 bootJar/Sable bridge 빌드 성공.
  - PackUpdatesTest 5: 실제 로컬/RunnerWorkspace 적용, 월드·데이터·properties 원문 보존, config 보존/신규 생성·덮어쓰기, bridge 보존, 클라이언트 제외·포함 선택, stale/실행 중 차단, 실패 복구, 연속 미리보기 9회와 잘못된 새 업로드 후 기존 계획 차단.
  - ImportAndMetricsTest 12, FolderDownloadsTest 3(실제 ZIP 원문·빈 디렉터리·한글·링크 제외·원격 청크·오프라인 복사본·전송 바이트), FileTransferAccessTest 1(뷰어 async HTTP 파일 응답/CSRF/다른 서버·변경 작업 거절).
  - RunnerWorkspaceTest 3, ModManagementTest 8, RunnerTransferTest 13(중앙 commit과 ZIP 읽기 잠금 동시성 포함).
- `npm run build --prefix frontend`: TypeScript·Vite 프로덕션 빌드 성공.
- Playwright: uploads-pack-update 5, force-disable 4, force-enable 2, roles-sync의 sync-switch 1 → **12 통과**. 실제 루프백 HTTP 첨부 응답으로 브라우저 파일 저장 완료 확인. 데스크톱·390×844 모바일에서 모달과 floating bar 전체 표시 검사 및 스크린샷 확인.
- 기존 roles-sync의 다른 2개 테스트는 옛 메뉴 이름(`서버 실행기` 등) 때문에 실패. **변경 전 origin/release/0.7.0-beta.26 워크트리에서도 두 실패를 똑같이 재현**했으며 이번 범위에서 변경하지 않음.
- 검증 중 수정: 원격 fixture가 view 복사본 대신 실제 저장된 인스턴스를 할당하도록 수정. 업데이트 미리보기가 신규 import 슬롯을 소모하지 않도록 보관 경로 분리. 업데이트 완료 후 모드 inventory 갱신. 전송 실패를 성공 아이콘으로 표시하지 않음.
- 백그라운드 보호 조건을 줄이려던 명령은 자동 승인 검토에서 데이터 손상 위험으로 거부되어 실행되지 않음. 원래의 backgroundPending/정지 검사 유지. 업데이트는 전송 완료 후 가능.
- 전체 테스트, 실제 외부 Modrinth 다운로드, 운영 월드 업데이트/다운로드, 다른 OS 검증, 운영 서비스 재시작 및 공개 릴리즈는 미실행.

## 사용 위치와 제한

- 모드 탭: 파일 드래그 또는 `모드 업로드`; 카드 휴지통 아이콘(Shift로 확인 생략); `mrpack으로 서버 업데이트`.
- 파일 탭: 기존 드래그 업로드에 하단 진행 바 적용. 폴더/현재 폴더 ZIP 버튼, 원격 인스턴스에는 `복사본` 버튼도 제공. 서버 데이터 수정 없이 뷰어도 다운로드 가능.
- mrpack 적용은 서버 정지·파일 전송 완료 후. Minecraft와 로더 종류가 같은 팩만 가능하며 로더 버전 자체는 바꾸지 않음. config 덮어쓰기 기본 OFF, 클라이언트 제외 기본 ON. merge 시 파일명이 다른 구버전은 유지됨을 모달에 명시.
- ZIP은 압축 전 최대 32 GiB/100,000항목. 임시 ZIP은 1시간 후 정리(전송 중 제외). 업데이트 원본 백업은 자동 삭제하지 않음. 사용자 게임 파일을 유지하는 기능 테스트만 수행함.
