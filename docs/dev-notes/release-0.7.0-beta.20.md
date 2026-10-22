상태: 기능47개·실제27B 검토3사례 통과 · Beta20 서명 CI 준비
# release/0.7.0-beta.20
- 기준 v0.7.0-beta.19, fix/pi-intent-output-budget ef32b51 통합. solo.
- 기능47개 및 bootJar 통과, 실험 실패/성공·제한은 docs/testing/pi-intent-output-20261022.md 기록.
- skip_full_tests=true, 프런트/JAR/설치/격리 bootstrap/서명/게시 CI. 기존 signing secret만 사용.
- stable/main/latest 보존. 운영 설치/재시작, 게시 후 다운로드·해시 비교 생략.
