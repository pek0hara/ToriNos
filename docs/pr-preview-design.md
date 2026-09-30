# Issue から PR とプレビューを作る仕組み

Issue にラベルを付けると Claude が実装して PR を作り、同時に Web 版(Kotlin/Wasm)のプレビューを公開する。
PR を閉じる(マージ・未マージとも)とプレビューを削除する。

## 全体の流れ

```
Issue に `claude` ラベルを付ける(書き込み権限を持つ人だけ付けられる)
  └─ issue-to-pr.yml
       claude-code-action(タグモード)が claude/issue-N-… ブランチを作り、実装して push する
       → main との差分があれば PR を作る(本文に "Closes #N")
       → pr-preview.yml を workflow_dispatch で起動する
          └─ pr-preview.yml
               build  : PR のコードで wasmJs をビルドし、成果物をアーティファクトにする(シークレットなし)
               deploy : プレビュー用リポジトリの gh-pages/pr-N/ へ push し、PR に URL をコメントする
PR にコミットを push する(人が push した場合は pull_request: synchronize で同じ処理が走る)
PR に `@claude …` とコメントする
  └─ pr-claude.yml
       Claude が PR のブランチに修正を push → ブランチが進んでいれば pr-preview.yml を起動する
PR を閉じる
  └─ pr-preview.yml(pull_request: closed)
       cleanup: gh-pages から pr-N/ を消して push し、コメントを「削除済み」に書き換える
       Issue は "Closes #N" によりマージ時に閉じる
```

## プレビューを本番と別オリジンに置く

Web 版は秘密鍵を `localStorage` に平文で保存する(`BrowserKeyStore`)。`localStorage` はオリジン単位なので、
プレビューを `pek0hara.github.io` 配下(同じリポジトリのサブディレクトリや別リポジトリ)に置くと、
プレビューのコードが本番の鍵を読める。Issue 本文は誰でも書けるため、プロンプトインジェクションで
鍵を送り出すコードが紛れ込む経路があり、悪意がなくてもプレビューのバグが本番の保存データを壊しうる。

そのため、プレビューは **別の GitHub Organization の Pages**(例: `torinos-preview.github.io`)に置く。
本番の `deploy-web.yml`(Actions アーティファクト方式)は変更しない。

- プレビュー用リポジトリはリポジトリ変数 `PREVIEW_REPO`(`owner/name`)で指定する。
- URL は `https://<owner>.github.io/<name>/pr-N/`。`name` が `<owner>.github.io` のときは `https://<owner>.github.io/pr-N/`。
- Web 版はファイルを相対パスで読み込み、画面遷移はハッシュを使うため、サブディレクトリにそのまま置ける。
- プレビューでは `index.html` のタイトルに `[PR #N]` を付け、左下に `PREVIEW #N` の帯を出し、`noindex` を付ける。
  CSP はインラインスクリプトを禁止しているので、帯は style 属性だけで作る。
- オリジンを分けても、プレビューに本番の秘密鍵を入力すれば同じ危険がある。プレビューには検証用アカウントの鍵を使う。

## issue-to-pr.yml

- `issues: labeled` かつラベル `claude` のときだけ起動する。タグモードは起動した人の書き込み権限を確認する。
- `claude-code-action@v1` のタグモードを使う。タグモードは Issue 用のブランチを作り、Issue に進捗コメントを書く。
  PR は自動では作らないので、アクションの後のステップで `gh pr create` する。
- Claude に許すコマンドは `./gradlew` と `git` に限る。ビルドとテストは Claude が実行して確かめる。
- 認証はシークレット `CLAUDE_CODE_OAUTH_TOKEN`(`claude setup-token` で作る)。

### GITHUB_TOKEN で作った PR はワークフローを起動しない

`GITHUB_TOKEN` による push や PR 作成では `pull_request` などのワークフローが起動しない(無限ループ防止)。
例外は `workflow_dispatch` と `repository_dispatch` なので、PR を作った後に
`gh workflow run pr-preview.yml --ref <branch> -f pr=<N>` でプレビューを明示的に起動する。
PAT や GitHub App トークンで PR を作る方式は、トークンの種類によって起動したりしなかったりするため採らない。

## pr-claude.yml

