상태: beta.29 서명 게시 완료, backend78/UI21·패키징·bootstrap 통과, 공개 자산9개 확인
# release/0.7.0-beta.29

- origin/release/0.7.0-beta.28 기반. solo. fix/relay-stream-isolation(d13ffc6) 및 fix/folder-upload-progress(a6b069c) 통합, 충돌 없음.
- 각 병합 후 Kotlin/프런트 빌드. VERSION·한국어 릴리즈 노트·통합 검증 계획을 선커밋한다.

## 통합 검증 계획
- backend: RelayIo/RelayDiagnostics/DirectRelay/DirectRelayProcess/DirectSable/DatagramOutbox/HostedRelay/VoiceChatRelay/VoiceChatHost/SableRelay/GameHost/HostReplacement/RunnerGateway/FileUploads/FileUploadAccess:78개.
- browser: cancellable-uploads/uploads-pack-update 및 console-follow 관련 회귀. TS/Vite·bootJar·runtime-security·runner-setup.
- 최초 일회성 manager-outage 실패는 숨기지 않고 원문과원인 미확정 상태를 보존. 보강한100회 fixture를 통합에서도 수행한다.
- 사용자 지시로 전체 backend suite·다른OS 실기·운영 Minecraft/서비스 재시작·게시 후 재다운로드 해시는 생략한다.
- 기존 서명 secret과 release.yml workflow_dispatch skip_full_tests=true 사용. 단일 CI에서 패키징·격리 bootstrap·서명 확인 후 새 immutable tag/자산9개 게시. stable latest/main·기존 게시 태그/자산 보존.

## 통합 결과
- testedProductCommit30e590a. 관련 backend78/78통과·실패/스킵0(55초). manager-outage100회도 통과.
- 브라우저 업로드12/콘솔9=21/21통과, TS/Vite·각 병합 Kotlin·bootJar 통과. 잘못된 logs config로 실행한 최초 console 명령은 테스트를 찾지 못해 올바른 mods config에서 실행했으며 성공 수에 포함하지 않았다.
- runtime-security11통과·runner-setup 격리 검사 통과. docs/testing/release-0.7.0-beta.29-results.json 보존.
- 원인 미확정 일회성 회귀 실패는 원문 보존하며 재현 여부와 구분한다. 실제 운영 끊김 원인 확정·수정 완료로 표현하지 않는다.
- 단일 workflow_dispatch로 패키징·서명·게시를 진행한다.

## 게시 완료
- CI41434362809 success. immutable tag753b6de637339aafa6408e93f020c2ca65d431cf, 2026-11-02T11:13:24Z(KST20:13:24) 게시.
- 공개 prerelease 자산9개 모두 uploaded/양수 크기 확인. 서명·Mac/Windows 패키지·격리 bootstrap 성공. 공개 자산 다운로드·해시 비교 없음. stable latest v0.6.3/main·기존 태그/자산 유지.
- 운영 Minecraft·실행기·상주 호스트·관리웹을 수동 재시작/업데이트하지 않았다. 관리웹 UI와 GAME_HOST 전송 개선은 각각 해당 구성요소 업데이트 후 적용된다.
