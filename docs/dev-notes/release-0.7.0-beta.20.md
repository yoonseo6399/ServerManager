상태: Beta20 공개 게시 완료 · CI39700677048 성공 · 기능47개·실제27B3사례 통과
# release/0.7.0-beta.20
- 기준 v0.7.0-beta.19, fix/pi-intent-output-budget ef32b51 통합. solo.
- 기능47개 및 bootJar 통과, 실험 실패/성공·제한은 docs/testing/pi-intent-output-20261022.md 기록.
- skip_full_tests=true, 프런트/JAR/설치/격리 bootstrap/서명/게시 CI. 기존 signing secret만 사용.
- stable/main/latest 보존. 운영 설치/재시작, 게시 후 다운로드·해시 비교 생략.

- CI39699365015 시도1/2는 upstream POM 대신 xl.meta 디렉터리 HTML 때문에 compileClasspath 해석에서 실패. 게시 없음 확인.
- scripts/prepare-youtube-source.py로 공식 GitHub1.18.2 JAR을 고정 SHA256으로 준비. Maven 사용본과 클래스/리소스94개 바이트 동일, ZIP 타임스탬프만 다름. 메타데이터·의존성 그래프 고정, module JAR 체크섬만 공식 컨테이너에 맞춤. 로컬 bootJar와 패키지 내JAR 해시/변조거부 확인.
- 이 검사는 빌드 의존성 검증이며 게시 후 ServerManager 자산 재다운로드·해시 검사는 아님.

- 게시 소스/태그8de431c5b786cec36cabdf82e0e978f9cc6d2b0c. CI39700677048 success; full backend suite skipped. 의존성 준비/프런트/JAR/설치/격리 bootstrap/서명/게시 통과.
- 공개 prerelease/draft=false,2026-10-22T00:41:24Z, 자산9개 메타데이터 확인. stable/latest v0.6.3 보존. 게시 자산 다운로드·해시 비교 없음. 운영 설치/재시작 없음.
- https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.20
