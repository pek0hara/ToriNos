# ToriNos ドキュメント

このディレクトリには、継続的に参照する要件、アーキテクチャ、将来計画だけを置く。
特定の修正やリファクタリングのためだけに作成した実装仕様は、実装完了後に削除する。

## 要件

- [`app-requirements.md`](./app-requirements.md) — アプリ全体の機能要件、NIP対応状況、未確定事項

## アーキテクチャ

- [`overall-refactor-design.md`](./overall-refactor-design.md) — 2026-10-16の全体レビューに基づく、有限取得・投稿入力・集計・通信管理などのリファクタ優先順位と段階的な移行設計（主要経路を実装・移行済み）
- [`refactor-details/README.md`](./refactor-details/README.md) — 全体レビューで選定した7箇所の詳細設計（責務・型・処理フロー・競合対策・移行手順・テスト、主要経路を実装・移行済み）

- [`nip-compatibility-design.md`](./nip-compatibility-design.md) — 下書きのNIP-37互換、リポストのkindと取得先、リアクションとBech32検証の共通規則

- [`account-session-viewmodel-architecture.md`](./account-session-viewmodel-architecture.md) — アカウント切り替え時の状態所有権とライフサイクル
- [`subscription-architecture-design.md`](./subscription-architecture-design.md) — Nostr購読セッション、リレー別状態管理、フィードのエンゲージメント履歴取得と送信キュー
- [`engagement-fetch-redesign.md`](./engagement-fetch-redesign.md) — リアクション・返信・リポストの件数を共有ストアのイベント集合から導出する再設計と、実機ログで見つかった購読・通信層の不備（二重REQ、同時購読数、NOTICE拒否）の修正方針（未着手）
- [`relay-fetch-failure-policy-design.md`](./relay-fetch-failure-policy-design.md) — 有限取得でリレーごとの結果を読む共通の規則（確定・置き換え・取り直し・諦める回数）、遅いリレーを待たない仕組み、ジャーナルへの適用（もらったいいねも選択中のリレーから取る）
- [`feed-relay-merge-design.md`](./feed-relay-merge-design.md) — フォローフィードのリレー別履歴カーソル、停止リレー分離、復旧マージ
- [`feed-relay-loading-fix-plan.md`](./feed-relay-loading-fix-plan.md) — 2026-10-03レビューに基づく短いページの終端判定、未取得差分の保持、保存と表示の分離の修正方針（未着手）
- [`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md) — フィードの状態分離、描画軽量化、位置保持、画像・プリフェッチ方針
- [`feed-chrome-interaction-design.md`](./feed-chrome-interaction-design.md) — フィードヘッダー／ボトムナビのドラッグ、慣性中断、先頭再表示を扱う操作状態機械
- [`feed-chrome-refactor-design.md`](./feed-chrome-refactor-design.md) — 現行のフィードヘッダー動作を固定したまま分岐を純粋ロジックへ移す段階的リファクタ計画
- [`feed-initial-reveal-design.md`](./feed-initial-reveal-design.md) — フィード初回表示の公開タイミングをコントローラへ一本化し、透明のまま取り残されないフェード演出へ移す段階的リファクタ設計
- [`feed-inline-post-composer-design.md`](./feed-inline-post-composer-design.md) — フィードの＋から簡易投稿欄を開き、入力を保ったまま現行投稿シートへ展開するUI・状態共有設計
- [`footer-customization-design.md`](./footer-customization-design.md) — フッターを1段の横スクロールにし、並び順と表示する機能を設定で変える設計、コンテンツのフェード切り替えと非表示のコンテンツへの入口
- [`status-tab-refactor-design.md`](./status-tab-refactor-design.md) — ステータスタブの購読ライフサイクル、置換イベント集約、投稿処理を整理する段階的リファクタ設計
- [`article-tab-refactor-design.md`](./article-tab-refactor-design.md) — 記事タブの一覧ViewModel統合、置換イベント集約、取得・削除処理、Markdown解析を整理する段階的リファクタ設計
- [`journal-refactor-design.md`](./journal-refactor-design.md) — ジャーナルの仕様整理、下書き一覧の分離、種類判定の統一、取得計画・日付索引・エンゲージメント集約を整理する段階的リファクタ設計
- [`custom-emoji-refactor-design.md`](./custom-emoji-refactor-design.md) — カスタム絵文字の同一性・タグ解析の統一、アカウント所有の状態とkind 10030同期の競合解決、公開セット取得と表示を整理する段階的リファクタ設計
- [`inline-media-player-design.md`](./inline-media-player-design.md) — 投稿カード内の動画・音声プレイヤーの判定、遅延生成、単一インスタンス管理とプラットフォーム実装
- [`profile-cache-design.md`](./profile-cache-design.md) — kind 0プロフィールの取得、キャッシュ、更新方針
- [`cache-performance-refactor-design.md`](./cache-performance-refactor-design.md) — キャッシュと長寿命状態の上限、差分通知、DB・画像メモリの性能改善方針
- [`search-performance-fix-plan.md`](./search-performance-fix-plan.md) — 検索性能の設計と導入状況、キャッシュ・購読・WebSocketのライフサイクル
- [`post-search-refactor-design.md`](./post-search-refactor-design.md) — 投稿検索のページ状態機械、有限購読、付随情報（プロフィール・集計）の取得と中断・再開、同秒境界の扱い
- [`notification-target-resolution-design.md`](./notification-target-resolution-design.md) — 通知対象のKind非依存取得、取得状態とKind別表示・遷移の境界
- [`post-auto-translation-design.md`](./post-auto-translation-design.md) — OS機能を使う投稿言語判定、自動翻訳、言語モデル準備と安全なフォールバック
- [`web-private-key-login-design.md`](./web-private-key-login-design.md) — Web版(Kotlin/Wasm)の署名実装、ブラウザでの秘密鍵保存、未対応機能とテスト方針
- [`signer-abstraction-design.md`](./signer-abstraction-design.md) — `AccountSigner`のsuspend化と署名方式の保存、Web版のNIP-07とパスキーによる秘密鍵の保護、NIP-46（段階1〜3は実装済み、NIP-55は未着手）

## 将来計画

- [`push-notification-durable-object-design.md`](./push-notification-durable-object-design.md) — Yabume共有購読とDurable Objectを使うプッシュ通知基盤の現行設計
- [`zap-feature-design.md`](./zap-feature-design.md) — ポストカードの⚡ボタンからのZap送信（NIP-57）、請求書の検証、レシートの検証と合計表示、NWC（NIP-47）の段階計画（未着手）

## 開発フロー

- [`pr-preview-design.md`](./pr-preview-design.md) — Issue から Claude が PR を作り、別オリジンに Web 版プレビューを公開・PR を閉じたら削除する GitHub Actions

## テスト

- [`regression-test.md`](./regression-test.md) — リリース前に実機で通す全体の回帰テスト手順（画面別の操作・表示、文字サイズ別の確認）

## 公開サイト

`site/`にはGitHub Pagesで公開するHTMLとスタイルを置く。

## リリース

- [`ios-release.md`](./ios-release.md) — コマンドからiOSのReleaseビルドを作成し、TestFlightへアップロードする手順

## 管理方針

- 現在の実装と異なる記述を見つけた場合は、実装変更と同じ変更セットで更新する。
- 未完了項目は、関連する設計文書の「導入状況」「移行手順」「未確定事項」で管理する。
- 完了後も判断理由や守るべき境界として有用な内容は、タスク固有仕様ではなく該当するアーキテクチャ文書へ反映する。
- 一時的な調査メモ、修正手順、受け入れチェックリストはコミット対象の恒久文書にしない。

## UI設計

- [`badge-feature-design.md`](./badge-feature-design.md) — NIP-58の現行・旧形式、ポストカードの名前の右側とプロフィールの名前下の表示、授与検証・段階取得・参照キャッシュ・選択保存の設計
- [`article-drafts-matometr-design.md`](./article-drafts-matometr-design.md) — 記事下書き、自動保存、投稿画面改善、フィード複数選択からのMatometr記事作成を合わせた要件・設計案
- [`settings-navigation-design.md`](./settings-navigation-design.md) — 設定の統合、プロフィールのアカウント切り替え、サービスの絵文字タブ
- [`emoji-service-discovery-design.md`](./emoji-service-discovery-design.md) — 絵文字サービスの共通ヘッダー、単一リレー探索、フォロー先の公開登録人数による一覧表示・並び順
