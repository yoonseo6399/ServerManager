상태: Beta13 게시 완료, 공개 9개 자산 해시·다운로드 JAR·고정 공개키 서명 검증 완료
# fix/mod-inventory-force-enable
- 게시된 Beta12 기반 단독 작업. Modrinth 부분 조회 복구·원본 파일 목록 조기 표시·조회 재시도 UI·의존성 강제 활성화·모드팩 보수적 제외 처리.
- 사유 옆 주황색 버튼과 확인 단계. override는 선택한 비활성 모드의 graph 검사만 무시. 권한·실행중·revision·충돌·백업/복구 유지.
- 고유 기능 회귀 54개(51통과·3외부 opt-in 생략), 별도 동의한 실제 조회 1통과, Playwright4통과, frontend build통과. 전체 테스트/다른OS 생략.
- 운영 파일 수정·재시작 없음. 실제 232개 해시 조회: 224알려짐+8미등록 모두 유지. 최초 장애의 정확한 예외는 미확보로 확정 원인이라 주장하지 않음.
- 상세 검증: docs/testing/beta13-mod-inventory.md. 비공개 원문은 ignored build/mod-fix-evidence/.
- 릴리즈 노트: 모드 일부 정보 조회가 실패해도 전체 목록/가져오기를 유지하고 의존성 차단 옆에서 선택 모드를 강제로 활성화할 수 있습니다.

- 게시: https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.13; 태그/source e1bcd19, 서명 CI39266397843 성공(전체 suite 생략), stable latest v0.6.3 유지. 운영 설치/재시작 명령 미실행.
