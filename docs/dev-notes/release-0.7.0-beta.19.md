상태: Bash 통합25개 기능 테스트 통과 · Beta19 서명 CI 게시 준비
# release/0.7.0-beta.19
- 기준 v0.7.0-beta.18, 병합 fix/pi-bash-unification 1f455f4. solo.
- 기능 브랜치 실제Pi SDK25개 테스트와 bootJar 통과. 릴리즈 CI는 full suite 생략, 프런트/JAR/설치/부트스트랩/서명/게시.
- 기존 signing secret 사용, stable/main/latest0.6.3 보존. 운영 설치/재시작 없음. 게시 후 자산 재다운로드·해시 비교 생략.
