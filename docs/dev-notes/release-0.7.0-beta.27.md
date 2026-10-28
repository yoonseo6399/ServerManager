상태: 음악·파일/팩·메뉴 수정 충돌 없이 통합 · 통합 검증 예정
# release/0.7.0-beta.27

- origin/release/0.7.0-beta.26 기반. solo.
- 범위: fix/music-autoplay-skip, feat/mod-upload-pack-update 및 이를 포함하는 fix/settings-menu-tests.
- 각 병합 뒤 Kotlin·frontend 빌드 확인. 코드와 계획 선커밋 후 음악/팩·파일·권한 관련 통합 기능 테스트 및 14개 UI 테스트 실행. 전체 suite는 기존 사용자 생략 지시 유지.
- Java 보안/runner-setup 범위 검사 및 CI 서명·모든 플랫폼 패키징·isolated bootstrap 검증.
- workflow_dispatch skip_full_tests=true로 CI가 새 immutable v0.7.0-beta.27 태그와 9개 자산 prerelease를 게시하도록 한다. 기존 stable latest/main/게시 태그·자산은 보존.
- 공개 파일 재다운로드/해시 비교, 운영 서비스나 Minecraft 재시작은 하지 않는다.

- 음악 병합 후 compileKotlin/프런트 빌드 성공. 메뉴 수정 브랜치 UI14 통과 후 e9ce9e2 병합(팩/파일 기능 포함). 충돌 없음.
- 새 VERSION·한국어 릴리즈 문서·개발 이력 선커밋 후 backend 관련14클래스(예상92개) 및 UI14개, frontend/bootJar와 런타임 보안/runner-setup 검사 실행.
