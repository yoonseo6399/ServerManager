상태: beta.27 통합 중 · 음악 수정 병합, 메뉴 테스트·파일/팩 기능 검증 후 병합 예정
# release/0.7.0-beta.27

- origin/release/0.7.0-beta.26 기반. solo.
- 범위: fix/music-autoplay-skip, feat/mod-upload-pack-update 및 이를 포함하는 fix/settings-menu-tests.
- 각 병합 뒤 Kotlin·frontend 빌드 확인. 코드와 계획 선커밋 후 음악/팩·파일·권한 관련 통합 기능 테스트 및 14개 UI 테스트 실행. 전체 suite는 기존 사용자 생략 지시 유지.
- Java 보안/runner-setup 범위 검사 및 CI 서명·모든 플랫폼 패키징·isolated bootstrap 검증.
- workflow_dispatch skip_full_tests=true로 CI가 새 immutable v0.7.0-beta.27 태그와 9개 자산 prerelease를 게시하도록 한다. 기존 stable latest/main/게시 태그·자산은 보존.
- 공개 파일 재다운로드/해시 비교, 운영 서비스나 Minecraft 재시작은 하지 않는다.
