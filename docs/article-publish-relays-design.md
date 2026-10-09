# 記事投稿画面のリレー設定整理

## 要件・設計

- 記事投稿画面のヘッダーからリレー設定ボタンを削除する。
- 投稿先はアプリのリレー設定で `enabled && write` のリレーすべてとする。読み取り専用・無効なリレーや記事閲覧用リレーへのフォールバックは行わない。
- 記事画面内のリレー設定ダイアログと本文内の呼び出しボタンも削除し、設定操作をアプリ側へ集約する。本文の投稿先件数表示は維持する。
- 公開時に最新の設定を取得する既存の `ArticlePublisher` と `AccountRelayStore.writableRelayUrlsSnapshot()` を維持する。画面表示と修正版の確認画面にも同じ `writableRelayUrls()` の条件を使う。
- 投稿先がゼロなら公開ボタンを無効にし、アプリのリレー設定を案内する。公開処理も署名・送信前に拒否する。
- 再送は既存の署名済みイベントを、元の投稿先のうち現在書き込みを許可された未受理リレーへ送る。新規追加リレーへの配信は新しい公開操作で行う。

## テスト・レビュー

- `ArticlePublisherTest.publicationUsesCurrentAppWriteRelays`: Publisher生成後の設定変更を反映し、有効な書き込み専用・読み書き可能リレーのみに送信する。
- `ArticlePublisherTest.noAppWriteRelaysPreventsSigningAndSending`: 投稿先なしで署名・送信せず、下書きを保持する。
- 既存の公開・部分成功・再送・設定変更テストを実行する。
- レビューでは設定ダイアログへの残存導線、広幅・狭幅レイアウトの呼び出し、画面と公開処理の投稿先条件を確認する。
- レビュー修正: 投稿先なしの案内が狭い画面で省略されないよう行数制限を外し、件数変数名を `writableRelayCount` に統一した。

## 検証結果

- iOSシミュレーター向け関連テスト: `ArticlePublisherTest` 12件、`ArticleEditorViewModelTest` 9件、`RelayStoreTest` 19件、計40件成功（追加テスト2件を含む）。
- `:composeApp:wasmJsBrowserDistribution` 成功。Webpackの動的依存・配信ファイルサイズ警告あり。
- 設定ダイアログ・ボタン・コールバックの残存参照なし。広幅・狭幅とも共通の本文コンポーネントを使用。
- 今回編集したファイルの `git diff --check` 成功。
