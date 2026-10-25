상태: 통합 완료, 게시 전 범위 검증·서명 패키징 준비
# release/0.7.0-beta.26

- 게시된 beta.25 기반, solo. origin/fix/java-security-runtime과 origin/feat/server-archive-import를 merge --no-ff. 뒤 브랜치는 voicechat/multi-server 작업을 포함한다. 충돌 없음.
- 보안 패치 단독 병합 뒤 Kotlin·프런트 빌드 통과. 나머지 기능 병합 후 새 버전으로 빌드·범위 테스트 예정.
- 2026-10-26 공식 Adoptium Windows JRE/JDK API와21.0.13+9 GitHub 태그에 Windows 신규 파일 없음 확인. Mac 두 아키텍처는21.0.13+9. 공식 Azul Metadata API에 Windows CA JRE21.0.13+9가 있어 package UUID1953be22-1404-4175-8257-0dcb1ac0dffb, SHA256 9532e56bcf2578b4bbb82507c4e38a17c0332c5918ff6c8b25cd2f71abf42cf9 고정 대안을 추가했다. 보안 하한을 낮추지 않는다.
- 새 코드·계획 커밋 후 다음을 실행: 기존 보안8+대체 공급자/무결성3 시험, 실제 공식 Windows ZIP 다운로드·해시·release/구조·라이선스 보존·cacerts harden/check, 통합 기능 backend 선별 클래스와 frontend/관련 browser, runtime 포함 패키징/isolated bootstrap.
- Windows 실행·전체 suite·운영 재시작·공개 자산 재다운로드/해시 비교는 하지 않는다. 사용자 지시에 따라 workflow_dispatch skip_full_tests=true, 기존 서명 secret 사용. GitHub CI가 새 태그를 만들고 새 자산9개를 immutable prerelease로 게시한다. stable latest와 기존 main을 보존한다(기존 베타 운영 방식).
