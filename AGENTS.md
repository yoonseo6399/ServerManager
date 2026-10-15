# 에이전트 작업 규칙

이 저장소에서 작업하는 모든 에이전트는 시작 전에 아래 문서를 읽는다.

- [docs/WORKFLOW.md](docs/WORKFLOW.md): 기능·수정은 항상 새 브랜치(워크트리)에서 하고, 릴리즈 때 `release/<버전>` 통합 브랜치에서 합치는 절차
- [scripts/RELEASING.md](scripts/RELEASING.md): 릴리즈 체크리스트와 서명·게시 규칙
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): 개발 이력과 진행 중인 결정 사항

작업 현황은 `scripts/agent-status.sh`로 본다(읽기 전용: 브랜치별 진행, main 대비 앞/뒤, 겹치는 수정 파일). 진행 상황은 자기 브랜치의 `docs/dev-notes/<브랜치 이름의 /를 -로 바꾼 것>.md` 첫 줄 `상태: …`에 적는다. `main`에 공유 상태 파일을 두지 않는다.

핵심만 요약하면 다음과 같다.

1. `main`에 직접 커밋하지 않는다.
2. 작업 브랜치에서는 `VERSION`, `docs/releases/*`, `docs/DEVELOPMENT.md`를 수정하지 않는다.
3. 게시된 버전의 파일은 덮어쓰지 않는다.
4. 다른 에이전트의 미커밋 변경은 건드리지 않는다.
