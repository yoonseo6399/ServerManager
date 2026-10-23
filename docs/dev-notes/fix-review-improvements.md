상태: 완료 — backend42/UI27 통과, production build 통과, 수정 브랜치 푸시
# fix/review-improvements
- 기준 v0.7.0-beta.24. R1~R8 및 B1 수정, A1은 파일 UI·Dialog·디코딩·팩 보관 정책 책임 분리로 반영.
- UI 심층 개선: 서버/폴더/파일 요청 경합, dirty/busy 이탈 보호, 저장 충돌·재인증 복구, 키보드 모달, 서버 설정 초안 보존, 모바일 관리 메뉴·터치 영역.
- 검증: backend42(실패/오류/스킵0), Playwright27(실패0), TS/Vite production build. 실제 장치·운영 API 미사용.
- 세부 변경과 한계: docs/testing/review-improvements.md.
- 릴리즈 노트 후보: 파일 편집의 미저장 보호와 서버 전환 정확성을 개선하고, 오류 복구·모달 접근성·모바일 사용성을 강화했습니다. 권한 설정 손상·텍스트 인코딩·팩 보관·스냅샷 공간 처리를 보완했습니다.
- 운영 설치/재시작/배포 없음. backend 전체 구조 재편과 설정 revision은 후속 범위.
