상태: Beta19 공개 게시 완료 · CI 성공 · 관련25개 테스트 통과
# release/0.7.0-beta.19
- 기준 v0.7.0-beta.18, 병합 fix/pi-bash-unification 1f455f4. solo.
- 기능 브랜치 실제Pi SDK25개 테스트와 bootJar 통과. 릴리즈 CI는 full suite 생략, 프런트/JAR/설치/부트스트랩/서명/게시.
- 기존 signing secret 사용, stable/main/latest0.6.3 보존. 운영 설치/재시작 없음. 게시 후 자산 재다운로드·해시 비교 생략.

- 태그/게시 소스 9d2373c137b51946f9c8db401192cbeb74d4addb. CI39692990060 success, full suite skipped; 프런트/JAR/설치/격리 bootstrap/서명/게시 통과. 공개 prerelease, draft=false, 자산9개 메타데이터 확인. 게시 후 파일 다운로드·해시 비교 없음. 운영 설치/재시작 없음.
- https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.19
