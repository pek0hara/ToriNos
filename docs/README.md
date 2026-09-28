# ToriNos ドキュメント

このディレクトリには、継続的に参照する要件、アーキテクチャ、将来計画だけを置く。
特定の修正やリファクタリングのためだけに作成した実装仕様は、実装完了後に削除する。

## 要件

- [`app-requirements.md`](./app-requirements.md) — アプリ全体の機能要件、NIP対応状況、未確定事項

## アーキテクチャ

- [`account-session-viewmodel-architecture.md`](./account-session-viewmodel-architecture.md) — アカウント切り替え時の状態所有権とライフサイクル
- [`subscription-architecture-design.md`](./subscription-architecture-design.md) — Nostr購読セッション、リレー別状態管理、フィードのエンゲージメント履歴取得と送信キュー
- [`feed-relay-merge-design.md`](./feed-relay-merge-design.md) — フォローフィードのリレー別履歴カーソル、停止リレー分離、復旧マージ
- [`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md) — フィードの状態分離、描画軽量化、位置保持、画像・プリフェッチ方針
- [`feed-chrome-interaction-design.md`](./feed-chrome-interaction-design.md) — フィードヘッダー／ボトムナビのドラッグ、慣性中断、先頭再表示を扱う操作状態機械
- [`feed-chrome-refactor-design.md`](./feed-chrome-refactor-design.md) — 現行のフィードヘッダー動作を固定したまま分岐を純粋ロジックへ移す段階的リファクタ計画
- [`feed-initial-reveal-design.md`](./feed-initial-reveal-design.md) — フィード初回表示の公開タイミングをコントローラへ一本化し、透明のまま取り残されないフェード演出へ移す段階的リファクタ設計
- [`status-tab-refactor-design.md`](./status-tab-refactor-design.md) — ステータスタブの購読ライフサイクル、置換イベント集約、投稿処理を整理する段階的リファクタ設計
- [`article-tab-refactor-design.md`](./article-tab-refactor-design.md) — 記事タブの一覧ViewModel統合、置換イベント集約、取得・削除処理、Markdown解析を整理する段階的リファクタ設計
- [`journal-refactor-design.md`](./journal-refactor-design.md) — ジャーナルの仕様整理、下書き一覧の分離、種類判定の統一、取得計画・日付索引・エンゲージメント集約を整理する段階的リファクタ設計
- [`custom-emoji-refactor-design.md`](./custom-emoji-refactor-design.md) — カスタム絵文字の同一性・タグ解析の統一、アカウント所有の状態とkind 10030同期の競合解決、公開セット取得と表示を整理する段階的リファクタ設計
- [`inline-media-player-design.md`](./inline-media-player-design.md) — 投稿カード内の動画・音声プレイヤーの判定、遅延生成、単一インスタンス管理とプラットフォーム実装
- [`profile-cache-design.md`](./profile-cache-design.md) — kind 0プロフィールの取得、キャッシュ、更新方針
- [`cache-performance-refactor-design.md`](./cache-performance-refactor-design.md) — キャッシュと長寿命状態の上限、差分通知、DB・画像メモリの性能改善方針
- [`search-performance-fix-plan.md`](./search-performance-fix-plan.md) — 検索性能の設計と導入状況、キャッシュ・購読・WebSocketのライフサイクル
- [`notification-target-resolution-design.md`](./notification-target-resolution-design.md) — 通知対象のKind非依存取得、取得状態とKind別表示・遷移の境界
- [`post-auto-translation-design.md`](./post-auto-translation-design.md) — OS機能を使う投稿言語判定、自動翻訳、言語モデル準備と安全なフォールバック

## 将来計画

- [`push-notification-durable-object-design.md`](./push-notification-durable-object-design.md) — Yabume共有購読とDurable Objectを使うプッシュ通知基盤の現行設計

## 公開サイト

`site/`にはGitHub Pagesで公開するHTMLとスタイルを置く。

## 管理方針

- 現在の実装と異なる記述を見つけた場合は、実装変更と同じ変更セットで更新する。
- 未完了項目は、関連する設計文書の「導入状況」「移行手順」「未確定事項」で管理する。
- 完了後も判断理由や守るべき境界として有用な内容は、タスク固有仕様ではなく該当するアーキテクチャ文書へ反映する。
- 一時的な調査メモ、修正手順、受け入れチェックリストはコミット対象の恒久文書にしない。
