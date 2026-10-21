상태: Beta18 게시 완료 · CI 성공 · 자산9개 메타데이터 확인 · 재다운로드/해시 검사 생략
# release/0.7.0-beta.18

- Beta17 태그 기준 fix/pi-approval-audit 742f494를 no-ff 통합(기능 커밋9d3bbf0 포함). 안정판 main/latest는 기존0.6.3 보존.
- 기능60개, 실제27B 파일 원문 보존 및 별도 실제 위험 검토+Pi 차단, 웹1개와 frontend build 통과. 모델 전부 해제, wired20480 보존. 운영 서비스 미재시작/미설치.
- 사용자 요청대로 전체 테스트는 workflow_dispatch skip_full_tests=true로 생략. 최종 릴리즈 커밋 [skip ci]로 태그 자동 full suite 중복 방지.
- 게시 후 다운로드/해시 대조는 하지 않는다. CI 패키징/서명/게시와 GitHub 메타데이터만 확인한다.
- 자세한 요청별 변경·안전 한계·운영 미실행 항목은 릴리즈 노트/개인 도구 안내/기능 노트 및 raw 벤치마크 자료 참조.

## 게시 결과
- 태그 v0.7.0-beta.18, 릴리즈 커밋 d8b01f82c6bb49a485bb9d119f503e9e35f6905f.
- CI39648514436 성공: https://github.com/yoonseo6399/ServerManager/actions/runs/39648514436
- Full backend suite=skipped, 프런트/JAR/실행기 설정/관리자·실행기 패키징/분리 bootstrap/서명·게시 단계 성공.
- 공개 prerelease, 자산9개 uploaded, 게시2026-10-21T18:03:22Z. https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.18
- latest v0.6.3 유지. 기존 태그·자산과 main은 변경하지 않음. 사용자 요청에 따라 릴리즈 재다운로드/해시 검증 미실행.
- 로컬 테스트용 Vite만 종료. 운영 앱/실행기/게임호스트 미설치·미재시작. Ollama 모델 미로딩 및 wired20480 확인.
