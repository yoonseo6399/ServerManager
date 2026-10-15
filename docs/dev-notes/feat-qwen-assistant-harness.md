상태: macOS 파일 비서·소유자 승인·Discord 연결·Google 비공개 요약 구현; 28개 범위 테스트 통과, UI 연결 검증 진행 중
# feat/qwen-assistant-harness

기준: origin/main 2bf9368 → origin/release/0.5.1 ad2bc9c fast-forward. 제공자 의존성 d8b615a를 272a93d로 cherry-pick. VERSION·릴리즈 문서는 작업 변경하지 않음.

## 구현
- `/api/admin/assistant`: 소유자 전용 등록 폴더/명령 허용 목록, 작업 준비, 명시적 모델 전송 동의, 메인 계획 → Qwen 초안 → 메인 리뷰 → 최종 승인·취소·기록 삭제.
- 파일 원본은 승인 전 변경하지 않음. 기존 UTF-8 파일 최대 5개×48KB. 메인 모델이 작은 관련 구간을 선택하고 Qwen은 정확히 한 번 일치하는 구문만 교체. 원본 전체의 SHA256 충돌을 검사.
- macOS 파일 도구는 고정 JNA 5.17.0을 통해 Darwin openat/NOFOLLOW/디렉터리 FD, fstat64 일반파일·하드링크 검사, 완성된 비공개 사본 + 원자적 renameatx_np(SWAP) 사용. 실제 교체된 원본 inode·SHA 검사, 원래 POSIX mode 유지. 파일별 교체는 원자적이나 여러 파일의 실패 복구는 충돌 보존 최선 노력. 복구 파일 `.assistant-backup-*` 보관. Linux/Windows 파일 도구는 접근부터 명시적 차단. 다른 OS 실행기 Qwen 추론은 기존 동작 유지.
- 모델/권한/파일/명령/바이너리 해시를 작업에 고정. 10분 승인 유효기간, 일회용 nonce, 요청자, 상태·원본·사본 변경 검사. 두 번 초안 한도, 추론·리뷰 실패는 반영 차단, 10분 작업 기한. 취소·폴더 철회·계정 권한 재검사.
- 명령은 기본 금지. 소유자가 등록한 절대 실행 파일만 제안 가능. 쉘/인터프리터/관리자/주요 네트워크 도구는 거부. 명령/argv/임시 cwd를 리뷰·최종 승인하고 바이너리 SHA 검사, 환경 변수 비움, 30초·64KB 출력 제한. 명령은 **OS 샌드박스가 아니며 호스트 사용자 파일/네트워크에 접근할 수 있음**을 UI/임베드로 알림. 명령 생성 파일은 자동 반영하지 않음.
- Aider는 설치·검증되지 않아 비활성. 기본 구조화 편집 엔진의 명시적 선택을 제공하며 Aider 통합 완료로 표시하지 않음.
- `/assistant-link`, `/assistant`, `/assistant-google`: Discord 최고 관리자 역할을 새로 조회하고 웹 소유자 일회용 코드로 연결. 역할만으로 소유자 추정하지 않음. 코드 5분·한 번, 계정 epoch/연결 해제/앱 재시작 시 무효. 비공개 임베드+전체 검토 첨부, 승인/거절/새로고침. 타 Discord 요청자 승인 거부.
- Google 요약: 인증된 본인의 서버 broker, Gmail 최대3개 제목/발신자/날짜/미리보기 또는 일정 최대10개만 Qwen 요약. 사용자가 검색 조건·기간 및 Qwen 전송 동의 제공. 메인 클라우드 미전송, 모델 도구·토큰 미제공. 요약 모드에는 파일/명령 도구 없음.
- QwenRequests 공통 세마포어가 일반 대화·파일 초안·Google 요약 사이의 추론 1개 제한을 공유. 기존 실행기 고정 선택 및 14B/30B 전환 언로드 유지.
- AssistantPanel.tsx는 UI 에이전트가 소유자 AI 메뉴에 연결. 정책 변경 SaveBar/미저장 이동 확인 포함.

## 검증
- `./gradlew test --tests local.servermanager.AssistantHarnessTest --tests local.servermanager.AssistantAccessTest --tests local.servermanager.LocalLlmTest --offline`: macOS arm64 Java21에서 28개 모두 통과. 실제 Darwin native 임시 파일 read/write/원자적 교체, 권한0600, 하드링크·심볼릭링크·부모 교체·경로탈출 거부, 원본/임시 변경 충돌, 중복·타인·만료 승인, 리뷰 장애·잘못된 모델 출력 2회 제한, 취소/권한철회, 바이너리 교체, 임시 사본 삭제 포함.
- 실제 Spring Security 필터 체인으로 익명401/일반403/소유자200, CSRF403, 재인증428, 계정 epoch 만료401 검증.
- TypeScript 빌드 검사 통과. UI 연결 이후 브라우저 범위 검증 예정.
- 127.0.0.1:11434/api/version 읽기 전용 검사 연결 거부. 실제 Qwen14B·30B 추론 품질/속도/메모리 비교는 미실행. 실 Google OAuth/GPT/Gemini/Discord 토큰 사용·메시지 발송 미실행.
- 다른 OS 검증은 사용자의 최신 요청대로 실행하지 않음. 운영 앱·Minecraft·공유기·DNS 변경 없음.

## 인계
UI 담당이 AssistantPanel을 AI 소유자 메뉴에 추가. Claude 담당 RunnerMain/Gateway/설치기/서비스 수명 코드는 건드리지 않음. DiscordBot에는 새 명령/버튼 전달과 선택 의존성만 추가. 다른 제공자 변경은 provider-agent 의존 커밋으로 분리.
브랜치 예약 푸시는 자동 승인 검토가 이 특정 소스 전송의 명시적 승인을 요구하며 두 번 거절. 같은 작업의 우회 전송은 하지 않음; 부모가 최종 검토 후 공유 처리.
