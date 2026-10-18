#!/usr/bin/env bash
# Stop 훅: 작업 트리에서 docs/handoff.md 보다 나중에 바뀐 파일이 있으면 종료를 막고 갱신을 요청한다.
# 이미 한 번 막았으면(stop_hook_active) 다시 막지 않는다.
# 일심 Runner 가 이 저장소를 작업할 때(ILSIM_RUNNER)는 단계 범위 밖 파일을 고치게 만들지 않도록 건너뛴다.
[ -n "$ILSIM_RUNNER" ] && exit 0
input=$(cat)
case "$input" in *'"stop_hook_active":true'*|*'"stop_hook_active": true'*) exit 0 ;; esac

repo="$(cd "$(dirname "$0")/../.." && pwd)"
handoff="$repo/docs/handoff.md"
[ -f "$handoff" ] || exit 0

newer=""
while IFS= read -r -d '' entry; do
  path="${entry:3}"
  [ "$path" = "docs/handoff.md" ] && continue
  f="$repo/$path"
  if [ -e "$f" ] && [ "$f" -nt "$handoff" ]; then newer="$path"; break; fi
done < <(git -C "$repo" status --porcelain -z --untracked-files=all 2>/dev/null)

[ -z "$newer" ] && exit 0
printf '{"decision":"block","reason":"작업 트리에 docs/handoff.md 보다 나중에 바뀐 파일이 있습니다(예: %s). 종료 전에 docs/handoff.md 를 이번 세션 내용으로 갱신하세요."}\n' "$newer"