PR 上で `@claude` と書くと、Claude が PR のブランチに修正を push する。
Issue 上のコメントは対象外にする(タグモードは Issue へのコメントで新しいブランチを作るため、PR が増える)。

- 起動条件: `issue_comment`(`issue.pull_request` があるものだけ)、`pull_request_review_comment`、
  `pull_request_review` のうち本文に `@claude` を含むもの。`issue_comment` は既定ブランチのワークフローで動く。
- 開いていて、このリポジトリのブランチから出ている PR だけを扱う。フォークのブランチには push できない。
- 実行前後で PR の head を比べ、進んでいれば `pr-preview.yml` を `workflow_dispatch` で起動する。
  Claude が途中で失敗しても push 済みのコミットがありうるので、失敗時も比べる。

## pr-preview.yml

| ジョブ | 条件 | 権限・シークレット | 内容 |
|---|---|---|---|
| build | 開いた・更新・再オープン・手動 | `contents: read` のみ | PR のコードでビルドし `_site` をアーティファクトにする |
| deploy | build 成功後 | `PREVIEW_DEPLOY_KEY`, `pull-requests: write` | PR のコードは実行しない。gh-pages へ push してコメント |
| cleanup | `closed` | `PREVIEW_DEPLOY_KEY`, `pull-requests: write` | `pr-N/` を削除してコメントを更新 |

- フォークからの PR は対象外(`head.repo.full_name == github.repository`)。フォークの PR にはシークレットが渡らない。
- **ビルドとデプロイのジョブを分ける**。Gradle のビルドスクリプトは PR 側で書き換えられるので、
  デプロイ鍵が見えるジョブでは PR のコードを動かさない。
- 同じ PR の実行は `concurrency: pr-preview-<N>` で直列にし、古いものを止める。`closed` は止めない。
- gh-pages への書き込みは複数 PR が同時に行いうる。Actions の concurrency は待ちが1件しか残らず、
  別 PR のデプロイが取り消されるため使わない。代わりに「取得 → 変更 → `--force-with-lease` で push」を
  最大5回やり直す(`.github/scripts/preview-pages.sh`)。
- wasm は push のたびに数 MB 増えるので、gh-pages は毎回親を持たない1コミット(`git commit-tree`)に作り直して強制 push する。
- PR へのコメントは `<!-- pr-preview -->` を含む1件を作り、以後は書き換える。

## 初期設定(手動)

1. プレビュー用の Organization とリポジトリ(公開)を作る。例: `torinos-preview/previews`。
2. そのリポジトリに `.nojekyll` だけを置いた `gh-pages` ブランチを作り、Pages の公開元を `gh-pages` / root にする。
3. `ssh-keygen -t ed25519 -f preview_deploy -N ''` で鍵を作り、
   公開鍵をプレビュー用リポジトリの Deploy key(書き込み可)に、秘密鍵を ToriNos のシークレット `PREVIEW_DEPLOY_KEY` に登録する。
4. ToriNos のリポジトリ変数 `PREVIEW_REPO` に `torinos-preview/previews` を設定する。
5. ToriNos のシークレット `CLAUDE_CODE_OAUTH_TOKEN` を登録する。
6. ToriNos に `claude` ラベルを作る。
7. Settings → Actions → General の Workflow permissions で「Allow GitHub Actions to create and approve pull requests」を有効にする。

## 運用上の注意

- ラベルを付ける前に Issue 本文を読む。本文は Claude への入力になり、Claude は `./gradlew` を実行できる。
  ワークフロー内で任意コードが動くと `CLAUDE_CODE_OAUTH_TOKEN` と書き込み可能な `GITHUB_TOKEN` に届く。
- `GITHUB_TOKEN` には `workflows` 権限がないので、Claude は `.github/workflows/` を変更した push ができない。
- 未マージのまま長く残った PR のプレビューは残り続ける。閉じれば消える。
- Pages の反映・削除は push から1分ほど遅れる。
- `workflow_dispatch` の `pr` は書き込み権限を持つ人が指定できる。PR 番号とブランチの対応は検証しない。

## 今後の拡張候補

- PR でテストを実行する CI。現状はプレビューのビルド成功しか確認していない。
- cleanup が失敗したときの取り残しを消す定期ジョブ(閉じた PR の `pr-N/` を削除)。
