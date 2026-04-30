# プッシュ通知バックエンド 実装プラン（Cloudflare Workers）

## アーキテクチャ

```
[アプリ (Android/iOS)]
    │
    │ POST /register  {pubkey, token, platform}
    ▼
[Cloudflare Worker: API]
    │
    ├── デバイストークン保存 → [Cloudflare KV]
    │
    └── Durable Object 起動/更新
            │
            │ WebSocket
            ▼
    [Nostrリレー (wss://...)]
            │
            │ イベント検知
            ▼
    [Cloudflare Worker: Notifier]
            │
            ├── Android → [FCM (Firebase Cloud Messaging)]
            └── iOS    → [APNs]
```

## ディレクトリ構成（案）

```
ToriNos/
└── backend/
    ├── wrangler.toml
    ├── package.json
    ├── src/
    │   ├── index.ts              # Worker エントリーポイント（登録API）
    │   ├── relay-watcher.ts      # Durable Object: リレー監視
    │   └── notifier.ts           # FCM / APNs 送信処理
    └── schema.sql                # DO SQLite スキーマ
```

## Cloudflare リソース構成

| リソース | 用途 |
|---|---|
| Workers | 登録API (`POST /register`, `DELETE /unregister`) |
| Durable Objects (SQLite) | pubkey ごとのリレー接続 + 購読状態管理 |
| KV | pubkey → デバイストークン マッピング |

## データモデル

### KV
- キー: `token:{pubkey}`
- 値: `{ token: string, platform: "android" | "ios", updatedAt: number }`

### Durable Object SQLite
```sql
CREATE TABLE subscriptions (
    pubkey      TEXT PRIMARY KEY,
    relay_url   TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);
```

## API エンドポイント

### POST /register
```json
{
  "pubkey": "npub1...",
  "token": "<FCM or APNs device token>",
  "platform": "android" | "ios",
  "relays": ["wss://relay.damus.io", "wss://nos.lol"]
}
```

### DELETE /unregister
```json
{
  "pubkey": "npub1..."
}
```

## 通知対象イベント (Nostr)

| kind | 内容 |
|---|---|
| 1 | メンション (`p`タグに自分のpubkeyを含む) |
| 4 | ダイレクトメッセージ (NIP-04) |
| 7 | リアクション |
| 6 | リポスト |

## Durable Object の動作

1. `RelayWatcher` DO を pubkey ごとに1インスタンス作成
2. Hibernation API を使って WebSocket 接続をスリープ対応にする（課金最小化）
3. リレーへ REQ フィルターを送信:
   ```json
   ["REQ", "sub1", {"#p": ["<pubkey>"], "kinds": [1, 4, 6, 7], "since": <timestamp>}]
   ```
4. EVENT 受信 → KV からデバイストークンを取得 → FCM/APNs へ送信

## 必要な外部サービス・認証情報

- [ ] Firebase プロジェクト + サービスアカウントキー (FCM v1 API)
- [ ] Apple Developer アカウント + APNs キー (.p8) + Team ID + Bundle ID
- [ ] Cloudflare アカウント (無料プランで可)

## 実装ステップ

1. `backend/` ディレクトリ作成・`wrangler.toml` 設定
2. KV ネームスペース作成・登録API実装
3. `RelayWatcher` Durable Object 実装（WebSocket + Hibernation）
4. FCM 送信処理実装（Android）
5. APNs 送信処理実装（iOS）
6. アプリ側: デバイストークン取得 → `/register` 呼び出し実装

## 参考

- [Durable Objects Hibernation API](https://developers.cloudflare.com/durable-objects/examples/websocket-hibernation-server/)
- [FCM HTTP v1 API](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send)
- [APNs Provider API](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
