상태: 모델 확장 구현·관련 테스트61개·UI1개 통과, 실제 벤치마크 진행 중
# feat/local-model-catalog
- origin/main 최신 확인, 게시된 Qwen beta.6 기반 새 작업 브랜치. 단독 작업.
- 기존 14b/30b ID와 기본값 유지, Gemma4 12B/31B 및 Qwen3-Coder 30B 추가.
- Discord/관리 웹/실행기 공통 카탈로그, Coder Thinking 자동 비활성화, 지원 모델 간 단일 상주 전환.
- 기능 테스트와 실제 임시 파일 원문 보존/지정 위치 수정 벤치마크 진행 예정. 전체 테스트와 다른 OS 생략.

- 웹 production build 통과. 기존 UI 시험은 오래된 메뉴·assistant mock을 보완한 후 새3모델 선택/저장·Coder Thinking·31B 메모리 안내·환경 확인/해제 PASS.
- 독립 위험 검토/승인/중지/실행기 전달 관련 회귀61개 PASS. 전체 테스트·다른 OS 생략.
- 실제 시험1에서 일반30B/Gemma12B는 파일 끝 LF 누락, Coder는 표시 줄 번호까지 저장. Coder의 Python 코드는 잘못된 `}{`로 구문 오류. 최초 결과는 보존.
- 읽기 도구에 원래 줄바꿈 형식/마지막 줄바꿈 개수와 표시 줄 번호 경고, 부분 조회 경고 추가. 임시 경로의 /var vs /private/var 비교로 생긴 벤치마크 자체 assert도 realPath로 수정. 합격 기준을 완화하지 않음.
- 최종 하네스로 일반30B·Coder30B·Gemma12B·31B의 동일 중간 삽입+실제 Python 별 출력 시험 재실행 중. 모델별1회라 일반 성능 순위가 아님. Qwen GGUF Q4_K_M / Gemma MLX, 실행 창8192·출력4096, Thinking 지원 모델true/Coderfalse.
