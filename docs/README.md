# ToriNos ドキュメント

このディレクトリには、継続的に参照する要件、アーキテクチャ、将来計画だけを置く。
特定の修正やリファクタリングのためだけに作成した実装仕様は、実装完了後に削除する。

## 要件

- [`app-requirements.md`](./app-requirements.md) — アプリ全体の機能要件、NIP対応状況、未確定事項

## アーキテクチャ

- [`account-session-viewmodel-architecture.md`](./account-session-viewmodel-architecture.md) — アカウント切り替え時の状態所有権とライフサイクル
- [`subscription-architecture-design.md`](./subscription-architecture-design.md) — Nostr購読セッションとリレー別状態管理
- [`profile-cache-design.md`](./profile-cache-design.md) — kind 0プロフィールの取得、キャッシュ、更新方針

## 将来計画

- [`push-notification-backend-plan.md`](./push-notification-backend-plan.md) — Cloudflare Workersを使ったプッシュ通知バックエンド計画

## 公開サイト

`site/`にはGitHub Pagesで公開するHTMLとスタイルを置く。

## 管理方針

- 現在の実装と異なる記述を見つけた場合は、実装変更と同じ変更セットで更新する。
- 未完了項目は、関連する設計文書の「導入状況」「移行手順」「未確定事項」で管理する。
- 完了後も判断理由や守るべき境界として有用な内容は、タスク固有仕様ではなく該当するアーキテクチャ文書へ反映する。
- 一時的な調査メモ、修正手順、受け入れチェックリストはコミット対象の恒久文書にしない。
