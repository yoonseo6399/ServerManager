상태: 통합 브랜치에서 전체 백엔드 661통과/29조건부 미실행·브라우저 12통과, 운영 배포 전

# fix/mod-force-disable

- Beta23 기준 별도 워크트리. 운영 서버의 Sodium 0.8.13이 LWJGL 클래스를 찾지 못해 종료 코드 1로 실패함을 확인했다. 해당 팩은 Sodium·Iris·Iris Flywheel을 server unsupported로 지정하고 저장된 Modrinth 정보도 client_only다. 실제 모드 구성은 아직 변경하지 않았다.
- 기존 강제 활성화 API의 ignoreDependencies를 비활성화에도 지원한다. 일반 거부는 MOD_DEPENDENCY_BLOCKED 코드, 강제 요청은 선택 파일만 .jar.disabled로 변경하고 남은 소비 모드·시작 실패 가능성을 경고한다.
- 개별 카드의 주황색 강제로 끄기, 의존 모드를 포함한 일괄 선택/거부 후 강제 끄기, 명시적 확인 화면을 추가한다. 실행·전송·진단 중 잠금, 권한·revision·경로·파일 충돌·복구 보호는 유지한다.

## 커밋 뒤 실행할 검증

1. ModManagementTest / ModSearchTest / ModArtifactsTest: 일반 거부와 강제 비활성화, 선택 파일만 변경, 원문·월드 보존, 정상 재활성화, 실행·진단·stale·외부경로·이름 충돌 거부.
2. Playwright force-disable/force-enable/mods/view-only: 개별·일괄, 취소·확인 payload, 주황색, 권한/실행 잠금, stale, 기존 켜기 회귀 및 화면 크기.
3. TypeScript/Vite build와 bootJar. 전체 테스트는 사용자 요청에 따라 최종 수정 통합 후 실행한다.

후속 요청: 실시간 콘솔 크래시 누락과 모드 토글 지연 원인은 별도 조사한다. 설치·릴리즈·운영 재시작은 이 커밋에 포함하지 않는다.

검증 결과: `fix/mod-start-diagnostics`의 `docs/testing/mod-start-diagnostics.md` 및 JSON 참조. 전체 Kotlin/Java 테스트 실패 0, bootJar 및 TypeScript/Vite 통과. 관련 브라우저 12개는 원격 API fixture이며 운영 Discord·다른 OS 검증은 수행하지 않았다.
