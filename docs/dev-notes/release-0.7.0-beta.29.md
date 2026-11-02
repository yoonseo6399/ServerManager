상태: 두 검증 브랜치 병합 완료, 통합 검증 예정
# release/0.7.0-beta.29

- origin/release/0.7.0-beta.28 기반. solo. fix/relay-stream-isolation(d13ffc6) 및 fix/folder-upload-progress(a6b069c) 통합, 충돌 없음.
- 각 병합 후 Kotlin/프런트 빌드. VERSION·한국어 릴리즈 노트·통합 검증 계획을 선커밋한다.

## 통합 검증 계획
- backend: RelayIo/RelayDiagnostics/DirectRelay/DirectRelayProcess/DirectSable/DatagramOutbox/HostedRelay/VoiceChatRelay/VoiceChatHost/SableRelay/GameHost/HostReplacement/RunnerGateway/FileUploads/FileUploadAccess:78개.
- browser: cancellable-uploads/uploads-pack-update 및 console-follow 관련 회귀. TS/Vite·bootJar·runtime-security·runner-setup.
- 최초 일회성 manager-outage 실패는 숨기지 않고 원문과원인 미확정 상태를 보존. 보강한100회 fixture를 통합에서도 수행한다.
- 사용자 지시로 전체 backend suite·다른OS 실기·운영 Minecraft/서비스 재시작·게시 후 재다운로드 해시는 생략한다.
- 기존 서명 secret과 release.yml workflow_dispatch skip_full_tests=true 사용. 단일 CI에서 패키징·격리 bootstrap·서명 확인 후 새 immutable tag/자산9개 게시. stable latest/main·기존 게시 태그/자산 보존.
