# プッシュ通知基盤 Durable Object 設計

## 1. 目的

ToriNos のログインユーザーに、アプリがバックグラウンドまたは終了中でも、Nostr のメンション、返信、リアクション、リポストを通知する。

初期リリースでは監視対象リレーを `wss://yabu.me/v2` に限定する。通知基盤はNostrイベントの恒久的な保存場所や配信元にはせず、通知候補を検出して APNs / FCM へ最小限の通知データを転送する役割だけを持つ。

## 2. 設計原則

- Durable Object はユーザー単位に作らない。
- 初期構成では、全登録ユーザーを1個の `RelayWatcher` Durable Object で管理する。
- Yabume への WebSocket は全ユーザーで共有する。
- 秘密鍵はサーバーへ送信しない。
- デバイストークンは Yabume へ送信しない。
- 登録・解除 API は NIP-98 で認証する。
- Queue は at-least-once 配送として扱い、イベントIDと端末IDで投入を重複排除する。Push Providerによる厳密なexactly-once表示は保証しない。
- 通知本文へ投稿内容を含めず、原則としてイベントID、種別、送信者だけを含める。
- Yabume 障害時に再開できるよう、カーソルへ重複区間を設ける。
- 将来、リレーまたは監視方式を交換できる境界を設ける。

## 3. Cloudflare の制約

Durable Objects の WebSocket Hibernation API は、Durable Object が WebSocket サーバーとして接続を受ける場合に利用できる。Nostr リレーへ接続する外向き WebSocket はハイバネーションできない。

したがって `RelayWatcher` は接続中に duration 課金の対象となる。ユーザーごとに Durable Object を作るとユーザー数に比例して費用が増えるため、共有 Watcher を採用する。

また、外向き WebSocket が Durable Object の退避を防ぐのは接続ごとに最大15分である。この制約に対応するため、Watcher は10分未満の間隔で新しい接続へローテーションする。

## 4. 全体構成

```text
[ToriNos iOS / Android]
          |
          | POST /v1/devices
          | DELETE /v1/devices/{deviceId}
          | Authorization: Nostr <NIP-98 event>
          v
[Notification API Worker]
          |
          | RPC
          v
[RelayWatcher Durable Object: singleton]
          |-- SQLite: devices / preferences / cursors / dedup
          |
          | outbound WebSocket（通常1本、切替中のみ2本）
          v
[wss://yabu.me/v2]
          |
          | EVENT
          v
[Cloudflare Queue: push-delivery]
          |
          v
[Push Delivery Worker]
       |           |
       v           v
     [APNs]       [FCM]
```

Durable Object のIDは、初期構成では次の固定名から生成する。

```text
env.RELAY_WATCHER.idFromName("yabume-v2:shard-0")
```

## 5. コンポーネント

### 5.1 Notification API Worker

- HTTPS API の公開
- リクエストサイズとContent-Typeの検証
- NIP-98 認証イベントの検証とリプレイ防止
- `RelayWatcher` への登録・解除 RPC
- ヘルスチェック応答

API Worker はデバイストークンをログへ記録しない。

### 5.2 RelayWatcher Durable Object

- 端末と通知設定の永続化
- Yabume との共有 WebSocket 接続
- 登録pubkeyを分割したNostr購読の管理
- Nostrイベントの形式・署名・時刻検証
- 通知対象pubkeyの抽出
- 自分自身のイベントと重複イベントの除外
- Queueへの配送要求投入
- 接続カーソル、再接続、接続ローテーション
- 無効デバイストークンの削除受付

初期構成では Watcher と Registry を同じ Durable Object にまとめ、登録直後の購読反映を強整合にする。

### 5.3 Push Delivery Worker

- Queueのバッチ消費
- APNs JWTの生成とHTTP/2送信
- FCM HTTP v1アクセストークンの生成と送信
- `RelayWatcher` RPCから送信時点の有効なデバイストークンを取得
- 一時エラーの再試行
- 恒久エラーとなったデバイストークンの無効化
- 配送結果メトリクスの記録

