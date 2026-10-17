상태: 구현 완료, 변경 범위 테스트 통과
# fix/qwen-live-output

- Ollama의 실제 Thinking 스트림만 `‘…’`로 표시한다. 일반 답변은 따옴표 없이 같은 Discord 메시지를 갱신한다. 수신한 Thinking이 없으면 사고 내용을 만들어 표시하지 않는다.
- Discord 메시지 편집은 약 750ms 간격으로 합쳐 전송한다. Thinking은 최신 1,500자, 답변 미리보기는 3,500자로 제한한다. 완료 뒤 늦은 스트림이 최종 답변을 덮지 않는다.
- 요청자와 작업 UUID에 묶인 중지 버튼을 추가한다. 로컬 HTTP 스트림·진행 중 작업을 interrupt하고 실행기 요청에는 해당 토큰의 llm-cancel을 전달한다. 이미 완료한 파일 변경을 되돌리는 기능은 아니다.
- 실행기 스트림 프로토콜 OLLAMA_AGENT_V3: 관리 앱과 실행기를 함께 업데이트해야 한다. 구버전 실행기의 일반 채팅 호환성은 유지한다.
- 범위 테스트: QwenStreamingTest, QwenDisplayTest, ProjectAgentStopTest, ProjectAgentTest, ProjectAgentConversationTest, ProjectAgentDiscordTest, LocalLlmTest 및 RunnerGatewayTest.qwen* 통과. HTTP 모의 서버에서 첫 응답 전 점진적 수신·연결 취소·다음 요청 재사용을 확인했다. JDA 모의 메시지에서 Thinking만 인용·같은 메시지 갱신·중지 버튼 제거를 확인했다.
- 전체 테스트·다른 OS·실제 Discord/Ollama 연동은 이번 변경에서 미실행.
