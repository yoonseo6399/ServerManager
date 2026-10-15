상태: 로컬 구현·범위 테스트 완료, 0.4.14 통합 준비; GitHub 쓰기 권한 403으로 push 불가
# feat/local-llm-relay

- `/local-llm` 14B·30B 독립 일반 대화, 최고 관리자 확인·비공개 응답·단일 요청·출력/시간 한도.
- 등록 실행기를 TCP 공개 수신 지점으로 선택. 로컬 opt-in과 인증 세션 capability 필요.
- 경로: 공개 중계 실행기 → 관리 Mac의 기존 호스트 라우팅 → 게임 실행기. UDP·직결 중계는 범위 밖.
- 기본 main 기준으로 작업했으며 기존 뒤로가기는 v0.4.13에 이미 구현됨. 통합 시 v0.4.13 전체를 보존해야 함.
- 프런트 빌드·Kotlin 컴파일·JAR 패키징과 로컬 HTTP/TCP 범위 테스트 통과. 최종 통합에서 전체 검증 예정.
- 실제 Ollama 추론·Discord 메시지 전송·외부 컴퓨터 접속은 미실행.
- 일반 git push는 인증 정보 없음, GitHub connector 브랜치 생성은 403 Resource not accessible by integration.
- 릴리즈 문구: Qwen 일반 대화와 다른 실행기의 TCP 중계 선택을 추가했습니다. 중계는 관리 Mac을 계속 경유합니다.
