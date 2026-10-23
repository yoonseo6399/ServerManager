# 리뷰 후 개선 및 UI 흐름 검증

기준: 게시된 `v0.7.0-beta.24` (`d35bd4d`). 작업: `fix/review-improvements`.
리뷰 항목의 수정은 다음과 같다. 운영 데이터·서비스 설치/재시작·릴리즈 게시는 수행하지 않았다.

| 보고서 | 구현 | 검증 |
|---|---|---|
| R1 잘못된 서버 파일 목록 | `FileBrowser`를 서버별 수명으로 분리. 폴더/파일 조회 세대와 현재 경로를 비교하고 오래된 응답 폐기. 목록 로딩 중 작업 차단. 드래그에 서버 ID 포함 | 서버/폴더 역순 응답, 읽는 중 폴더 이동, 현재 서버별 endpoint |
| R2 설정 손상 시 권한 허용 | 파일 없음과 읽기/파싱 실패 구분. 손상 파일 보존, 최고관리자 포함 차단, 정상 백업 복구/재시작 안내. 손상 상태에서 설정 변경도 차단 | grants/ceiling JSON 손상, 잘못된 enum, 디렉터리를 파일로 읽는 오류, 정상 최초 실행 |
| R3 초안 유실 | 편집기 dirty/busy를 공통 이탈 보호에 등록. 저장 중 편집·이동 차단, 409/저장 확인 실패에도 초안 유지 | 닫기/서버/탭/폴더/뒤로가기 취소, 폐기 확인 1회, 저장 성공/실패/지연 |
| R4 문자 손실 | 공통 `EditableText.decode`의 strict UTF-8 decoder를 로컬·원격 경로에 적용 | UTF-8/한글/BOM/CRLF 바이트 왕복, EUC-KR·잘못된 UTF-8·NUL 거부, UI 원본 다운로드 안내 |
| R5 오래된 상태·빈 목록 혼동 | 서버 조회와 메모리/주소 조회 실패 분리. 마지막 성공 시각과 장애 표시·재시도. 파일 loading/error/empty 분리 | 메모리 실패·서버 조회 실패·파일 조회 재시도·지연 목록 |
| R6 모달 접근성 | 공통 native Dialog, Tab/Shift+Tab 순환, 배경 inert, Escape와 포커스 복귀, busy 뒤로가기 보호 | 키보드 순환·Escape 복귀·busy Escape/Back |
| R7 팩 업로드 누적 | 시작/매시간 24시간 만료 자료 수거, 활성 작업 제외, UUID 디렉터리만 처리, 링크 미추적. 미리보기 최대 8개 및 업로드 여유 공간 검사 | 만료/활성/신규/무관 디렉터리, symlink, quota |
| R8 재인증 복구 | 서버 삭제 428에서 패스키 확인 후 캡처한 원래 서버에 한 번 재시도. 작업 중 중복 클릭·이동 보호 | WebAuthn fixture 성공/취소, 동일 대상 요청 횟수 |
| A1 구조 부채 | `main.tsx`의 파일 I/O·화면·선택 상태를 별도 `FileBrowser`로 분리. Dialog/텍스트 디코딩/팩 보관 정책 공통화 | 흐름 회귀 테스트와 TypeScript/Kotlin 컴파일 |
| B1 스냅샷 공간 | CoW 실패/미지원 시 잔여 stage 제거 뒤 원본 전체 크기+128MiB 여유 재검사 | sparse 600MiB/여유 650MiB fixture 거부·원본 보존·stage 정리, 기존 스냅샷 테스트 |

## UI 심층 검토에서 추가 개선

- 새 파일은 원격 읽기를 하지 않고 초안을 생성하며 빈 파일도 저장할 수 있다.
- 저장 뒤 다시 읽기가 실패하거나 내용이 다르면 저장 확인 실패로 표시하고 초안을 유지한다. 성공 여부를 추측해 dirty를 해제하지 않는다.
- 서버 설정 초안을 작성하는 동안 주기 조회가 들어와도 입력을 덮어쓰지 않는다.
- 서버 삭제 화면 바로가기, 신규 서버/팩 열기, 실행기 설정 이동에도 미저장 보호를 적용했다.
- 서버 생성은 native form 검증으로 포트·메모리 범위/정수/필수 이름을 확인한다. 처리 중 입력·중복 제출을 차단한다.
- 파일 작업 아이콘에 대상 파일을 포함한 접근성 이름을 제공하고 터치 영역/긴 파일명 줄바꿈을 개선했다.
- 모바일 관리 메뉴는 접기/펼치기를 제공해 작업 화면을 밀어내는 높이를 줄였다. 390px에서 편집 화면 가로 넘침을 확인했다.
- 부분 업로드 실패 시 완료 개수와 실패 이유를 보여준다.
- 브라우저 이력은 기존의 다음 task에서 기록하는 병합 방식을 유지한다. 서버 간 Back 검증은 이력 기록 완료를 확인한 뒤 실행한다.

## 검증 및 재현

백엔드: `./gradlew --offline test --tests local.servermanager.ReviewRegressionTest --tests local.servermanager.PermissionServiceTest --tests local.servermanager.LiveRunnerOperationsTest --tests local.servermanager.ServerManagerTest --no-daemon`

- 총 42개, 실패/오류/스킵 0. 테스트 전용 프로필을 사용한다.
- TypeScript 검사와 Vite production build 통과.
- UI: 로컬 Vite 5187 포트에서 `node node_modules/@playwright/test/cli.js test -c playwright.review.config.ts` 실행. `frontend/tests/review.spec.ts`에서 `/api/**`를 모의 처리하며 운영 API에 연결하지 않는다.
- UI 최종 27개 모두 통과했다. 스크린샷은 ignored `frontend/test-results/review-desktop.png`, `review-mobile.png`에 생성하고 직접 확인했다.
- 최초 검증 중 모달 Shift+Tab 이탈을 발견해 수정했다. 모의 로그 응답 형식 및 비동기 이력 기록을 기다리지 않던 테스트도 보완했다. 최초 실행 실패를 최종 통과로 숨기지 않는다.

## 남는 범위와 운영 복구

- 백엔드 전체 패키지 재편·전역 상태 제거는 수행하지 않았다. 이번 구조 변경은 파일 흐름과 공통 정책의 책임 분리에 한정한다.
- 권한 파일 손상 시 원본을 별도로 보관하고 검증된 백업을 같은 위치에 복원한 뒤 프로세스를 재시작해야 한다. 자동 초기화하지 않는다.
- 미리보기 취소 즉시 삭제 API는 추가하지 않았다. 취소/실패 자료는 24시간 후 수거하며 최대 8개로 제한한다. 활성 import의 전체 다운로드 크기 quota는 별도 정책이다.
- 서버 설정 API의 낙관적 동시성 revision은 기존과 같이 없다. 초안을 보존하지만 다른 관리자의 동시 설정 저장까지 원자적으로 차단하지는 않는다.
- 실제 WebAuthn 장치, 화면낭독기, Windows/APFS 실장비 저디스크, 실 Discord·외부 모델·운영 Minecraft에서 실행하지 않았다. 전체 백엔드 suite는 이번 수정 범위 테스트로 대체했다.
