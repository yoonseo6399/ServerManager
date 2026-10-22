상태: 기능47개·실제27B 검토3사례 통과 · 외부 Maven 응답 장애2회 확인 · 공식 동일 클래스 배포본 해시 고정으로 복구 중
# release/0.7.0-beta.20
- 기준 v0.7.0-beta.19, fix/pi-intent-output-budget ef32b51 통합. solo.
- 기능47개 및 bootJar 통과, 실험 실패/성공·제한은 docs/testing/pi-intent-output-20261022.md 기록.
- skip_full_tests=true, 프런트/JAR/설치/격리 bootstrap/서명/게시 CI. 기존 signing secret만 사용.
- stable/main/latest 보존. 운영 설치/재시작, 게시 후 다운로드·해시 비교 생략.

- CI39699365015 시도1/2는 upstream POM 대신 xl.meta 디렉터리 HTML 때문에 compileClasspath 해석에서 실패. 게시 없음 확인.
- scripts/prepare-youtube-source.py로 공식 GitHub1.18.2 JAR을 고정 SHA256으로 준비. Maven 사용본과 클래스/리소스94개 바이트 동일, ZIP 타임스탬프만 다름. 메타데이터·의존성 그래프 고정, module JAR 체크섬만 공식 컨테이너에 맞춤. 로컬 bootJar와 패키지 내JAR 해시/변조거부 확인.
- 이 검사는 빌드 의존성 검증이며 게시 후 ServerManager 자산 재다운로드·해시 검사는 아님.
