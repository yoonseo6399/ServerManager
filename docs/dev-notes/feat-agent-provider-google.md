상태: 구현·범위 검증 완료, 통합 및 실제 제공자/Google 연결 검증 대기
# feat/agent-provider-google

- 기준: origin/main 2bf9368에서 생성, origin/release/0.5.1 ad2bc9c까지 fast-forward하여 기존 실행기 Qwen 기능 보존.
- 메인 설정에 OPENAI/GEMINI, 모델, pool:* / key:<id> / project:<project> credentialRef 추가. 기존 JSON 기본값 OpenAI 유지. 키 선택은 활성/실제 비밀 값 존재를 확인하며 프로젝트·모델별 기존 Gemini 할당량 장부를 공유한다.
- GeminiMainAdapter는 기존 coordinator Responses envelope를 Gemini contents/functionDeclarations로 변환한다. 병렬 함수 응답을 같은 user parts에 모으며 제공자 call ID와 thoughtSignature를 원형 보존하고 UI에는 노출하지 않는다. Gemini 추론 강도는 모델 기본값으로 명시한다.
- DiscordCodeAgent call-site는 Qwen 담당이 settings.ready(config), settings.provider(config,notify)로 연결한다. 본 브랜치는 해당 공유 파일을 수정하지 않는다.
- GoogleReadonlyService는 Spring singleton. 서버에서만 AES-GCM 토큰 저장; 사용자별 SHA256 이름 분리, 읽기 범위만, 앱 clientId 변경 시 이전 연결 무효, 토큰/메일 본문 로그 없음. 읽기 고정 Google URL만 제공하며 임의 URL/HTTP method 입력 없음.
- OAuth: 신선한 패스키 인증으로 시작, 세션·사용자·clientId·5분 state·PKCE 검증, 실패 포함 1회 소비. SameSite=Strict를 유지하기 위해 public callback은 토큰 교환 없는 HTML landing이고 보호된 same-origin callback-complete로 이동한다. Security.kt에는 정확히 /api/google/callback만 공개했다.
- 연결 해제는 로컬 토큰을 먼저 삭제한다. 외부 revoke 실패는 remoteRevoked=false로 알리고 Google 계정의 타사 앱 권한 수동 해제를 안내한다.
- UI GoogleConnectionSettings (props 없음)는 내 계정 메뉴에 삽입. 소유자 앱 설정, 개인 연결/해제, 최대20개 Gmail 검색·텍스트 본문 조회, 향후30일 primary 캘린더 표시. HTML 본문은 렌더하지 않는다. 조회 내용은 모델/클라우드에 자동 전송하지 않는다.
- Google service broker API: connected(user), search(user,query), message(user,id), events(user,from,until). Discord는 AssistantIdentity.resolveDiscord의 검증된 웹 계정만 전달해야 한다. 링크 없는 사용자 추정 금지. Qwen 담당에게 계약 전달 완료.

## 검증
- ./gradlew compileKotlin 성공.
- ./gradlew test --tests '*MainProviderGoogleTest' --tests '*GeminiKeyPoolTest' --tests '*MainAgentCoordinatorTest' -x sableBridgeBuild 성공 (25개). 이후 병렬 함수ID/그룹 회귀 추가 후 MainProviderGoogleTest 재실행.
- npm ci 및 npm run build --prefix frontend 성공. 신규 컴포넌트는 TypeScript 검사를 받으며 메뉴 삽입은 UI 담당 브랜치에서 수행.
- 테스트: 구 설정 이전, 선택키 준비/공유 쿼터, Gemini 도구왕복/서명/중단 응답, Google 개인 격리·암호화·정해진 GET·로컬 해제 실패 경로·clientId 변경·경로 거부·OAuth 세션 분리/state 치환/재사용·landing 이스케이프.
- 실제 Gemini/OpenAI 유료 호출·Google OAuth 계정·실제 Gmail/캘린더 조회는 미실행. 사용자 앱 등록 및 Google의 scope/배포 검증은 필요하다.
- 운영 Minecraft·계정·DNS 변경 없음. 릴리즈 게시 없음.

## 참고한 공식 문서
- https://developers.google.com/identity/protocols/oauth2/web-server
- https://developers.google.com/identity/protocols/oauth2/scopes
- https://ai.google.dev/gemini-api/docs/generate-content/function-calling
- https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures
