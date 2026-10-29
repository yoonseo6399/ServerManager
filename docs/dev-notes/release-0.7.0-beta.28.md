상태: 두 브랜치 통합·병합 빌드 성공, 통합 기능 검증 예정
# release/0.7.0-beta.28

- 기준 origin/release/0.7.0-beta.27. solo.
- 통합: fix/console-follow-scroll (656e28c), feat/cancellable-file-uploads (bd90c5a). 병합 충돌 없음.
- 각 병합 후 compileKotlin과 TS/Vite 빌드 확인. VERSION·한국어 릴리즈 문서·테스트 계획 선커밋 후 검증.
- 새 공통 진행 창의 조회 API를 기존 로그 UI fixture 두 개에도 추가한다. 실제 서버 API 계약에 맞춘 테스트 응답 추가이며 제품 동작 변경이 아님.

## 검증 계획
- backend: FileUploadsTest/FileUploadAccessTest/DeferredWorkspaceEditsTest/RunnerWorkspaceTest/LiveRunnerOperationsTest/RemoteConsoleTest (관련 28개).
- browser: console-follow/live-crash/cancellable-uploads/uploads-pack-update (관련 18개).
- TS/Vite, bootJar, runtime-security, runner-setup. 기존 사용자 지시에 따라 전체 suite 생략.
- CI workflow_dispatch skip_full_tests=true로 고정 의존성·서명·Mac/Windows 패키징·격리 bootstrap 검사 후 새 immutable 베타 태그와 9개 자산 게시.
- 기존 stable latest/main/이미 게시된 태그·자산 보존. 공개 자산 재다운로드/해시 비교 및 운영 서비스/Minecraft 재시작은 하지 않는다.

## 통합 검증 완료
- testedProductCommit 7a39c99. backend **28/28 통과**, 실패/스킵 0. compileKotlin/bootJar 통과.
- 로그/업로드/취소/새로고침/모바일 및 기존 팩·ZIP 회귀 **UI18/18 통과**(46.6초). TS/Vite 빌드 통과.
- runtime-security **11/11 통과**, runner-setup URL/CLI/자동 시작 선택/잠금/단계 복사/rollback/데이터 보존/링크 거부/등록/원본 폴더 검사 통과.
- docs/testing/release-0.7.0-beta.28-results.json에 집계 보존. 전체 suite·운영 서비스 재시작·공개 파일 재다운로드/해시 검사는 미실행.
- 기존 서명 secret을 이용하는 CI 수동 빌드 1개로 서명·패키징·새 태그 생성·게시 진행.
