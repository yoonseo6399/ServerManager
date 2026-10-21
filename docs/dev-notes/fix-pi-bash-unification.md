상태: Pi 명령 실행 Bash 통합 구현 완료 · 관련 기능25개 통과 · 실제 SDK sh 오류→bash 복구·git 승인 실행 검증 완료 · solo
# fix/pi-bash-unification
- 게시된 Beta18(d8b01f8) 기반. 원격 fetch 후 별도 worktree/브랜치 등록.
- 실제 iMac 08:02 로그: 제한형 sh의 cd 미지원이 최초 거절, 이후 git -C도 요청 내 거부 latch로 차단. 위험 모델의 git 위험 판정이 아님.
- Pi sh 등록 제거, OWNER native bash 통일. READ/EDIT 임의 셸 금지 유지. 제거된 sh의 명시적 입력 오류만 복구 가능; 진짜 권한 거부 latch 유지.
- 정확한 명시 승인 뒤 cd+git status+remote 실행·로그·세션 재개와 도구 목록, 권한 거절·실행 파일 검토 회귀 확인: 실제 Pi SDK 25개 통과, LLM 응답은 결정적 fixture(실제27B 새 호출은 하지 않음). 전체 테스트 생략.
- SSH 시험용 파일은 사용자가 직접 모델에 요청하여 검증할 목적. 실제 시스템 변경 대신 프로젝트 밖 전용 시험 표식 쓰기. 직접 실행/운영 앱 변경 없음. 생성 원문 SHA256 `10778c7944dbe56fe778cad579fcbf7b2eff390cfae379b43cdaa7393aab067a`; ast.parse와 저장 원문 일치·외부 표식 미생성 확인.
- 실제 SDK에서 미등록 sh 호출 오류 후 bash로 수정·승인·git 실행·세션 재개 통과. 추가 검증 첫 실패는 테스트가 시스템 지시문의 EXECUTION_DENIED 설명을 도구 결과로 오인한 assertion 문제였고 tool role만 확인하도록 수정 후25/25 통과.
- 사용자 요청으로 원격 시험 파일을 report_export.py로 변경(원문 동일). 운영 모델 검토 결과는 사용자가 직접 시험 예정.
- 전체 테스트/실제27B 새 호출/운영 Discord 실사용은 미실행. bootJar는 focused test 의존 빌드로 통과. 운영 서비스 재시작 없음.