### 5.4 Cloudflare Queue

Nostrイベント受信処理と外部Push APIを分離する。APNs / FCMの遅延や一時障害によって、Yabumeからのイベント受信を止めない。Queueメッセージには秘密情報や投稿本文を含めない。

## 6. API設計

### 6.1 端末登録

```http
POST /v1/devices
Authorization: Nostr <base64-kind-27235-event>
Content-Type: application/json
```

```json
{
  "deviceId": "018f...",
  "platform": "ios",
  "pushToken": "...",
  "locale": "ja-JP",
  "notifications": {
    "mentions": true,
    "replies": true,
    "reactions": true,
    "reposts": true
  }
}
```

要件:

- `deviceId` はアプリが端末内で生成するランダムなUUIDとする。
- `pubkey` は本文ではなくNIP-98認証イベントから取得する。
- NIP-98の `u`、`method`、`payload` タグを検証する。
- 認証イベントは発行から5分以内に限定し、同一イベントIDを再利用できないようにする。
- 同じ `deviceId` の再登録はトークン更新として扱う。
- 1 pubkeyから登録できる端末数は初期値10台を上限とする。

### 6.2 端末登録解除

```http
DELETE /v1/devices/{deviceId}
Authorization: Nostr <base64-kind-27235-event>
```

認証pubkeyが所有する `deviceId` だけを削除できる。

### 6.3 通知設定更新

```http
PATCH /v1/devices/{deviceId}/notifications
Authorization: Nostr <base64-kind-27235-event>
Content-Type: application/json
```

設定更新により、そのpubkeyで通知がすべて無効になった場合はNostr購読対象から除外する。

### 6.4 ヘルスチェック

```http
GET /health
```

公開エンドポイントではAPI Workerの稼働だけを返す。Watcherの詳細状態は管理用エンドポイントまたはCloudflareメトリクスで確認する。

## 7. SQLiteスキーマ

```sql
CREATE TABLE devices (
    device_id       TEXT PRIMARY KEY,
    pubkey          TEXT NOT NULL,
    platform        TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
    push_token      TEXT NOT NULL,
    locale          TEXT NOT NULL DEFAULT 'ja-JP',
    enabled         INTEGER NOT NULL DEFAULT 1,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    last_seen_at    INTEGER NOT NULL,
    UNIQUE(platform, push_token)
);

CREATE INDEX devices_pubkey_idx ON devices(pubkey);

CREATE TABLE preferences (
    device_id       TEXT PRIMARY KEY,
    mentions        INTEGER NOT NULL DEFAULT 1,
    replies         INTEGER NOT NULL DEFAULT 1,
    reactions       INTEGER NOT NULL DEFAULT 1,
    reposts         INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY(device_id) REFERENCES devices(device_id) ON DELETE CASCADE
);

CREATE TABLE relay_cursors (
    relay_url           TEXT PRIMARY KEY,
    last_created_at     INTEGER NOT NULL DEFAULT 0,
    last_event_id       TEXT,
    updated_at          INTEGER NOT NULL
);

CREATE TABLE event_dedup (
    event_id         TEXT PRIMARY KEY,
    received_at      INTEGER NOT NULL
);

CREATE INDEX event_dedup_received_idx ON event_dedup(received_at);

CREATE TABLE auth_dedup (
    event_id         TEXT PRIMARY KEY,
    expires_at       INTEGER NOT NULL
);

CREATE INDEX auth_dedup_expires_idx ON auth_dedup(expires_at);

CREATE TABLE delivery_dedup (
    event_id         TEXT NOT NULL,
    device_id        TEXT NOT NULL,
    queued_at        INTEGER NOT NULL,
    PRIMARY KEY(event_id, device_id)
);

CREATE INDEX delivery_dedup_queued_idx ON delivery_dedup(queued_at);

CREATE TABLE delivery_outbox (
    event_id         TEXT NOT NULL,
    device_id        TEXT NOT NULL,
    payload_json     TEXT NOT NULL,
    state            TEXT NOT NULL DEFAULT 'pending',
    attempts         INTEGER NOT NULL DEFAULT 0,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    PRIMARY KEY(event_id, device_id),
    FOREIGN KEY(device_id) REFERENCES devices(device_id) ON DELETE CASCADE
);

CREATE INDEX delivery_outbox_state_idx
    ON delivery_outbox(state, updated_at);
```

