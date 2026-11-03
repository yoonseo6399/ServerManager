상태: 서명 게시 성공·로컬 설치본 runner/GAME_HOST31 및 Survival 시작·상태 응답 확인
# release/0.7.0-beta.31

- origin/release/0.7.0-beta.30 기반, fix/relay-large-bursts 통합(solo). 추가 사용자 요청으로 이미 게시된 beta30을 유지하고 새 beta31에 여유 있는 전송 한도를 제공한다.
- 선커밋 통합 검증 계획: 중계 기능70개·Kotlin/bootJar·TS/Vite·보안11·runner setup. CI 단일 workflow_dispatch skip_full_tests=true, packaging/isolated bootstrap/signature/신규 immutable tag/자산9개.
- 서버 정상 종료→서명 업데이트 staging→상주 호스트31 교체→기존 설정 그대로 게임 시작→supervisor 관리 복귀·중계/로그 확인. 사용자의 기존 재시작 승인은 유지하며 한 번만 적용한다.
- 전체 suite·다른OS 실기·별도 게시 자산 재다운로드 해시 테스트는 생략. 설치에 필요한 기존 서명/무결성 검사 자체는 유지한다. stable latest/main/기존 태그·자산 보존.

- eb4f02d 제품 커밋 통합70/70통과(63초), 실패/스킵0. Kotlin/bootJar·TS/Vite·보안11·runner setup 통과. 서명 게시 후 승인된 운영 재시작을 진행한다.

- CI41582055122 success. 태그 a06cd7ca59299a596ad451f29d7cca1369eee60f, 공개 자산9개 uploaded/양수 크기, prerelease=true, stable latest v0.6.3 확인. 공개 자산 재다운로드 해시 시험 없음. 설치 업데이터의 기존 서명/해시 검증은 유지.
- 운영 적용: 기존 Minecraft는14:03 정상 저장·종료 상태였다. 업데이터로31 staging 후 기존 supervisor가 정상 활성화했다. 임시 유지보수 제어에서 첫 호스트가 로컬 빌드 경로로 뜬 실수를 발견해 게임을 정상 save/stop하고 설치된 서명본/내장 Java로 정정했다. 종료 인계 완료 후 남은 EXIT_UNACKED는 인증된 ack-exit API로 정확한 이전 PID2645만 확인했다. 임시 activate 보조 함수는 구형 bootstrap에 메서드가 없어 실패했지만 이미31로 활성화되어 추가 파일 수정은 하지 않았다.
- 최종 표준 bootstrap3015/runner3017, GAME_HOST2777, Minecraft2963. runner/host31 서명 설치 경로와 내장 Java, 단일 game PID 유지·WSS/암호화6400 터널 재연결·6700/25567 listen 확인. 실제 Minecraft status 요청에1.21.1/protocol767/online0 응답. 플레이어 로그인·차원 이동은 미실행이며 외부망 장애 전체 해결을 주장하지 않는다. 임의 월드/모드 수정·강제 종료는 하지 않았다.
