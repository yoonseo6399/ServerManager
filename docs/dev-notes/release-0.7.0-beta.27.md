상태: v0.7.0-beta.27 서명 게시 완료 · CI 성공 · 공개 자산9개 메타데이터 확인
# release/0.7.0-beta.27

- origin/release/0.7.0-beta.26 기반. solo.
- 범위: fix/music-autoplay-skip, feat/mod-upload-pack-update 및 이를 포함하는 fix/settings-menu-tests.
- 각 병합 뒤 Kotlin·frontend 빌드 확인. 코드와 계획 선커밋 후 음악/팩·파일·권한 관련 통합 기능 테스트 및 14개 UI 테스트 실행. 전체 suite는 기존 사용자 생략 지시 유지.
- Java 보안/runner-setup 범위 검사 및 CI 서명·모든 플랫폼 패키징·isolated bootstrap 검증.
- workflow_dispatch skip_full_tests=true로 CI가 새 immutable v0.7.0-beta.27 태그와 9개 자산 prerelease를 게시하도록 한다. 기존 stable latest/main/게시 태그·자산은 보존.
- 공개 파일 재다운로드/해시 비교, 운영 서비스나 Minecraft 재시작은 하지 않는다.

- 음악 병합 후 compileKotlin/프런트 빌드 성공. 메뉴 수정 브랜치 UI14 통과 후 e9ce9e2 병합(팩/파일 기능 포함). 충돌 없음.
- 새 VERSION·한국어 릴리즈 문서·개발 이력 선커밋 후 backend 관련14클래스(예상92개) 및 UI14개, frontend/bootJar와 런타임 보안/runner-setup 검사 실행.

## 통합 검증 결과

- testedProductCommit d815c4006b375b93cf6dc281706131f984c58f57. backend 관련14클래스 **92 통과/0실패/0스킵**, Kotlin/bootJar 성공.
- roles-sync 3개(기존 실패2 해결) + 파일/팩/ZIP5 + 강제 끄기4 + 켜기2, **UI14 통과**(18.6초). 프런트 TS/Vite 빌드 성공.
- runtime-security **11 통과**, runner-setup URL/CLI/시작 선택/잠금/rollback/데이터 보존/경로/등록 검사 성공.
- 첫 통합 명령에서 --tests를 bootJar 뒤에 놓아 Gradle 옵션 해석 실패. 명령 순서만 수정한 후 모든 지정 테스트 통과. 제품 수정 없이 해결.
- 전체 suite·운영 설치/재시작·실제 외부 Modrinth/Discord/게임·다른 OS 실행·공개 파일 재다운로드는 미실행.
- docs/testing/release-0.7.0-beta.27-results.json에 기계 판독 결과 보존. CI 서명/패키징/bootstrap/게시 결과는 완료 후 별도 기록.

## 게시 완료

- CI https://github.com/yoonseo6399/ServerManager/actions/runs/40787998555: success. Mac 관리 앱·Mac/Windows CLI/UI 실행기 패키징, isolated bootstrap, 서명, 새 태그·공개 게시 모두 성공. 전체 suite는 계획대로 skip.
- 태그 v0.7.0-beta.27 = 604eef92430dcd7e19c394cd2d02c8aaf04bb00b (불변). 게시 시각 2026-10-28T23:35:27Z.
- https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.27 공개 자산9개가 정확한 이름·0보다 큰 크기·uploaded 상태임을 GitHub API로 확인. 태그 커밋은 CI head와 일치. 안정판 latest v0.6.3 유지.
- 게시 파일 재다운로드/해시 비교는 수행하지 않음. 운영 앱·실행기·게임은 설치/재시작하지 않음. 이 완료 기록은 태그 이후 별도 문서 커밋이며 게시 태그·파일은 바꾸지 않는다.