`push_token` はCloudflare側の保存時暗号化に加えて、必要に応じてアプリケーション鍵で暗号化する。暗号鍵はWorker Secretに置き、データベースへ保存しない。

## 8. Nostr購読設計

### 8.1 対象イベント

| kind | 用途 |
|---:|---|
| 1 | メンション、返信、引用 |
| 6 | リポスト |
| 7 | リアクション |

初期構成ではDMを対象にしない。将来NIP-17 / NIP-59を扱う場合は、公開イベント通知とは別のプライバシー設計を行う。

### 8.2 pubkeyの分割

1フィルターに含める `#p` は初期値200件とし、設定で変更可能にする。

```json
["REQ", "notify-g42-c0", {
  "kinds": [1, 6, 7],
  "#p": ["pubkey1", "pubkey2"],
  "since": 1700000000
}]
```

購読IDは `notify-g{generation}-c{chunkIndex}` とする。登録pubkeyが変化した場合は変更を2秒間デバウンスし、次の順番で購読を切り替える。

1. 新generationのREQを送信する。
2. 新generationがEOSEを受信する。
3. 旧generationへCLOSEを送信する。

切り替え中の重複は `event_dedup` で除外する。

### 8.3 カーソル

再接続時の `since` は次で求める。

```text
max(0, last_created_at - 120秒)
```

120秒の重複区間により、切断直前のイベントを取りこぼしにくくする。イベントIDによる重複排除を必須とする。

未来時刻が現在時刻より10分以上先のイベントは通知対象から除外し、カーソルを進める材料にも使わない。

### 8.4 イベント検証

- JSON形式と必須フィールド
- event IDの再計算
- Schnorr署名
- 許可したkind
- `created_at` の許容範囲
- `p` タグが登録pubkeyに一致すること
- 送信者pubkeyと通知対象pubkeyが異なること
- サイズ上限

## 9. 接続ライフサイクル

### 9.1 起動

1. SQLiteスキーマを初期化する。
2. 通知が有効なpubkeyを読み込む。
3. カーソルを読み込む。
4. Yabumeへ接続する。
5. 全チャンクのREQを送信する。
6. 8分後に接続ローテーション用Alarmを設定する。

### 9.2 8分ごとのローテーション

外向きWebSocketの15分制約へ余裕を持って対応するため、接続を8分ごとに更新する。

1. 現接続を維持したまま、新しいWebSocketを開く。
2. `last_created_at - 120秒` から新購読を開始する。
3. 新接続ですべてのEOSEを受信する。
4. 新接続をactiveに切り替える。
5. 旧接続の購読をCLOSEし、WebSocketを閉じる。
6. 次回Alarmを設定する。

EOSEが30秒以内に揃わない場合は新接続を破棄し、旧接続を維持して1分後に再試行する。

### 9.3 切断

指数バックオフは `5秒 → 15秒 → 30秒 → 1分 → 2分 → 最大5分` とする。成功状態が5分継続したらリセットし、再接続にはランダムなjitterを加える。

## 10. 通知判定

イベントのすべての `p` タグを抽出し、登録端末と突き合わせる。1イベントで複数ユーザーが対象になり得る。

- kind 7: `reaction`
- kind 6: `repost`
- kind 1かつ返信マーカーあり: `reply`
- その他のkind 1: `mention`

返信と単純メンションをサーバーだけで確実に区別できない場合は `mention` として送り、アプリがイベント取得後に表示を補正する。誤分類より通知漏れを避ける。

Queueメッセージ:

