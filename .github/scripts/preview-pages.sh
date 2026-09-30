#!/usr/bin/env bash
# プレビュー用リポジトリの gh-pages に PR のプレビューを置く、または消す。
#
#   preview-pages.sh <gh-pages の作業ツリー> deploy <PR番号> <公開するディレクトリ>
#   preview-pages.sh <gh-pages の作業ツリー> remove <PR番号>
#
# 別 PR のワークフローが同時に push しうるので、取得 → 変更 → --force-with-lease での push をやり直す。
# wasm でリポジトリが膨らまないよう、gh-pages は毎回1コミットだけの孤立ブランチに作り直す。
set -euo pipefail

repo_dir=$1
action=$2
pr=$3
site_dir=${4:-}

[[ $pr =~ ^[0-9]+$ ]] || { echo "PR番号が不正です: $pr" >&2; exit 1; }
case $action in
  deploy)
    [[ -d $site_dir ]] || { echo "公開するディレクトリがありません: $site_dir" >&2; exit 1; }
    site_dir=$(cd "$site_dir" && pwd)
    ;;
  remove) ;;
  *) echo "不明な操作です: $action" >&2; exit 1 ;;
esac

cd "$repo_dir"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

target="pr-$pr"
for attempt in 1 2 3 4 5; do
  git fetch -q --depth 1 origin gh-pages
  base=$(git rev-parse FETCH_HEAD)
  git checkout -q -f --detach "$base"
  git clean -fdq

  rm -rf "$target"
  if [[ $action == deploy ]]; then
    mkdir -p "$target"
    cp -R "$site_dir"/. "$target"/
  fi
  touch .nojekyll
  git add -A

  if [[ $action == remove ]] && git diff --cached --quiet "$base"; then
    echo "$target はありません"
    exit 0
  fi

  # 親を持たないコミットにして、gh-pages の履歴を常に1コミットに保つ。
  commit=$(git commit-tree "$(git write-tree)" -m "$action $target")
  if git push -q --force-with-lease="gh-pages:$base" origin "$commit:refs/heads/gh-pages"; then
    echo "$target を${action}しました"
    exit 0
  fi
  echo "gh-pages が更新されていたのでやり直します ($attempt)" >&2
  sleep $((attempt * 3 + RANDOM % 5))
done

echo "gh-pages への push に失敗しました" >&2
exit 1
