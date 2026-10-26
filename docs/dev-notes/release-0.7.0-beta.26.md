상태: v0.7.0-beta.26 서명 prerelease 게시 완료, 공개 자산9개 메타데이터 확인
# release/0.7.0-beta.26

- 게시된 beta.25 기반, solo. origin/fix/java-security-runtime과 origin/feat/server-archive-import를 merge --no-ff. 뒤 브랜치는 voicechat/multi-server 작업을 포함한다. 충돌 없음.
- 보안 패치 단독 병합 뒤 Kotlin·프런트 빌드 통과. 나머지 기능 병합 후 새 버전으로 빌드·범위 테스트 예정.
- 2026-10-26 공식 Adoptium Windows JRE/JDK API와21.0.13+9 GitHub 태그에 Windows 신규 파일 없음 확인. Mac 두 아키텍처는21.0.13+9. 공식 Azul Metadata API에 Windows CA JRE21.0.13+9가 있어 package UUID1953be22-1404-4175-8257-0dcb1ac0dffb, SHA256 9532e56bcf2578b4bbb82507c4e38a17c0332c5918ff6c8b25cd2f71abf42cf9 고정 대안을 추가했다. 보안 하한을 낮추지 않는다.
- 새 코드·계획 커밋 후 다음을 실행: 기존 보안8+대체 공급자/무결성3 시험, 실제 공식 Windows ZIP 다운로드·해시·release/구조·라이선스 보존·cacerts harden/check, 통합 기능 backend 선별 클래스와 frontend/관련 browser, runtime 포함 패키징/isolated bootstrap.
- Windows 실행·전체 suite·운영 재시작·공개 자산 재다운로드/해시 비교는 하지 않는다. 사용자 지시에 따라 workflow_dispatch skip_full_tests=true, 기존 서명 secret 사용. GitHub CI가 새 태그를 만들고 새 자산9개를 immutable prerelease로 게시한다. stable latest와 기존 main을 보존한다(기존 베타 운영 방식).

## 게시 전 실제 결과

- 통합 backend20클래스122통과/0실패/0스킵, 전체 suite 미실행. Kotlin/bootJar와 TS/Vite 성공. 관련 browser11통과/0실패. Java 보안/공급자/무결성11통과.
- 실제257MiB ZIP33청크 및 대역 게임 JVM2개 동시 음성40패킷·한쪽 종료 후 다른 PID/TCP/음성 유지 증거 재확인. 원시 결과는 ignored build/test-results와 docs/testing/release-0.7.0-beta.26-results.json.
- Windows 공식 Azul ZIP49,364,100바이트의 고정 SHA256 확인. release21.0.13/구조/bin/java.exe/라이선스 확인. 호스트 Mac JVM으로 실제 Windows cacerts의 영향받는 인증서2개 제거, 재검사0개 확인. Windows 실행 아님.
- 첫 Windows 사전 검사에서 루트 LICENSE를 가정해 실패했으나 실제 legal/java.base/LICENSE 배치를 확인해 검사 경로를 보완했다. 제품 패키저는 전체 런타임을 복사하므로 파일 누락 수정은 필요 없었다. 실패도 기록한다.
- CI에 runtime-security focused 검사도 추가해 공급자/무결성/인증서 회귀를 패키징 전에 확인한다. 설치된 서비스·게임·OS 메모리 설정을 변경하지 않았다.

## 게시 완료

- CI [40247054797](https://github.com/yoonseo6399/ServerManager/actions/runs/40247054797) 성공. Mac 설치 PKG·Mac/Windows CLI/UI 실행기 ZIP4개·JAR·서명 업데이트 manifest·signature·SHA256SUMS 합계9개 게시.
- [v0.7.0-beta.26](https://github.com/yoonseo6399/ServerManager/releases/tag/v0.7.0-beta.26), 2026-10-26 09:02:37 KST. tag commit924dca894639500f43ef09f96a471e46f08cb588는 CI 빌드 커밋과 일치한다. 기존 태그·자산을 교체하지 않았다.
- GitHub API 메타데이터로 prerelease=true/draft=false·자산 이름·크기 모두 양수 확인. 전체 suite step은 skipped이고 보안·runner setup·bootstrap·서명·게시 step은 success 확인. stable latest는v0.6.3 유지, main 미변경.
- 공개 자산 재다운로드/해시 대조는 사용자 지시대로 실행하지 않았다. 운영 설치·재시작 없음. 이 후속 커밋은 게시 결과 문서만 추가하며 이미 게시된 태그나 바이너리를 변경하지 않는다.