```json
{
  "eventId": "hex-event-id",
  "eventKind": 7,
  "notificationType": "reaction",
  "authorPubkey": "hex-pubkey",
  "targetPubkey": "hex-pubkey",
  "deviceId": "018f...",
  "platform": "ios",
  "createdAt": 1700000000
}
```

Queueメッセージにはpush tokenを含めない。Push Delivery Workerは配送直前に `RelayWatcher.getDeliveryTarget(deviceId)` をRPCで呼び、端末が現在も有効であることを確認してトークンを取得する。登録解除済みまたは無効化済みなら、外部Push APIを呼ばずにメッセージを正常終了する。

## 11. Pushペイロード

通知本文にNostr投稿本文、プロフィール名、画像URLを直接入れない。

```json
{
  "type": "reaction",
  "eventId": "hex-event-id",
  "author": "hex-pubkey",
  "relay": "wss://yabu.me/v2"
}
```

表示は「新しい返信があります」「投稿にリアクションがありました」などの固定文言とし、ユーザー名や本文は通知タップ後にアプリがリレーから取得する。

## 12. 配送と再試行

HTTP 429、APNs / FCMの5xx、タイムアウトはQueueの再試行へ委譲する。最大試行回数を超えたメッセージはDead Letter Queueへ送る。

APNsの `Unregistered` / `BadDeviceToken`、FCMの `UNREGISTERED` など恒久エラーでは該当端末を無効化する。最後の有効端末が無効になったpubkeyは、次回generation更新でNostr購読から除外する。

イベント受信処理とQueue送信は原子的にできないため、SQLiteのOutboxパターンを使う。

1. `delivery_dedup` と `delivery_outbox` を同じSQLiteトランザクションでINSERTする。
2. Outboxフラッシャーが `pending` を小さなバッチで読み、Queueへ送信する。
3. Queue送信成功後にOutboxを `queued` へ更新する。
4. 途中で失敗した行はAlarmまたは次のイベント処理で再送する。
5. `queued` のOutbox行は24時間後に削除する。

Queue送信に成功してから `queued` 更新前に停止した場合はQueueへ重複投入され得る。そのためQueue Consumer側でも `eventId + deviceId` を配送キーとし、APNsの `apns-collapse-id` またはFCMのcollapse keyへevent IDを設定する。

## 13. データ保持

| データ | 保持期間 |
|---|---:|
| 有効デバイストークン | 最終利用から90日を目安に更新確認 |
| 無効デバイストークン | 即時または24時間以内に削除 |
| event_dedup | 24時間 |
| delivery_dedup | 24時間 |
| delivery_outbox | queuedは24時間、pendingは成功または運用対応まで |
| auth_dedup | 10分 |
| 配送ログ | 個人を特定しない集計値を30日 |

定期削除はAlarm内で小さなバッチに分けて行う。

## 14. セキュリティとプライバシー

- 登録APIはNIP-98署名を必須とする。
- APIとRPCにレート制限を設ける。
- pubkey、デバイストークン、イベントIDを通常ログへ出さない。
- Push Providerの秘密鍵はWorker Secretで管理する。
- APNsキー、FCM資格情報、暗号鍵を同じ値で兼用しない。
- Nostrイベントの署名をリレー任せにせず再検証する。
- Yabumeへ送る `#p` フィルターから、ToriNos通知利用者のpubkey集合を推測され得ることをプライバシーポリシーへ記載する。
- 通知は明示的なオプトインとし、アプリ設定から停止・登録解除できるようにする。
- Yabume運営者へ、共有購読を通知基盤に利用することと想定負荷を事前に相談する。

## 15. 可用性と監視

初期構成はYabumeと単一Watcherへ依存するため、厳密な高可用構成ではない。最低限、次を監視する。

- Yabume WebSocket接続状態
- 最後に受信したEVENT時刻
- 最後にEOSEを受信した時刻
- 登録端末数、通知対象pubkey数
- Queue滞留数と最古メッセージ時刻
- APNs / FCMの成功率
- 無効トークン率
- 再接続回数

