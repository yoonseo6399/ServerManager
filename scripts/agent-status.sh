#!/bin/bash
# 에이전트 작업 현황판. 아무것도 커밋하거나 쓰지 않고 git 상태에서 계산해 출력한다.
#   scripts/agent-status.sh               원격을 fetch한 뒤 전체 현황 출력
#   scripts/agent-status.sh --no-fetch    fetch 없이 마지막으로 받은 상태 사용
# 환경변수: REMOTE(기본 origin), BASE(기본 $REMOTE/main)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
REMOTE="${REMOTE:-origin}"
BASE="${BASE:-$REMOTE/main}"
[ "${1:-}" = "--no-fetch" ] || git fetch --prune --quiet "$REMOTE"
git rev-parse --verify --quiet "$BASE" >/dev/null || { echo "기준 브랜치 $BASE 를 찾을 수 없습니다" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
branches="$work/branches"
git for-each-ref --format='%(refname:short)' "refs/remotes/$REMOTE/feat/*" "refs/remotes/$REMOTE/fix/*" "refs/remotes/$REMOTE/release/*" | sed "s#^$REMOTE/##" > "$branches"

echo "기준: $BASE ($(git log -1 --format='%h %cr' "$BASE"))  VERSION: $(git show "$BASE:VERSION" 2>/dev/null || echo '?')"
if [ ! -s "$branches" ]; then echo "진행 중인 작업 브랜치가 없습니다 (feat/*, fix/*, release/*)"; exit 0; fi
echo

# 노트 파일 이름: 브랜치 이름의 '/'를 '-'로 바꾼다 (feat/a-b -> docs/dev-notes/feat-a-b.md)
while IFS= read -r b; do
  safe="$(echo "$b" | tr '/' '-')"
  counts="$(git rev-list --left-right --count "$BASE...$REMOTE/$b")"
  behind="${counts%%[[:space:]]*}"; ahead="${counts##*[[:space:]]}"
  if [ "$ahead" = "0" ]; then state="병합됨/변경 없음"; else state="main보다 ${ahead}개 앞"; fi
  [ "$behind" = "0" ] || state="$state, main이 ${behind}개 앞서 있음(rebase 필요)"
  info="$(git log -1 --format='%cr · %an · %s' "$REMOTE/$b")"
  note="$(git show "$REMOTE/$b:docs/dev-notes/$safe.md" 2>/dev/null | grep -m1 '^상태:' || true)"
  echo "● $b"
  echo "    $state"
  echo "    마지막 커밋: $info"
  echo "    ${note:-상태: (노트 없음 — docs/dev-notes/$safe.md 에 '상태: …' 한 줄을 적으세요)}"
  # 이 브랜치가 main 대비 바꾼 파일(노트·생성물 제외)
  git diff --name-only "$BASE...$REMOTE/$b" | grep -v -e '^docs/dev-notes/' -e '^src/main/resources/static/' | sort -u > "$work/files.$safe" || true
  echo "    변경 파일: $(wc -l < "$work/files.$safe" | tr -d ' ')개"
done < "$branches"

# 같은 파일을 둘 이상의 브랜치가 바꾸면 경고한다
echo
echo "겹치는 수정 파일 (병합 충돌 위험)"
found=0
list=($(cat "$branches"))
for ((i=0;i<${#list[@]};i++)); do
  for ((j=i+1;j<${#list[@]};j++)); do
    a="$(echo "${list[$i]}" | tr '/' '-')"; b="$(echo "${list[$j]}" | tr '/' '-')"
    common="$(comm -12 "$work/files.$a" "$work/files.$b" || true)"
    if [ -n "$common" ]; then
      found=1
      echo "  ⚠ ${list[$i]}  ↔  ${list[$j]}"
      echo "$common" | sed 's/^/      /'
    fi
  done
done
[ "$found" = "1" ] || echo "  없음"

echo
echo "이 PC의 워크트리"
git worktree list | sed 's/^/  /'
