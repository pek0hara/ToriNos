#!/usr/bin/env bash
# PR のプレビューコメントを1件だけ作り、以後は書き換える。GH_TOKEN と GITHUB_REPOSITORY を使う。
#
#   preview-comment.sh <PR番号> <本文>
set -euo pipefail

pr=$1
marker='<!-- pr-preview -->'
body="$marker
$2"

[[ $pr =~ ^[0-9]+$ ]] || { echo "PR番号が不正です: $pr" >&2; exit 1; }

comment_id=$(gh api --paginate "repos/$GITHUB_REPOSITORY/issues/$pr/comments" \
  --jq ".[] | select(.body | startswith(\"$marker\")) | .id" | head -n 1)

if [[ -n $comment_id ]]; then
  gh api -X PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$comment_id" -f body="$body" > /dev/null
else
  gh api -X POST "repos/$GITHUB_REPOSITORY/issues/$pr/comments" -f body="$body" > /dev/null
fi