Watcherから5分以上正常性シグナルがない場合にアラートを出す。ただしEVENT時刻だけでは死活判定しない。

## 16. 費用モデル

共有Watcher 1個が常時128MB相当で30日稼働した場合の概算durationは次のとおり。

```text
2,592,000秒 × 0.128GB = 約331,776 GB-s/月
```

これは現行のWorkers Paidに含まれるDurable Objectsの月間duration枠内に収まる水準である。別途、Workers Paidの最低料金、Queue、リクエスト、ストレージ等が発生する。

Watcherを2シャード以上へ増やすとdurationもほぼシャード数に比例する。実測したメッセージ量、購読サイズ、CPU時間を基準にシャードを増やす。

## 17. シャーディング

次のいずれかが継続的に発生した場合にシャーディングを検討する。

- REQメッセージがYabumeの許容サイズを超える。
- 購読数またはEVENT流量で処理遅延が発生する。
- Durable ObjectのCPU上限へ接近する。
- 登録更新によるgeneration切り替えが頻発する。
- 単一オブジェクトのSQLite操作がボトルネックになる。

pubkeyは次の安定したハッシュで分割する。

```text
shard = first_32_bits(SHA-256(pubkey)) % shardCount
```

シャード数変更時には旧・新シャードが重複する移行期間を設ける。最初から自動リシャードは実装しない。

## 18. 将来の複数リレー対応

リレー固有処理を `RelaySource` 境界として扱う。

```text
RelaySource
  - connect()
  - replaceSubscriptions(pubkeys, cursor)
  - events()
  - close()
```

第二リレーを追加する場合は、基本的にリレーごとにWatcherを分ける。リレー横断の重複排除が必要になった時点で、Queue投入前に共有Dedup Durable Objectを追加するか、配送側で `eventId + deviceId` を一意にする。

## 19. 段階的リリース

### Phase 1: 内部検証

- テスト用端末10台以下
- メンションと返信のみ
- Yabume共有接続1本
- 手動メトリクス確認

### Phase 2: TestFlight / 内部Androidテスト

- リアクション、リポストを追加
- QueueとDead Letter Queueを有効化
- 無効トークン自動削除
- 接続ローテーションと障害試験

### Phase 3: 一般公開

- オプトインUIとプライバシー表示
- レート制限
- 運用アラート
- データ保持ジョブ
- 負荷計測に基づくチャンクサイズ調整

### Phase 4: 拡張

- 必要な場合だけシャーディング
- 第二リレー対応
- 通知種別の追加

## 20. 受け入れ条件

- 同じイベントを複数回受信しても、同一端末向けOutboxは1件だけ作られ、Push Providerへ同じcollapse keyで配送される。
- WebSocket切断中に発生したイベントを、再接続後の重複区間から回収できる。
- 接続ローテーション中に通知漏れがない。
- 他人のpubkeyへデバイストークンを登録できない。
- ログへデバイストークンまたは秘密鍵が出力されない。
- 無効になったAPNs / FCMトークンを自動的に停止できる。
- 通知を無効にしたユーザーが次回generationから購読対象外になる。
- Yabume停止中でもAPI登録情報が失われない。
- Yabume復旧後に手動操作なしで購読を再開できる。

## 21. 未確定事項

- Yabumeが許容する1フィルターあたりの `#p` 数とREQサイズ
- Yabume運営者との通知用途・想定負荷の合意
- APNs / FCM資格情報の本番ローテーション手順
- デバイストークンをアプリケーションレベルでも暗号化するか
- Workers Paid、Queues、ログ保存を含む本番予算上限
- 返信とメンションをサーバー側でどこまで分類するか
- 将来の第二監視リレー

## 22. 参照

- [Cloudflare Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [Cloudflare Durable Objects WebSockets](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [Cloudflare Durable Object lifecycle](https://developers.cloudflare.com/durable-objects/concepts/durable-object-lifecycle/)
- [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging)
- [APNs Provider API](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
