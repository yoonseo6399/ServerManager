상태: 통합70/보안11·빌드·설치 통과, 서명 게시 CI 예정
# release/0.7.0-beta.31

- origin/release/0.7.0-beta.30 기반, fix/relay-large-bursts 통합(solo). 추가 사용자 요청으로 이미 게시된 beta30을 유지하고 새 beta31에 여유 있는 전송 한도를 제공한다.
- 선커밋 통합 검증 계획: 중계 기능70개·Kotlin/bootJar·TS/Vite·보안11·runner setup. CI 단일 workflow_dispatch skip_full_tests=true, packaging/isolated bootstrap/signature/신규 immutable tag/자산9개.
- 서버 정상 종료→서명 업데이트 staging→상주 호스트31 교체→기존 설정 그대로 게임 시작→supervisor 관리 복귀·중계/로그 확인. 사용자의 기존 재시작 승인은 유지하며 한 번만 적용한다.
- 전체 suite·다른OS 실기·별도 게시 자산 재다운로드 해시 테스트는 생략. 설치에 필요한 기존 서명/무결성 검사 자체는 유지한다. stable latest/main/기존 태그·자산 보존.

- eb4f02d 제품 커밋 통합70/70통과(63초), 실패/스킵0. Kotlin/bootJar·TS/Vite·보안11·runner setup 통과. 서명 게시 후 승인된 운영 재시작을 진행한다.
