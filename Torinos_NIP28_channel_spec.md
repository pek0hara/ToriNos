# ToriNos NIP-28 チャンネル改善 実装仕様書

## 1. 文書情報

- 対象: ToriNos の公開チャンネル機能（NIP-28）
- 作成日: 2026-09-23
- ステータス: 実装提案
- 元資料: [ChatGPT 共有会話「NIP28チャンネル不足整理」](https://chatgpt.com/share/6ab396d8-d7c0-83ee-afff-3e0e0fa71e32)
- 参照仕様:
  - [NIP-28: Public Chat](https://github.com/nostr-protocol/nips/blob/master/28.md)
  - [NIP-51: Lists](https://github.com/nostr-protocol/nips/blob/master/51.md)

> [!NOTE]
> NIP-28 は現在 `draft`、`unrecommended`、`optional` であり、新規のグループ機能には NIP-29 が推奨されている。本仕様は既存 NIP-28 チャンネルとの互換性改善を目的とし、NIP-29 への移行・新規実装は対象外とする。

## 2. 背景と課題

ToriNos には NIP-28 チャンネルの一覧・閲覧・投稿 UI があるが、チャンネル固有のリレー情報が投稿・購読の経路へ一貫して反映されていない。

NIP-28 の `kind:40` と最新の有効な `kind:41` に含まれる `content.relays` は、そのチャンネルのイベントを取得・配信する推奨リレーである。チャンネルメッセージの `kind:42` でも、`e` タグと返信時の `p` タグへ relay hint を含めることが推奨される。

現状の中心的な問題は、UI とイベント型としてのチャンネルは存在する一方、次のリレーコンテキストが分離されていないことである。

```text
ユーザーの read/write リレー
            ↓
チャンネル推奨リレー
            ↓
メッセージの relay hint
```

このため、次の不具合や分かりにくさが生じる。

- チャンネルの推奨リレーに投稿されず、他クライアントからメッセージが見えない。
- 推奨リレーを購読しないため、ToriNos から一部のメッセージが見えない。
- ユーザーが現在の閲覧先・投稿先を確認できない。
- `kind:41` で推奨リレーが変わっても、接続・購読が更新されない。
- 同じ URL の末尾スラッシュ差などで、同一リレーが重複する可能性がある。

## 3. 現行コードの確認結果

2026-09-23 時点のワークツリーを基準とする。

| 項目 | 現状 | 不足 |
| --- | --- | --- |
| `kind:40` の取得 | `ChannelController` で取得済み | `content.relays` をモデルに保持していない |
| `kind:41` の取得 | `e` タグで購読し、作成者と `created_at` を確認済み | 同時刻の決定規則、`root` marker の検証、リレー変更の反映がない |
| `kind:41` の作成 | チャンネル所有者向け編集処理が存在する | `relays` を保存せず、`e` タグに relay hint と `root` marker がない |
| チャンネル詳細 | kind 40/41、説明、作成者、推奨リレーの表示が一部存在する | チャンネル画面のヘッダーや投稿 UI には反映されない |
| `kind:42` の投稿 | `SignedEventPublisher` からユーザーの書き込みリレーへ投稿 | チャンネル推奨リレーの指定、relay hint、リレー別結果がない |
| `kind:42` の購読 | 起動時の `relayUrl` または有効リレーを使用 | `content.relays` を動的な購読先にできない |
| 明示リレー API | `RelayTarget.Explicit` と指定リレー投稿 API が存在する | `Explicit` は有効済みリレーに限定され、チャンネル固有の外部リレーを購読できない |
| リレー別投稿結果 | `RelayPublishResult` が成功・失敗を保持する | `SignedPublishResult.Published` まで結果が伝播しない |

既存の kind 41 編集・詳細表示は維持し、本仕様に沿って拡張する。

### 3.1 現在の作業ツリーで修正中の範囲

2026-09-23 時点の未コミット差分を、今後の実装ループのベースラインとする。ここでいう「修正中」は実装完了を意味せず、レビューとシミュレーターテストを通過するまでは未完了として扱う。

| 領域 | 現在の変更 | 状態 | 本仕様との関係 |
| --- | --- | --- | --- |
| チャンネル詳細 | 一覧のメニューから `ChannelDetailsDialog` を開き、kind 40/41、生JSON、名前、説明、画像、作成者、日時、`content.relays` を表示 | 作業中 | FR-11 の診断表示の土台 |
| メタデータ取得 | 詳細表示時に kind 40 と、所有者・channel ID が一致する kind 41 を有限取得 | 作業中 | FR-02 の一部。共通 resolver は未導入 |
| チャンネル一覧UI | 行の右側をメニュー + お気に入り + unread badge に整理 | 作業中 | relay context 対応前のUI整理 |
| リレー選択UI | 一覧ヘッダーの実装を共通 `RelaySelector` へ抽出し、接続状態ドットを表示 | 作業中 | FR-09/10 で再利用可能。ただし単一選択用 |
| 履歴キャッシュ | 履歴ページの複数メッセージを `upsertMessages` で1トランザクション保存 | 作業中 | FR-12 の前段 |
| メッセージ観測元 | メッセージ本体・観測リレー・チャンネル観測リレーを1トランザクション保存 | 作業中 | FR-12 の観測元管理に利用 |
| キャッシュprune | チャンネル単位上限と全体上限を導入し、孤立relay行を削除 | 作業中 | 長期運用時の容量制御 |
| Room DB | schema v6、migration 5→6、複合index `(channelId, createdAt, eventId)` を追加 | 作業中 | 本仕様のDB v7設計の直前段階 |
| 削除要求 | kind 5 に対象kindを示す `k` タグを追加 | 作業中 | kind 43との機能分離に必要 |
| プロフィール更新 | チャンネル画面・一覧で変更pubkeyだけを再取得 | 作業中 | 横断的な性能改善。NIP-28固有ではない |
| メッセージreducer | 旧 `ChannelMessageReducer` とテストを削除し、`ChannelHistory` 側へ責務を集約 | 作業中 | 重複排除責務の所在をレビュー対象にする |

現在の差分には、次の機能はまだ含まれていない。

- `ChannelMeta.relays`
- 共通 `ChannelMetadataResolver`
- `ChannelRelayContext`
- 推奨リレーへの動的購読・投稿
- kind 41/42 の relay hint 改善
- リレー別投稿結果のチャンネルUI表示
- チャンネル作成・編集時の推奨リレー選択
- kind 43/44、kind 10005

### 3.2 現在差分に対する設計レビュー結果

現在の差分は維持しつつ、次の点を後続ループで解消する。

1. `ChannelDetailsDialog` が独自の `ChannelDetailsContent` と最新kind 41選択を持っている。`ChannelMeta` と `ChannelMetadataResolver` 導入後は共通処理へ置き換え、画面ごとの判定差をなくす。
2. 現在の最新kind 41表示は `createdAt` だけで選択する。同時刻の場合の event ID tie-break を追加する。
3. 詳細取得先は現在の一覧選択リレーまたは通常リレーであり、`content.relays` の推奨先までは探索しない。relay context 導入後に metadata session へ統合する。
4. DB v6 の `channel_relays` は観測元リレーである。推奨リレーと混同せず、DB v7 で `channel_recommended_relays` を追加する。
5. `ChannelMessageReducer` 削除後も、ライブ受信、履歴ページ、キャッシュ再生の全経路で event ID 重複排除と安定ソートが保たれることをテストする。
6. `RelaySelector` は設定済みリレーの単一選択用として維持し、推奨リレーの複数選択には別の `RelayMultiSelector` を用いる。
7. 現在の差分には複数領域の性能改善が含まれるため、NIP-28実装を重ねる前にシミュレーター上でチャンネル一覧・詳細・履歴の回帰確認を完了する。

## 4. 目的

### 4.1 必須目的

1. 最新の有効な `kind:41` を解決し、チャンネルの実効メタデータを決定する。
2. `content.relays` をチャンネル単位で保持する。
3. 推奨リレーから `kind:42` を購読する。
4. 推奨リレーへ `kind:42` を投稿する。
5. kind 41/42 のタグへ適切な relay hint と marker を付ける。
6. 閲覧先・投稿先と送信結果をユーザーが確認できるようにする。
7. kind 41 による推奨リレー変更を開いている画面へ動的に反映する。

### 4.2 対象外

- NIP-29 グループへの自動変換
- リレーサーバー側の管理・モデレーション
- E2EE または非公開チャンネル
- NIP-28 以外のイベントを使った新しいグループ設計

## 5. 用語とデータモデル

### 5.1 チャンネルメタデータ

`ChannelMeta` を次の形へ拡張する。

```kotlin
@Serializable
data class ChannelMeta(
    val name: String = "",
    val about: String = "",
    val picture: String = "",
    val relays: List<String> = emptyList(),
)
```

パース時は不正な URL を除外し、正規化後に重複を除く。`relays` が欠落または空の場合でも、メタデータ全体の読み込みを失敗させない。

### 5.2 実効メタデータ

```kotlin
data class EffectiveChannelMetadata(
    val channelId: String,
    val ownerPubkey: String,
    val sourceEventId: String,
    val sourceKind: Int,
    val updatedAt: Long,
    val metadata: ChannelMeta,
)
```

- `sourceKind` は 40 または 41。
- 有効な kind 41 がなければ kind 40 を使う。
- `recommendedRelays` は `metadata.relays` の正規化済み値とする。
- 表示・購読・投稿・タグ生成は同じ `EffectiveChannelMetadata` を参照し、個別に解決しない。

### 5.3 リレーコンテキスト

```kotlin
data class ChannelRelayContext(
    val recommendedRelays: List<String>,
    val readRelays: Set<String>,
    val writeRelays: Set<String>,
    val primaryHint: String?,
)
```

初期方針は次のとおりとする。

- `readRelays = recommendedRelays ∪ fallbackReadRelays`
- `writeRelays = recommendedRelays ∪ userWriteRelays`
- `primaryHint = recommendedRelays.firstOrNull() ?: navigationRelayHint ?: userWriteRelays.firstOrNull()`

推奨リレーを優先するが、可用性と既存利用者への互換性のためユーザーリレーも併用する。設定画面での細かなルーティング選択は将来拡張とする。

## 6. 機能要件

### FR-01: リレー URL の正規化

すべてのチャンネルリレー URL は、保存・比較・接続の前に共通関数で正規化する。

- `ws://` または `wss://` のみ許可する。
- scheme と host は小文字として扱う。
- host のみを指す末尾 `/` の有無は同一として扱う。
- query、fragment、userinfo を含む URL は採用しない。
- 入力順を維持して重複を除く。
- 不正値は無視し、診断ログへ記録する。イベント全体は破棄しない。

例:

```text
wss://yabu.me
wss://yabu.me/
```

上記は内部では 1 件として扱う。

### FR-02: 最新 kind 41 の解決

`kind:40` を取得後、次の条件で `kind:41` を検索・購読する。

- kind が 41。
- `e` タグが対象 kind 40 の ID を指す。
- 発行者 pubkey が kind 40 の発行者と一致する。
- メタデータ JSON をパースできる。

候補のうち最大の `(created_at, id)` を採用する。`created_at` が同じ場合は event ID の辞書順で決定し、端末ごとの不定な選択を避ける。

NIP-10 形式の `root` marker と relay hint を持つイベントを ToriNos の発行形式とする。既存クライアント互換のため、受信時は marker のない旧イベントも受理してよい。

### FR-03: kind 41 によるチャンネル編集

kind 40 の発行者と現在の署名者が一致する場合だけ編集 UI を表示する。

編集対象:

- `name`
- `about`
- `picture`
- `relays`
- 任意の `t` タグによるカテゴリ（第2段階）

保存時は kind 40 を再作成せず、新しい kind 41 を発行する。

```json
{
  "kind": 41,
  "content": "{\"name\":\"...\",\"about\":\"...\",\"picture\":\"...\",\"relays\":[\"wss://yabu.me\"]}",
  "tags": [
    ["e", "<kind40-event-id>", "wss://yabu.me", "root"],
    ["client", "ToriNos"]
  ]
}
```

kind 41 は更新後の完全なメタデータを含める。変更項目だけを送る差分形式にはしない。

### FR-04: kind 40 作成時の推奨リレー

チャンネル新規作成 UI に推奨リレー選択を追加する。

- 初期選択はユーザーの書き込み可能リレー。
- 1 件以上を推奨するが、仕様互換のため空配列でも作成自体は可能とする。
- 手入力追加時は FR-01 の検証を行う。
- kind 40 の `content.relays` へ保存する。
- 作成した kind 40 は選択した推奨リレーにも投稿する。

### FR-05: チャンネル推奨リレーからの購読

チャンネルを開いたときの処理順は次のとおりとする。

```text
kind 40 を取得
    ↓
最新の有効な kind 41 を解決
    ↓
content.relays を正規化
    ↓
チャンネル用リレーコンテキストを作成
    ↓
推奨リレーへ接続
    ↓
kind 42 を購読
```

- チャンネル推奨リレーは、ユーザーの通常リレー設定に未登録でも一時接続できること。
- チャンネルを閉じたとき、その画面だけが使用していた一時購読と接続を解放すること。
- 同一リレーを複数の画面・機能が使用中なら参照を共有し、他の利用者がいる接続を切断しないこと。
- 履歴、ライブ更新、返信・リアクション集計も同じ read relay context を使うこと。
- 推奨リレーが空の場合は、ナビゲーション元の relay hint とユーザー read リレーへフォールバックすること。

`RelayTarget.Explicit` が有効済みリレーだけを対象とする現行制約は、チャンネル用途では不十分である。一時リレーを含む明示購読 API、または参照カウント付き `ChannelRelaySession` を `NostrRepository` 側に追加する。

### FR-06: kind 42 の投稿先

投稿先は `ChannelRelayContext.writeRelays` とする。少なくとも 1 件の推奨リレーがある場合、それらを必ず試行する。

- 未接続リレーには一時接続して送信する。
- リレーの NIP-65 write policy が明示されている場合は尊重する。
- ただしチャンネル推奨リレーがユーザー設定に存在しないだけの理由で送信対象から除外しない。
- 全送信先の結果を `RelayPublishResult` として UI 層まで返す。

成功判定:

- 1 件以上成功: 投稿成功。入力欄をクリアする。
- 一部成功: 投稿成功として扱い、リレー別の警告を表示できる状態を保持する。
- 全件失敗: 投稿失敗。入力内容を維持し、再試行可能にする。

### FR-07: kind 42 の relay hint

ルートメッセージ:

```json
["e", "<kind40-event-id>", "<primary-relay>", "root"]
```

返信メッセージ:

```json
[
  ["e", "<kind40-event-id>", "<primary-relay>", "root"],
  ["e", "<reply-event-id>", "<reply-relay-or-primary-relay>", "reply"],
  ["p", "<reply-user-pubkey>", "<reply-relay-or-primary-relay>"]
]
```

- `primary-relay` は `ChannelRelayContext.primaryHint` を使用する。
- relay hint を決められない場合もイベント作成を妨げず、空文字の要素は付けない。
- root/reply marker は必ず付ける。

### FR-08: kind 41 受信時の再購読

開いているチャンネルで、より新しい有効な kind 41 を受信して `relays` が変化した場合、次を原子的に更新する。

1. 実効メタデータ
2. ヘッダーと詳細表示
3. 投稿先プレビュー
4. 推奨リレーへの接続
5. kind 42 および関連イベントの購読先

新しい購読を開始してから古い購読を外し、切り替え時の取りこぼしを抑える。削除されたリレーはチャンネルの主要購読先から外すが、他機能が使用中なら接続自体は維持する。

### FR-09: 閲覧先リレーの表示

チャンネル画面のヘッダーに、チャンネル名と現在の read relay context を表示する。

```text
さびれたスナック
yabu.me・relay-jp.nostr.wirednet.jp
```

表示幅が足りない場合は `2 relays` とし、タップで詳細を表示する。

詳細には次を含める。

- 推奨リレー
- 実際の購読先
- 接続中・接続待ち・失敗の状態
- メタデータの取得元イベント（kind 40 または kind 41）

### FR-10: 投稿先リレーの表示

投稿欄の近くに `投稿先: N relays` を表示し、タップで URL 一覧を確認できるようにする。

- 送信前: 予定している投稿先
- 送信中: リレーごとの進行状態
- 送信後: 成功・失敗結果

例:

```text
yabu.me                       成功
relay-jp.nostr.wirednet.jp   失敗
```

### FR-11: チャンネル情報画面

次を表示する。

- アイコン
- 名前
- 説明
- 作成者
- Channel ID / kind 40 ID
- 推奨リレー
- 実際の購読先
- 作成日時
- 最終更新日時
- 実効メタデータの取得元
- 所有者本人の場合は「チャンネルを編集」

現在の `ChannelDetailsDialog` を拡張し、イベント JSON の診断表示は維持する。

### FR-12: キャッシュ

- kind 40、採用した kind 41、実効メタデータ、正規化済み推奨リレーをキャッシュする。
- kind 41 の受信後は、古い実効メタデータを置き換える。
- メッセージキャッシュは、同じイベントを複数リレーから受信しても event ID で重複排除する。
- キャッシュの所属リレーは単一 URL ではなく、必要に応じて観測元リレー集合を保持できる設計を検討する。

## 7. 第2段階の要件

### 7.1 kind 43: Hide Message

- メッセージのメニューに「非表示にする」を追加する。
- 対象 kind 42 の event ID を `e` タグに持つ kind 43 を発行する。
- 自分が発行した有効な kind 43 の対象メッセージを非表示にする。
- 既存の kind 5 削除要求とは別機能として扱う。

### 7.2 kind 44: Mute User

- メッセージのメニューに「このユーザーをミュート」を追加する。
- 対象 pubkey を `p` タグに持つ kind 44 を発行する。
- 自分が発行した有効な kind 44 の対象ユーザーの kind 42 を非表示にする。
- ToriNos の既存ミュート機能との統合方針を別途決定する。

### 7.3 kind 10005: 参加チャンネル同期

NIP-51 の kind 10005 を使い、参加中の NIP-28 チャンネルを端末間同期する。

```json
{
  "kind": 10005,
  "tags": [
    ["e", "<kind40-event-id>"],
    ["e", "<kind40-event-id>"]
  ]
}
```

ローカル DB は表示速度とオフライン利用のキャッシュとして残し、kind 10005 を同期元として統合する。

## 8. 実装構成案

### 8.1 モデル層

- `ChannelMeta`
  - `relays: List<String>` を追加。
- `ChannelMetadataResolver`（新規）
  - kind 40 と kind 41 の検証・選択を担当。
- `RelayUrlNormalizer`（新規または既存共通処理へ追加）
  - URL 検証、正規化、重複排除を担当。
- `ChannelRelayContext`
  - read/write/hint の決定を担当。

### 8.2 ネットワーク層

- `NostrRepository`
  - 未登録の推奨リレーを含む明示購読を追加。
  - 一時接続を参照カウントまたはセッション単位で管理。
  - 購読先を動的更新できる既存 `SubscriptionSession.update` を活用する。
- `SignedEventPublisher`
  - 任意の投稿関数またはリレー集合を指定可能にする。
  - `SignedPublishResult.Published` に `RelayPublishResult` を含める。

例:

```kotlin
data class Published(
    val event: NostrEvent,
    val relayResult: RelayPublishResult,
) : SignedPublishResult
```

### 8.3 状態管理層

`ChannelController` が次を単一状態として管理する。

- `effectiveMetadata`
- `relayContext`
- `relayConnectionStates`
- `lastPublishResult`
- `metadataSourceEvent`

`UiState.Ready` へ表示に必要な値だけを公開し、Composable からリレー選択ロジックを分離する。

### 8.4 UI 層

- `ChannelScreen`
  - ヘッダーへ閲覧先リレー概要を追加。
  - 投稿欄へ投稿先リレー概要を追加。
  - 一部成功時の警告とリレー別結果を表示。
- `ChannelDetailsDialog`
  - 実効メタデータ、購読先、状態を追加。
- 編集ダイアログ
  - picture と relays の編集を追加。
  - 共通 `RelaySelector` を再利用する。
- チャンネル作成画面
  - 推奨リレー選択を追加。

## 9. 処理フロー

### 9.1 チャンネルを開く

```text
画面遷移時の channelId / relay hint
                ↓
kind 40 をキャッシュまたはネットワークから取得
                ↓
所有者を確定し kind 41 を取得・購読
                ↓
ChannelMetadataResolver で実効メタデータを決定
                ↓
ChannelRelayContext を生成
                ↓
購読先を推奨リレー + fallback に更新
                ↓
キャッシュとライブイベントを統合して表示
```

### 9.2 メッセージを投稿する

```text
本文を検証
    ↓
relay context から primary hint と write relays を取得
    ↓
root/reply/p タグを構築
    ↓
署名
    ↓
全 write relays へ並列送信
    ↓
リレー別結果を状態へ保存
    ↓
1件以上成功なら入力をクリア、全失敗なら本文を維持
```

### 9.3 kind 41 が更新される

```text
kind 41 受信
    ↓
所有者・参照先・JSON・新旧を検証
    ↓
実効メタデータを置換
    ↓
新しい relay context を計算
    ↓
新規リレーの購読開始
    ↓
UIを更新
    ↓
不要になった購読を解除
```

## 10. エラー処理

| 状況 | 動作 |
| --- | --- |
| kind 40 の JSON が不正 | チャンネル ID を暫定名として表示し、再取得可能にする |
| kind 41 の発行者が所有者と異なる | 無視して診断ログへ記録 |
| kind 41 の JSON が不正 | 直前の有効なメタデータを維持 |
| 推奨リレーが空 | ナビゲーション元 hint、ユーザー read/write リレーへフォールバック |
| 一部の推奨リレーへ接続不能 | 接続できたリレーで表示を続行し、詳細に失敗を表示 |
| 投稿が一部成功 | 成功扱い。失敗リレーと再試行導線を表示 |
| 投稿が全件失敗 | 失敗扱い。下書きを保持 |
| 更新中に relay context が変化 | 投稿開始時点の snapshot を使い、処理途中で送信先を変えない |

## 11. セキュリティと制限

- kind 41 は必ず kind 40 所有者の署名を検証する。
- リレー URL は FR-01 に従い、任意 scheme や userinfo を拒否する。
- 推奨リレーは第三者が指定する外部接続先であるため、接続数に上限を設ける。
- 1 チャンネルの推奨リレーは最大 10 件を目安とし、超過分は無視して診断可能にする。
- 一時リレー接続にはタイムアウト、キャンセル、再接続回数の上限を設ける。
- relay hint は配送先の保証ではなくヒントとして扱う。

## 12. テスト要件

### 12.1 単体テスト

- `ChannelMeta` が `relays` の有無を含めてパースできる。
- URL 末尾 `/` の差が重複排除される。
- 不正 scheme、query、fragment、userinfo が除外される。
- 所有者以外の kind 41 を無視する。
- 複数 kind 41 から最新を選ぶ。
- 同一 `created_at` の kind 41 を event ID で決定する。
- kind 41 がない場合は kind 40 を採用する。
- root kind 42 に正しい marker と relay hint が付く。
- reply kind 42 に root/reply/p タグが付く。
- 推奨リレー変更で read/write relay context が更新される。
- 投稿の全成功、一部成功、全失敗を区別する。

### 12.2 結合テスト

- ユーザー設定にない推奨リレーから kind 42 を取得できる。
- ユーザー設定にない推奨リレーへ kind 42 を投稿できる。
- kind 41 受信後に新しいリレーへ再購読される。
- 古いリレーだけのイベントと新しいリレーだけのイベントを重複なく統合できる。
- チャンネル画面を閉じると専用購読が解除される。
- 一部リレー失敗時にも成功したイベントをローカル表示へ反映できる。

### 12.3 UI テスト

- ヘッダーに閲覧先の概要が表示される。
- 投稿欄に投稿先の概要が表示される。
- リレー詳細で URL と接続状態を確認できる。
- 所有者だけが編集ボタンを使える。
- 編集内容に `relays` を含む kind 41 が生成される。
- 全件失敗時に下書きが残る。

## 13. 受け入れ条件

- [ ] 最新の有効な kind 41 の `name`、`about`、`picture`、`relays` が画面に反映される。
- [ ] 所有者以外が発行した kind 41 は反映されない。
- [ ] `wss://example.com` と `wss://example.com/` が同一リレーとして扱われる。
- [ ] チャンネル推奨リレーがユーザー設定外でも kind 42 を購読できる。
- [ ] kind 42 がすべての選択済み推奨リレーへ送信される。
- [ ] kind 42 の `e` タグに root marker と relay hint が入る。
- [ ] 返信時に root/reply/p タグが正しく生成される。
- [ ] 閲覧先と投稿先を UI から確認できる。
- [ ] リレー別の送信成功・失敗を確認できる。
- [ ] 一部成功時は投稿済み、全失敗時は未投稿として扱われる。
- [ ] kind 41 のリレー変更を、画面を開き直さずに購読と UI へ反映できる。
- [ ] 既存の relays を持たない NIP-28 チャンネルも引き続き閲覧・投稿できる。

## 14. 実装優先順位

### Phase 1: リレーコンテキストの基盤（最優先）

1. `ChannelMeta.relays` と URL 正規化
2. 最新 kind 41 の決定ロジックを resolver へ分離
3. `ChannelRelayContext` の導入
4. 未登録推奨リレーを含む購読 API
5. kind 42 の購読先・投稿先への推奨リレー反映
6. kind 42 の relay hint と marker
7. リレー別投稿結果の伝播

### Phase 2: UI と編集

1. ヘッダーの閲覧先表示
2. 投稿欄の投稿先表示
3. チャンネル詳細の拡張
4. kind 41 編集への picture / relays 追加
5. kind 40 作成時の推奨リレー選択
6. kind 41 更新時の動的再購読

### Phase 3: NIP-28 補完

1. kind 43 Hide Message
2. kind 44 Mute User
3. kind 10005 参加チャンネル同期
4. NIP-29 との役割分担を別仕様として整理

## 15. 完了の定義

Phase 1 と Phase 2 の受け入れ条件を満たし、単体・結合・UI テストが通過した時点で「チャンネル固有の relay context 対応」を完了とする。

NIP-28 完全対応を表明する場合は Phase 3 の kind 43/44 までを必須とし、kind 10005 は NIP-51 対応として別に明示する。

## 16. 詳細設計

### 16.1 設計方針

本対応では、チャンネル専用のネットワークスタックを新設せず、既存の `NostrRepository`、`SubscriptionSession`、`RelayPublishResult` を拡張する。

採用する原則は次のとおり。

1. メタデータ解決、リレー選択、タグ生成を UI から分離し、単体テスト可能な純粋ロジックにする。
2. `ChannelController` をチャンネル画面における実効メタデータと relay context の唯一の所有者にする。
3. 通常リレーとチャンネル固有リレーを `activeSubscriptions` / `activeRelays` の同じライフサイクルで管理する。
4. 「イベントを観測したリレー」と「メタデータで推奨されたリレー」を DB 上でも別の概念として保持する。
5. 署名対象のイベントと送信先リレー集合を、投稿開始時点の immutable snapshot から生成する。
6. 受信互換性は広く、ToriNos からの発行形式は NIP-28 推奨形式へ統一する。

### 16.2 変更対象一覧

| ファイルまたは新規クラス | 主な変更 |
| --- | --- |
| `model/ChannelMeta.kt` | `relays`、実効メタデータ型、resolver を追加 |
| `model/NoteContext.kt` | チャンネルタグ生成を共通 builder へ委譲 |
| `model/ReplyTarget.kt` | チャンネル返信の root/reply/p relay hint を補完 |
| `network/RelayStore.kt` | URL 正規化を強化し、全経路で共有 |
| `network/NostrRepository.kt` | 未登録リレーを含む `RelayTarget.Explicit`、接続状態、明示購読を実装 |
| `network/SubscriptionSession.kt` | 必要に応じて接続先 snapshot を公開 |
| `network/ChannelCacheStore.kt` | 実効メタデータと推奨リレーの保存・取得 API を追加 |
| `network/cache/ChannelCacheDatabase.kt` | DB v7 と推奨リレーテーブルを追加 |
| `ui/timeline/SignedEventPublisher.kt` | 指定リレー投稿と結果伝播を追加 |
| `ui/channel/ChannelController.kt` | メタデータ解決、動的購読、投稿結果の中心実装 |
| `ui/channel/ChannelViewModel.kt` | relay context と UI イベントを公開 |
| `ui/channel/ChannelScreen.kt` | ヘッダー、投稿先、詳細、編集 UI を追加 |
| `ui/channel/ChannelListViewModel.kt` | kind 40 作成、初回投稿、推奨リレー編集を追加 |
| `ui/channel/ChannelListScreen.kt` | 作成ダイアログにリレー選択を追加 |
| `ui/channel/ChannelDetailsDialog.kt` | 実効情報と接続状態の診断表示を追加 |
| `ComposerCoordinator.kt` | チャンネル返信時の投稿先 snapshot を保持 |
| `ui/post/PostSheet.kt` | チャンネル推奨リレーを初期選択へ反映 |
| `ui/post/PostViewModel.kt` | 共通タグ builder と指定リレー投稿を使用 |

### 16.3 URL 正規化の設計（FR-01）

#### 責務

既存の `normalizeRelayUrl` を唯一の正規化関数として強化する。チャンネル専用の重複関数は作らない。

```kotlin
internal fun normalizeRelayUrl(rawUrl: String): String?

internal fun normalizeRelayUrls(
    rawUrls: Iterable<String>,
    limit: Int = MAX_CHANNEL_RELAYS,
): List<String>
```

#### 正規化規則

1. 前後の空白を除去する。
2. Ktor の URL パーサーで解析する。
3. scheme が `ws` / `wss` 以外なら `null`。
4. host が空、userinfo が存在、query または fragment が存在する場合は `null`。
5. scheme と host を小文字化する。
6. 既定ポート `ws:80` / `wss:443` は省略する。
7. path が空または `/` の場合は末尾 `/` を除く。
8. `/relay` のような意味のある path は保持する。
9. 入力順を保った `LinkedHashSet` で重複を除く。
10. 最大 10 件で打ち切る。

保存・比較・`RelayTarget`・publish・relay hint・キャッシュキーのすべてに正規化済み URL を使う。表示名だけが必要な場合も、正規化済み URL から scheme を除去する。

#### 互換性

既存 `RelayStore` の保存値は、読み込み時に同じ関数を通して正規化する。正規化によって同じ URL になった複数設定は次の規則で統合する。

- `enabled = OR`
- `read = OR`
- `write = OR`
- 表示順は最初に出現した項目を維持

### 16.4 メタデータ resolver の設計（FR-02）

#### 新規型

```kotlin
internal data class ChannelMetadataResolution(
    val channelCreateEvent: NostrEvent,
    val effectiveEvent: NostrEvent,
    val metadata: ChannelMeta,
    val ignoredEventIds: Set<String>,
)

internal object ChannelMetadataResolver {
    fun resolve(
        channelId: String,
        createCandidates: Collection<NostrEvent>,
        updateCandidates: Collection<NostrEvent>,
    ): ChannelMetadataResolution?
}
```

#### 検証順

kind 40 候補:

1. `kind == 40`
2. `id == channelId`
3. 署名検証済みの `NostrEvent` であること（ネットワーク受信時の既存検証を前提）
4. `content` が `ChannelMeta` としてパース可能

kind 41 候補:

1. `kind == 41`
2. `pubkey == kind40.pubkey`
3. `e` タグの参照先が `channelId`
4. `content` が `ChannelMeta` としてパース可能

有効な kind 41 を `(createdAt, id)` の降順で並べ、先頭を採用する。有効な kind 41 がなければ kind 40 を採用する。

#### 受信時の marker 方針

- `e` タグの marker が `root`: 正常。
- marker が空またはタグ長が 2: 旧クライアント互換として受理。
- `reply` など `root` 以外の明示 marker: 対象外として無視。

#### Controller での保持

`ChannelController` はイベント到着のたびにその場で値を上書きせず、候補を event ID 単位で保持して resolver を再実行する。

```kotlin
private var channelCreateEvent: NostrEvent? = null
private val metadataUpdates = linkedMapOf<String, NostrEvent>()
private var effectiveMetadata: EffectiveChannelMetadata? = null
```

これにより、リレーごとに到着順が違っても結果が安定する。

### 16.5 relay context の設計（FR-05、FR-06）

#### Builder

```kotlin
internal object ChannelRelayContextBuilder {
    fun build(
        recommendedRelays: List<String>,
        navigationRelayHint: String?,
        userReadRelays: Collection<String>,
        userWriteRelays: Collection<String>,
    ): ChannelRelayContext
}
```

#### 決定規則

```text
bootstrapRelays = navigationRelayHint ?: userReadRelays
readRelays      = recommendedRelays + bootstrapRelays
writeRelays     = recommendedRelays + userWriteRelays
primaryHint     = recommendedRelays.first
               ?: navigationRelayHint
               ?: userWriteRelays.first
```

各集合は正規化済み、入力順維持、最大件数適用後の値とする。`recommendedRelays` は必ず先頭へ置く。

ユーザー設定に対象 URL があり `read == false` の場合でも、チャンネル閲覧の明示操作を優先して recommended relay の購読を許可する。`write == false` の場合は NIP-65 の明示的な意思を尊重し、その URL を `writeRelays` から除外して UI に理由を表示する。未登録 URL は read/write とも許可する。

#### 状態更新

`ChannelController` に次を追加する。

```kotlin
private var relayContext = ChannelRelayContext.EMPTY
private var metadataSession: SubscriptionSession? = null
private var messageSession: SubscriptionSession? = null
private var relatedEventsSession: SubscriptionSession? = null
private var relayContextGeneration = 0L
```

generation は kind 41 の連続更新が発生したとき、古い遅延処理が新しい relay context を上書きしないために使う。

### 16.6 明示リレー購読の設計（FR-05）

#### `RelayTarget.Explicit` の意味変更

現在は `Explicit.urls` も `enabledRelayUrls` でフィルタされるため、名前と実際の動作が一致していない。次の意味へ変更する。

```kotlin
private fun RelayTarget.urls(enabledRelayUrls: List<String>): List<String> = when (this) {
    RelayTarget.AllEnabled -> enabledRelayUrls
    is RelayTarget.Single -> listOf(url).filter { it in enabledRelayUrls }
    is RelayTarget.Explicit -> normalizeRelayUrls(urls)
}
```

- `AllEnabled`: ユーザー設定の read 有効リレー。
- `Single`: 一覧 UI で選択された設定済みリレー。
- `Explicit`: 呼び出し側が明示した未登録 URL を含む正確な集合。

`Explicit` の既存呼び出し箇所は意味変更の影響を監査する。設定済みリレーだけを意図する呼び出しは、事前に `RelayStore.enabledRelayUrlsSnapshot()` と積集合を取る。

#### 接続ライフサイクル

別系統の `temporaryRelays` はチャンネルのライブ購読には使わない。`activeSubscriptions` の target に明示 URL が入れば、既存の次の仕組みをそのまま利用できる。

- `desiredActiveRelayUrlsLocked()` による利用中 URL の集合化
- `reconcileActiveRelaysLocked()` による接続作成・解放
- 複数 subscription 間の暗黙の参照共有
- `SubscriptionSession.update()` によるフィルターと接続先の動的変更
- `SubscriptionSignal.Event.relayUrl` による観測元追跡

`subscribeTemporaryRelay()` は単発の既存用途のため残すが、新しいチャンネル実装からは呼ばない。

#### 購読分割

チャンネル画面では最低 2 セッションに分ける。

1. `metadataSession`
   - filter: kind 40 の ID、kind 41 の `e` タグ
   - target: bootstrap + 現在までに判明した推奨リレー
2. `messageSession`
   - filter: channelId を root に持つ kind 42
   - target: `relayContext.readRelays`

返信・リアクション・リポストの関連購読は `relatedEventsSession` にまとめるか、既存 ID ごとの購読を同じ `RelayTarget.Explicit(readRelays)` へ更新する。

#### 初期化順

1. キャッシュ済み実効メタデータを読み込み、暫定 relay context を構築。
2. metadata session を bootstrap relay で開始。
3. kind 40 受信後、その `relays` を metadata session の target に加える。
4. resolver の結果から relay context を再計算。
5. message session を開始または更新。
6. キャッシュメッセージとライブイベントを統合。

キャッシュがなくても metadata 取得完了まで画面全体をブロックせず、チャンネル ID と bootstrap relay を使った暫定状態を表示する。

### 16.7 購読切り替えの設計（FR-08）

kind 41 により read relay が `old` から `next` に変わる場合、次の二段階更新を行う。

```kotlin
val transitionTargets = old.readRelays + next.readRelays
messageSession.update(filters, RelayTarget.Explicit(transitionTargets))
awaitNewRelayReadyOrTimeout(next.readRelays - old.readRelays)
messageSession.update(filters, RelayTarget.Explicit(next.readRelays))
```

`awaitNewRelayReadyOrTimeout` は次のいずれかで完了する。

- 追加リレーが `Connected` になった。
- 追加リレーから最初の EVENT / EOSE を受信した。
- 3 秒経過した。

切り替え中も event ID による既存の deduplicate を有効にする。generation が一致しない場合は最後の縮退更新を中止する。

接続失敗しても古い推奨リレーを永久に残さない。タイムアウト後は `next.readRelays + bootstrapRelays` へ収束させ、失敗は UI 状態へ残す。

### 16.8 タグ生成の設計（FR-03、FR-07）

#### 共通 builder

```kotlin
internal object ChannelEventTags {
    fun metadata(channelId: String, relayHint: String?, categories: List<String>): List<List<String>>

    fun rootMessage(channelId: String, relayHint: String?): List<List<String>>

    fun replyMessage(
        channelId: String,
        channelRelayHint: String?,
        parent: ReplyEventReference,
    ): List<List<String>>
}
```

relay hint が `null` の場合、空文字を含む 4 要素タグを作らない。

```kotlin
private fun markedEventTag(id: String, relayHint: String?, marker: String) =
    if (relayHint == null) listOf("e", id, "", marker)
    else listOf("e", id, relayHint, marker)
```

marker を第4要素に置くため、hint 不明時だけは NIP-10 互換上必要な空文字を第3要素へ置く。通常は `primaryHint` が存在するため空文字にならない。

#### 返信元リレー

`SubscriptionSignal.Event.relayUrl` を使い、受信した kind 42 の観測元を `messageSourceRelays[eventId]` に保持する。返信タグは次の優先順で hint を選ぶ。

1. `ReplyEventReference.relayUrl`
2. 対象メッセージを観測したリレーの先頭
3. `ChannelRelayContext.primaryHint`

`NostrEvent.toReplyTarget` に観測元を渡せる overload を追加する。

```kotlin
fun NostrEvent.toReplyTarget(
    noteContext: NoteContext,
    observedRelayUrl: String? = null,
): ReplyTarget?
```

`ReplyTarget.Channel.tags()` は channel relay hint も必要なため、`ReplyTarget.Channel` に `channelRelayUrl: String?` を追加する。

### 16.9 指定リレー投稿と結果伝播の設計（FR-06）

#### `SignedEventPublisher`

```kotlin
internal sealed interface SignedPublishResult {
    data class Published(
        val event: NostrEvent,
        val relayResult: RelayPublishResult,
    ) : SignedPublishResult
    data object MissingSigner : SignedPublishResult
    data class Failed(val cause: Throwable) : SignedPublishResult
}

internal suspend fun publish(
    content: String,
    kind: Int,
    tags: List<List<String>>,
    relayUrls: Collection<String>? = null,
): SignedPublishResult
```

- `relayUrls == null`: 従来どおり `NostrRepository.publish`。
- 指定あり: `publishToRelaysWithResult`。
- `succeededRelays.isEmpty()`: `Failed`。
- 1 件以上成功: 失敗を含んでいても `Published`。

#### 投稿 snapshot

`sendMessage()` の開始時に次を local val として固定する。

```kotlin
val publishContext = ChannelPublishContext(
    relayUrls = relayContext.writeRelays,
    primaryHint = relayContext.primaryHint,
    metadataSourceEventId = effectiveMetadata?.sourceEventId,
)
```

送信中に kind 41 が更新されても、その投稿のタグと送信先は変更しない。次の投稿から新しい context を使う。

#### UI 状態

```kotlin
data class ChannelPublishUiState(
    val phase: Phase,
    val targets: List<String>,
    val succeeded: Set<String> = emptySet(),
    val failed: Map<String, String> = emptyMap(),
) {
    enum class Phase { Idle, Sending, PartialSuccess, Success, Failed }
}
```

`UiState.Ready` の `isPosting` と `postError` は移行期間中に残してもよいが、最終的には `publishState` から導出する。

成功イベントは `ReactionEventStore.observe` とチャンネル履歴へ即時反映する。全失敗時は下書きを維持する。

### 16.10 kind 41 編集の設計（FR-03）

#### State

```kotlin
data class EditThreadDialogState(
    val title: String,
    val description: String,
    val picture: String,
    val relayDrafts: List<String>,
    val categories: List<String>,
    val validationErrors: Map<Field, String> = emptyMap(),
    val isSaving: Boolean = false,
    val error: String? = null,
)
```

初期値は必ず `effectiveMetadata.metadata` から作る。kind 40 の元値から作らない。

#### 保存処理

1. タイトルが空でないことを確認。
2. picture が空でなければ `https://` URL であることを確認。
3. relay drafts を正規化し、不正項目をフィールド単位で表示。
4. 完全な `ChannelMeta` JSON を生成。
5. `ChannelEventTags.metadata()` で root tag とカテゴリを生成。
6. `relayContext.writeRelays` へ kind 41 を投稿。
7. 1 件以上成功したら候補集合へローカルイベントを追加し resolver を実行。
8. 一部失敗の場合はダイアログを閉じ、警告を snackbar / 詳細へ残す。
9. 全失敗ならダイアログを維持する。

編集権限は UI 表示だけでなく `saveThreadMeta()` 冒頭でも `ownPubkey == channelOwnerPubkey` を再検証する。

### 16.11 kind 40 作成の設計（FR-04）

#### State と UI

`CreateDialogState` に次を追加する。

```kotlin
val picture: String = ""
val selectedRelays: Set<String> = emptySet()
val customRelayInput: String = ""
val publishState: ChannelPublishUiState = ChannelPublishUiState.Idle
```

`showCreateDialog()` 時に `RelayStore.writableRelayUrlsSnapshot()` を初期選択する。既存の単一選択 `RelaySelector` は流用せず、チェックボックス複数選択用の `RelayMultiSelector` を作る。

#### 作成トランザクション

厳密な分散トランザクションにはできないため、次の順序とする。

1. kind 40 を署名。
2. 選択推奨リレー + ユーザー write リレーへ kind 40 を投稿。
3. kind 40 が 1 件以上成功した場合だけ、任意の初回 kind 42 を署名・投稿。
4. 初回 kind 42 は kind 40 と同じ推奨リレーと relay hint を使用。
5. kind 40 成功・初回投稿失敗の場合もチャンネル作成自体は成功とし、本文を復元可能な警告として残す。
6. kind 40 が全失敗なら作成失敗としてダイアログを維持する。

ローカル一覧へ追加する際、kind 40 の成功リレー集合を観測元としてキャッシュし、`content.relays` を推奨リレーとして別保存する。

### 16.12 キャッシュと DB migration の設計（FR-12）

#### 概念分離

既存 `channel_relays` は「そのチャンネルを観測したリレー」を表すため、推奨リレーの保存に流用しない。次のテーブルを追加する。

```kotlin
@Entity(
    tableName = "channel_recommended_relays",
    primaryKeys = ["channelId", "relayUrl"],
    indices = [Index("channelId")],
)
data class CachedChannelRecommendedRelayEntity(
    val channelId: String,
    val relayUrl: String,
    val position: Int,
)
```

`channels` へ次の列を追加する。

```text
metadataEventId TEXT
metadataKind INTEGER NOT NULL DEFAULT 40
metadataCreatedAt INTEGER NOT NULL DEFAULT 0
```

`updatedAt` は互換のため残し、`metadataCreatedAt` と同じ値へ更新する。

#### Migration 6 → 7

1. `channels` に 3 列を追加。
2. 既存行は `metadataEventId = channelId`、`metadataKind = 40`、`metadataCreatedAt = updatedAt` で補完。
3. `channel_recommended_relays` を作成。
4. 既存 DB には content.relays が保存されていないため自動推測しない。次回ネットワーク取得時に埋める。

#### Store API

```kotlin
data class CachedChannelMetadata(
    val channelId: String,
    val ownerPubkey: String,
    val sourceEventId: String,
    val sourceKind: Int,
    val sourceCreatedAt: Long,
    val metadata: ChannelMeta,
)

suspend fun getChannelMetadata(channelId: String): CachedChannelMetadata?

suspend fun upsertChannelMetadata(
    channelCreateEvent: NostrEvent,
    effectiveEvent: NostrEvent,
    metadata: ChannelMeta,
    observedRelayUrl: String?,
)
```

推奨リレー置換はチャンネル行更新と同じ Room transaction 内で行う。

```text
upsert channels
delete recommended relays for channel
insert current normalized recommended relays with position
optional: upsert observed channel relay
```

kind 41 の event ID を channel ID として保存しないよう、API 引数で create event と effective event を明示的に分ける。

#### 観測元リレー

`SubscriptionSignal.Event.relayUrl` を `ChannelCacheStore.upsertMessage` へ渡し、既存 `channel_message_relays` に追記する。同じ event ID を別リレーで受信した場合、本体は `IGNORE`、relay 対応だけ追加される現行構造を維持する。

### 16.13 チャンネル画面 UI の設計（FR-09、FR-10、FR-11）

#### ViewModel state

`UiState.Ready` に次を追加する。

```kotlin
val effectiveMetadataSource: MetadataSource
val relayContext: ChannelRelayContext
val relayStates: Map<String, RelayConnectionState>
val publishState: ChannelPublishUiState
val isRelayTransitioning: Boolean
```

Composable は `NostrRepository.relayConnectionStates` を直接 collect せず、Controller が現在の context に必要な URL だけへ絞って state に載せる。これにより UI テストで repository singleton を必要としない。

#### ヘッダー

`AppTopBar.title` を 2 行構成にする。

- 1 行目: チャンネル名
- 2 行目: 推奨リレーの host 一覧、または `N relays`
- 切り替え中: 小さな progress indicator
- リレーなし: `ユーザーリレーを使用中`

2 行目をタップすると `ChannelRelayDetailsSheet` を開く。

#### 投稿欄

`AppMessageComposer` 自体へチャンネル固有仕様を入れず、`ChannelMessageInputBar` の上段に relay summary を追加する。

```text
投稿先: 2 relays                       [詳細]
[ メッセージを入力…                     ][送信]
```

送信後に一部失敗した場合は、本文入力欄の下へ `2件中1件に送信しました` と「詳細」を表示する。

#### 詳細 sheet

各 URL について次を表示する。

- 推奨 / fallback の区分
- read / write の使用有無
- 接続状態
- 直近投稿の成功 / 失敗と理由

接続状態は診断情報であり、ユーザーがリレー設定へ追加しない限り `RelayStore` 自体は変更しない。

#### チャンネル情報

既存の画面内 `ThreadInfoDialog` と一覧側 `ChannelDetailsDialog` で表示項目が重複しているため、`ChannelInfoContent` を共通 Composable として抽出する。イベント JSON 一覧は一覧側の診断画面だけに残す。

### 16.14 通常投稿画面からのチャンネル返信設計（FR-07）

現状 `PostViewModel.post()` は `relayUrls` を受け取れるが、`PostSheet` の初期値はユーザーの通常 write リレーである。次を追加する。

```kotlin
data class ComposerRelayContext(
    val initialRelayUrls: Set<String>,
    val recommendedRelayUrls: Set<String>,
    val primaryHint: String?,
)
```

`ComposerCoordinator` に `replyRelayContext` を保持し、`prepareReply` で設定する。チャンネル画面から返信を開く場合は、現在の `ChannelRelayContext` と対象メッセージの観測元を渡す。

`PostSheet` の初回 composition では次を使用する。

```kotlin
postRelayUrls = composerRelayContext?.initialRelayUrls
    ?: RelayStore.writableRelayUrlsSnapshot().toSet()
```

ユーザーは送信先を追加・削除できるが、推奨リレーをすべて外した場合は確認用の警告を表示する。投稿を完全には禁止しない。

返信タグ生成では `ComposerRelayContext.primaryHint` を channel root tag に使い、parent の観測元を reply/p tag に使う。

チャンネル外のスレッド画面から返信を開き、relay context がメモリ上にない場合は `ChannelCacheStore.getChannelMetadata(channelId)` から復元する。それもない場合は親イベントの relay hint と通常 write リレーへフォールバックする。

### 16.15 kind 43 Hide Message の設計

#### 既存 kind 5 との区別

- kind 5: 自分が発行したイベントの削除要求。
- kind 43: 自分のクライアント表示から任意の kind 42 を隠す NIP-28 操作。

メニューは次のように分ける。

- 自分の投稿: `削除要求を送信`
- すべての投稿: `このメッセージを非表示`

#### 発行

```json
{
  "kind": 43,
  "content": "{\"reason\":\"\"}",
  "tags": [
    ["e", "<kind42-id>", "<relay-hint>"]
  ]
}
```

チャンネルの write relay context へ送る。1 件以上成功したら `hiddenMessageIds` に即時追加する。

#### 受信と表示

- 自分の pubkey が発行した kind 43 だけをデフォルトで適用する。
- 対象 ID が現在のチャンネルの kind 42 であることを確認する。
- 他人が発行した kind 43 は将来のモデレーションポリシー用に受信しても、初期実装では表示へ反映しない。
- `filteredMessages()` で `hiddenMessageIds` を除外する。

ローカルで即時反映したイベントは、全失敗時だけ rollback する。

### 16.16 kind 44 Mute User の設計

#### 方針

kind 44 は NIP-28 の公開イベント、既存 `MuteStore` はアカウントのミュート状態であり、意味と配送範囲が異なる。初期実装では同じ UI 操作から両方を更新しない。

チャンネルメニューに次を分けて表示する。

- `このチャンネルで非表示（NIP-28）`: kind 44
- `ユーザーをミュート`: 既存 `MuteStore`

#### 発行と適用

```json
{
  "kind": 44,
  "content": "{\"reason\":\"\"}",
  "tags": [
    ["p", "<target-pubkey>", "<relay-hint>"]
  ]
}
```

- 自分が発行した kind 44 の対象 pubkey を `channelMutedPubkeys` として保持する。
- 初期実装では channel ID をイベント自体から限定できない NIP-28 の性質上、自分の kind 44 は全 NIP-28 チャンネルへ適用する。
- この広い影響範囲を確認ダイアログに明示する。
- 既存 `MuteStore.mutedPubkeys` と union して `filteredMessages()` へ適用する。

解除仕様が NIP-28 に明確でないため、初期リリースではローカル非表示解除だけを提供するか、kind 44 の削除要求を別仕様で定める。実装前にプロダクト判断を行う。

### 16.17 kind 10005 参加チャンネル同期の設計

#### 新規 repository

```kotlin
internal class JoinedChannelRepository(
    private val accountSession: AccountSession,
) {
    val channelIds: StateFlow<Set<String>>
    suspend fun join(channelId: String): Result<Unit>
    suspend fun leave(channelId: String): Result<Unit>
    suspend fun refresh(): Result<Unit>
}
```

kind 10005 は replaceable event なので、追加・削除のたびに現在の完全な `e` タグ集合を再発行する。

#### 競合制御

- `Mutex` で join/leave を直列化する。
- 書き込み直前に最新 kind 10005 を再取得する。
- 最新判定は `(createdAt, id)`。
- 同一端末の連続更新では `createdAt` を前回より最低 1 秒進める。
- 他端末との同時編集は replaceable event の last-write-wins になるため、直前再取得で損失確率を下げる。

#### ローカル状態との統合

- kind 10005: 参加状態の同期元。
- `isFavorite`: ToriNos 固有の端末ローカル表示設定として維持。
- キャッシュ行の存在: 閲覧履歴であり参加を意味しない。

この3つを同一フラグに統合しない。

### 16.18 リレー接続状態の設計

`relayConnectionStates` は現在 `activeRelays` だけを対象としている。`Explicit` を通常の active relay 管理へ統合することで推奨リレーも自動的に公開対象になる。

UI 用には次の表示状態へ変換する。

```kotlin
sealed interface ChannelRelayStatus {
    data object Connecting : ChannelRelayStatus
    data object Connected : ChannelRelayStatus
    data class Unavailable(val reason: String) : ChannelRelayStatus
    data object Disconnected : ChannelRelayStatus
}
```

`SubscriptionSignal.Closed` と `RelayUnavailable` の理由を Controller が URL ごとに保持し、単なる `Disconnected` と区別する。再接続で EVENT / EOSE / Connected を受けたらエラー理由を消す。

### 16.19 Controller の状態遷移

```text
Bootstrapping
  ├─ cache hit  → Ready(stale=true) → ResolvingMetadata
  └─ cache miss → ResolvingMetadata

ResolvingMetadata
  ├─ valid kind40 → Ready + ResolveKind41
  ├─ timeout + cache → Ready(stale=true, warning)
  └─ timeout no data → Error(retryable)

Ready
  ├─ newer valid kind41 → TransitioningRelays → Ready
  ├─ send → Publishing → Ready / PartialSuccess / PublishError
  └─ close → Closed
```

既存 `UiState.Loading` / `Ready` を全面置換する必要はない。内部状態を上記のように持ち、UI へは `Ready` の補助フィールドとして段階導入する。

### 16.20 並行処理とキャンセル

- metadata、message、related-events の各 session は `ChannelController.close()` で `NonCancellable` を使って確実に close する。
- kind 41 の更新処理は `Mutex` または単一 collector 内で直列化する。
- relay transition job は新しい generation の開始時に cancel する。
- publish は開始時 snapshot を使うため transition job とロックを共有しない。
- DB 書き込み失敗はネットワーク表示を止めず、ログに残す。
- ネットワーク購読失敗はキャッシュ表示を消さない。
- `CancellationException` は既存方針どおり必ず再送出する。

### 16.21 ログと診断

本文、秘密鍵、署名前データはログへ出さない。次の構造化情報だけを記録する。

- channel ID の先頭 8 文字
- metadata source kind / event ID の先頭 8 文字
- 推奨、read、write リレー件数
- relay transition generation
- publish の成功数 / 失敗数
- kind 41 を無視した理由コード
- URL 正規化で除外した件数（生の不正 URL は出さない）

無視理由は enum とする。

```kotlin
enum class MetadataRejectReason {
    WrongKind,
    WrongChannel,
    WrongAuthor,
    InvalidMarker,
    InvalidContent,
    OlderThanEffective,
}
```

## 17. 実装単位と依存関係

### 17.1 PR 1: モデルと純粋ロジック

対象:

- URL 正規化
- `ChannelMeta.relays`
- `ChannelMetadataResolver`
- `ChannelRelayContextBuilder`
- `ChannelEventTags`
- 単体テスト

この PR ではネットワーク動作と UI を変えない。

### 17.2 PR 2: 明示購読とキャッシュ

対象:

- `RelayTarget.Explicit` の意味修正
- active relay への未登録 URL 統合
- DB migration 6 → 7
- `ChannelCacheStore` の metadata API
- repository / migration テスト

PR 1 に依存する。

### 17.3 PR 3: チャンネル購読

対象:

- `ChannelController` の session 化
- metadata resolver の接続
- recommended relay の kind 42 購読
- kind 41 更新時の二段階再購読
- 観測元リレーのキャッシュ
- Controller 結合テスト

PR 1、PR 2 に依存する。

### 17.4 PR 4: チャンネル投稿

対象:

- `SignedEventPublisher` の結果伝播
- root kind 42 の relay hint
- 指定 write relay への投稿
- 一部成功 / 全失敗状態
- kind 40 作成と初回 kind 42
- kind 41 編集

PR 1、PR 2 に依存し、PR 3 と同時または後に実施する。

### 17.5 PR 5: UI と返信連携

対象:

- ヘッダーの閲覧先表示
- 投稿欄の投稿先表示
- relay details sheet
- チャンネル情報共通 UI
- `ComposerCoordinator` / `PostSheet` の初期投稿先
- チャンネル返信タグの relay hint
- UI テスト

PR 3、PR 4 に依存する。

### 17.6 PR 6: NIP-28 モデレーション

対象:

- kind 43
- kind 44
- 既存 kind 5 / `MuteStore` との UI 分離
- フィルタリングと rollback テスト

PR 3、PR 4 に依存する。

### 17.7 PR 7: 参加チャンネル同期

対象:

- `JoinedChannelRepository`
- kind 10005 の取得・発行
- join / leave UI
- 競合・replaceable event テスト

他の PR から独立性は高いが、チャンネル情報画面を再利用するため PR 5 後を推奨する。

## 18. 詳細テストケース

### 18.1 Metadata resolver

| ケース | 期待結果 |
| --- | --- |
| kind 40 のみ | kind 40 を採用 |
| 所有者の kind 41 が複数 | `(createdAt, id)` 最大を採用 |
| 他人の新しい kind 41 | 無視 |
| `reply` marker の kind 41 | 無視 |
| marker なしの旧 kind 41 | 互換モードで採用 |
| 最新 kind 41 の JSON 不正 | 直前の有効 kind 41 または kind 40 を採用 |
| リレーごとに到着順が逆 | 最終結果が同一 |

### 18.2 Relay context

| 推奨 | navigation | user write | 期待される write / hint |
| --- | --- | --- | --- |
| A, B | C | D | A, B, D / A |
| 空 | C | D | D / C |
| 空 | なし | D | D / D |
| A（read-only設定） | C | D | D / A |
| A（未登録） | C | D | A, D / A |

primary hint と実際の write 対象は一致しない場合がある。read-only の A は配送先にはしないが、既存イベントの所在を示す hint としては使用できる。

### 18.3 Dynamic subscription

1. A/B 購読中に kind 41 で B/C へ変更。
2. 一時的に A/B/C を購読。
3. C 接続または timeout 後に B/C へ収束。
4. 移行中に B から同一 event、C から同一 event を受信。
5. UI と DB に 1 件だけ表示し、観測元は B/C の 2 件を保持。

### 18.4 Publish

| 結果 | 入力欄 | ローカル表示 | UI |
| --- | --- | --- | --- |
| 全成功 | クリア | 即時追加 | 成功 |
| 一部成功 | クリア | 即時追加 | 警告と失敗 URL |
| 全失敗 | 維持 | 追加しない | 再試行可能なエラー |
| publish 中に kind 41 更新 | 成功時クリア | 1 件追加 | 開始時の送信先結果を表示 |

### 18.5 Database migration

- v6 の全テーブルとデータを用意して v7 へ migration する。
- チャンネル、お気に入り、既読位置、メッセージ、観測元リレーが維持される。
- metadata source が kind 40 として補完される。
- recommended relay は空で始まる。
- v7 で kind 41 を保存後、再起動して同じ実効メタデータと順序付き推奨リレーを復元できる。

## 19. 未決事項

実装着手前に次を確定する。

1. `writeRelays` にユーザーの全 write リレーを常に加えるか、推奨リレーが存在するときは推奨だけに限定するか。本仕様の初期値は併用。
2. kind 44 の解除をローカルだけにするか、kind 5 による削除要求まで行うか。
3. kind 43/44 をチャンネル一覧の selected relay だけへ送るか、channel write context 全体へ送るか。本仕様の初期値は全体。
4. 推奨リレー最大件数 10 を固定値とするか設定可能にするか。
5. relay transition の待機条件を Connected のみとするか、EOSE / EVENT も成功扱いにするか。本仕様はすべてを成功シグナルとする。
6. チャンネル作成時に推奨リレーを 0 件で許可するか。本仕様は警告付きで許可。

## 20. 反復実装プロセス

### 20.1 ループの目的

実装は大きな一括変更にせず、1ループごとに設計・コード・テスト結果を確定する。各ループで得た知見を本設計書へ反映してから、次のループへ進む。

```text
対象範囲を選定
    ↓
設計レビュー
    ↓
実装
    ↓
実装レビュー
    ↓
自動テスト
    ↓
iOS Simulatorテスト
    ↓
結果・課題を設計書へ反映
    ↓
次ループの範囲を再決定
```

### 20.2 1ループの完了条件

次をすべて満たしたときだけ、当該ループを `完了` とする。

- [ ] ループの対象と対象外が明記されている。
- [ ] 設計レビューの指摘が解消済み、または明示的に次ループへ送られている。
- [ ] 対象コードが実装されている。
- [ ] 実装レビューで correctness、並行処理、ライフサイクル、互換性、性能を確認している。
- [ ] 対象の単体・結合テストが成功している。
- [ ] iOS Simulator で定義済みシナリオを実施している。
- [ ] テスト結果と環境が本設計書のループ記録へ追記されている。
- [ ] 発見した仕様変更・追加課題が要件、設計、次ループ計画へ反映されている。
- [ ] 既知の重大不具合が残っていない。

コンパイル成功だけではループ完了としない。シミュレーターテストを実行できない場合は `保留` とし、理由と未確認項目を記録する。

### 20.3 レビュー観点

#### 設計レビュー

- NIP-28/10/51 のイベント形式と矛盾しないか。
- 既存の `NostrRepository`、キャッシュ、アカウントセッションの責務と重複しないか。
- 正常系、部分成功、全失敗、タイムアウト、キャンセルが定義されているか。
- イベント到着順やリレー接続順に依存しない決定規則になっているか。
- DB migration と既存データの扱いが定義されているか。
- UIに必要な情報が ViewModel state として供給され、Composable がロジックを持ちすぎていないか。
- 未登録の外部リレーへ接続する場合の上限とライフサイクルがあるか。

#### 実装レビュー

- `CancellationException` を握りつぶしていないか。
- subscription、Job、一時状態が画面終了時に解放されるか。
- kind 41 の所有者検証と最新判定が全経路で共通化されているか。
- URLが正規化前の文字列で比較・キャッシュされていないか。
- 投稿開始後に relay context が変化しても、イベントと配送先のsnapshotが崩れないか。
- event IDの重複排除と観測元リレーの追加保存を両立しているか。
- 一部成功と全失敗を混同していないか。
- 既存の通常フィード、返信、リレー設定に回帰がないか。
- ユーザーの未コミット変更を意図せず削除・上書きしていないか。

レビュー指摘は次の重大度で記録する。

| 重大度 | 定義 | ループ完了可否 |
| --- | --- | --- |
| Blocker | データ破損、クラッシュ、誤署名・誤配送、migration失敗 | 不可 |
| Major | 主要要件未達、購読漏れ、全失敗の誤判定、再現性の高いUI不具合 | 不可 |
| Minor | 代替操作がある表示・診断・性能上の問題 | 次ループ送りを明記すれば可 |
| Note | 改善提案、将来のリファクタ候補 | 可 |

### 20.4 自動テストの順序

各ループで変更範囲に応じて次の順で実行する。

1. 対象に近い `commonTest`。
2. チャンネル・ネットワーク関連の `commonTest`。
3. `./gradlew` による対象モジュールのコンパイル。
4. Android/iOS固有コードを変更した場合は該当targetのコンパイル。
5. migration変更時はschema検証とmigrationテスト。

失敗した場合は、テストを削除・弱体化して通さない。設計誤り、実装不具合、既存テストの前提変更のどれかを分類し、修正理由をループ記録へ残す。

### 20.5 iOS Simulator テスト方針

テストは実機相当のUI・ライフサイクル確認として iOS Simulator で実施する。各ループで使用した環境を必ず記録する。

記録項目:

- macOS / Xcode バージョン
- Simulator機種
- iOSバージョン
- Debug/Release
- テストアカウントまたは匿名状態
- 選択リレー
- 対象チャンネルID
- ネットワーク条件
- 実行日時

共通確認:

1. アプリ起動とチャンネル一覧表示。
2. チャンネルを開く、戻る、再度開く。
3. バックグラウンド化・復帰。
4. 画面回転または利用可能なウィンドウサイズ変更。
5. ネットワーク切断・復帰、または接続不能リレーを含む状態。
6. 一部成功・全失敗を発生させられる場合の投稿結果。
7. アプリ再起動後のキャッシュ・メタデータ・既読位置復元。
8. ログ上の例外、購読リーク、無限再接続がないこと。

自動UI操作が安定して行える範囲は自動化し、リレー応答など外部状態へ依存する箇所は、取得したイベントID・表示・ログを証跡として記録する。

### 20.6 ループ記録フォーマット

各ループは本節の末尾へ次の形式で追記する。

```markdown
### Loop N: タイトル

- 状態: 計画中 / 実装中 / レビュー中 / テスト中 / 保留 / 完了
- 対象:
- 対象外:
- 開始時commit:
- 対象ファイル:

#### 設計レビュー

- 指摘:
- 対応:

#### 実装レビュー

- 指摘:
- 対応:

#### 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |

#### Simulatorテスト

| シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- |

#### 設計書へのフィードバック

- 確定した仕様:
- 変更した仕様:
- 次ループへ送る課題:
```

### 20.7 実装ループ計画

| Loop | 対象 | 完了時の成果 | 状態 |
| --- | --- | --- | --- |
| 0 | 現在の未コミット差分の安定化 | チャンネル詳細、一覧UI、DB v6、キャッシュ改善のレビュー・Simulator確認 | 保留（外部送信・障害注入テスト待ち） |
| 1 | モデルと純粋ロジック | URL正規化、`ChannelMeta.relays`、metadata resolver、relay context、タグbuilder | 完了 |
| 2 | 明示購読とDB v7 | 未登録推奨リレー購読、metadata cache、migration 6→7 | 完了 |
| 3 | チャンネル購読 | 推奨リレーからkind 42取得、kind 41更新時の再購読 | 着手中(3a完了、3bは未着手) |
| 4 | チャンネル投稿 | kind 40/41/42の配送、relay hint、リレー別結果 | 未着手 |
| 5 | UIと返信連携 | ヘッダー、投稿先、詳細、編集、通常返信画面 | 未着手 |
| 6 | モデレーション | kind 43/44 | 未着手 |
| 7 | 参加同期 | kind 10005 | 未着手 |

依存関係やレビュー結果によってループを分割・統合してよい。ただし、変更した理由を直前ループのフィードバックへ記録する。

## 21. Loop 0 計画: 現在差分の安定化

### 21.1 対象

- `ChannelDetailsDialog` と一覧からの導線
- kind 40/41 の診断取得
- 共通 `RelaySelector`
- チャンネル履歴の一括キャッシュ書き込み
- Room schema v6 / migration 5→6
- チャンネル単位・全体単位のprune
- kind 5の `k` タグ
- `ChannelMessageReducer` 削除後の重複排除回帰

NIP-28推奨リレーの動的購読・投稿は Loop 0 には含めない。

### 21.2 設計レビューで確認する項目

- 詳細画面が所有者以外のkind 41を表示対象にしない。
- kind 40/41取得の有限購読がダイアログ破棄後に状態を更新しない。
- 同一 `createdAt` のkind 41表示が決定的になる。
- 詳細画面の独自メタデータ型が後続resolver導入時に置換可能である。
- 履歴ページ保存が1トランザクションになり、個別保存と同じ観測元情報を保持する。
- prune後に孤立したmessage-relay行、message行、channel行が残らない。
- migration 5→6で既存メッセージ・既読・お気に入りが維持される。
- reducer削除後も `ChannelHistory` / subscription session で重複排除される。

### 21.3 Simulatorテストケース

| ID | シナリオ | 期待結果 |
| --- | --- | --- |
| L0-S01 | アプリを起動してチャンネル一覧を開く | クラッシュせず、選択リレー・一覧・お気に入り・unread badgeが表示される |
| L0-S02 | リレー選択メニューを開き、別の有効リレーへ切り替える | 接続状態付き一覧が表示され、選択後にチャンネル一覧が更新される |
| L0-S03 | チャンネル行のメニューから詳細を開く | kind 40と取得済みkind 41、現在のメタデータが表示される |
| L0-S04 | `content.relays` を持つチャンネル詳細を開く | 推奨リレー一覧が診断表示される |
| L0-S05 | 詳細取得中に画面を閉じる | 後着イベントで閉じたダイアログが再表示されず、例外が出ない |
| L0-S06 | 同じチャンネルを開閉し、過去履歴を追加取得する | 重複投稿が表示されず、順序とスクロール位置が安定する |
| L0-S07 | アプリを終了・再起動して同じチャンネルを開く | キャッシュされた投稿・既読位置・お気に入りが復元される |
| L0-S08 | 自分が作成したkind 40チャンネルへ削除要求を送る | 一覧から消え、送信イベントに `e` と `k=40` が含まれる |
| L0-S09 | バックグラウンド化して復帰する | 購読再開後に重複せず、新着を受信できる |
| L0-S10 | 接続不能または応答しないリレーを選択する | UIが固まらず、タイムアウトまたはエラー表示から戻れる |

### 21.4 Loop 0 完了後の判断

Loop 0 で Blocker / Major がなければ Loop 1 へ進む。Loop 0 の差分にNIP-28 relay contextの変更を混在させず、ベースラインの挙動を先に確定する。

Loop 0 で判明した詳細表示・キャッシュ・購読の問題が Loop 1以降の設計へ影響する場合は、該当する第16章の設計を更新してから次の実装を開始する。

## 22. Loop 0 実施記録: 現在差分の安定化

- 状態: 保留
- 対象: 第21.1節の現在差分、特にチャンネル詳細のkind 40/41取得と表示
- 対象外: NIP-28推奨リレーの動的購読・投稿、DB v7、kind 43/44、kind 10005
- 開始時commit: `46d796b`
- 対象ファイル:
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/channel/ChannelListViewModel.kt`
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/channel/ChannelDetailsDialog.kt`
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/channel/ChannelMetadataSelection.kt`
  - `composeApp/src/commonTest/kotlin/com/nostr/torinos/ui/channel/ChannelMetadataSelectionTest.kt`

### 22.1 設計レビュー

| 重大度 | 指摘 | 対応 |
| --- | --- | --- |
| Major | 詳細を閉じた後、または同一チャンネルを閉じて再表示した後に、以前の有限購読が新しいダイアログを更新できる | 詳細取得用 `Job` を保持し、再表示・dismiss・`onCleared` でキャンセルする |
| Major | kind 41の `created_at` が同じ場合、受信順によって表示内容が変わる | `(createdAt, eventId)` の降順で決定し、同時刻でも結果を固定する |
| Major | 新しいkind 41のJSONが不正な場合に、正常な過去メタデータより不正イベントが優先される | `toChannelMeta()` で解釈できるイベントだけを候補にして最新を選ぶ |
| Minor | kind 41の `e` tag markerを検証しておらず、`reply` など明示的な非root参照を採用し得る | markerなしの旧形式、空文字、`root` のみを許可する |
| Note | 元のL0-S08はkind 42削除と記載していたが、Loop 0のチャンネル一覧削除はkind 40が対象 | シナリオをkind 40、`k=40` に訂正した |

### 22.2 実装レビュー

- kind 41はチャンネル作成者のpubkey、対象channel ID、root markerを満たす場合だけ詳細stateへ追加する。
- 詳細取得Jobは再表示、dismiss、ViewModel破棄の各経路でキャンセルされる。`CancellationException` は `SafeCoroutineLauncher` から再送出されるため、通常例外として握りつぶさない。
- 有効メタデータ選択は純粋関数へ分離し、同時刻tie-break、kind 40 fallback、不正JSON除外を単体テスト可能にした。
- `effectiveChannelMetadataEvent` は、呼出し前に `isChannelMetadataFor` で所有者と対象チャンネルを検証済みであることを前提とする。Loop 1ではこの前提をUIローカル関数から共通metadata resolverへ統合する。
- 今回のレビュー範囲ではBlocker / 未解消Majorはない。

### 22.3 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test --tests 'com.nostr.torinos.ui.channel.ChannelMetadataSelectionTest'` | 成功 | 5ケース |
| `./gradlew :composeApp:iosSimulatorArm64Test --tests 'com.nostr.torinos.ui.channel.*'` | 成功 | チャンネル関連commonTest |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功 | iOS Simulator Arm64の全テスト |
| `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=D5D20B75-99E2-45AF-A1E7-848C6E1E6E76' -derivedDataPath /private/tmp/ToriNosDerivedData build` | 成功 | ICUのdeployment target警告のみ |
| `git diff --check` | 成功 | whitespace errorなし |

### 22.4 Simulatorテスト

実施環境:

- 実行日時: 2026-09-23 18:40-18:56 JST
- macOS: 26.5 (25F71)
- Xcode: 26.6 (17F113)
- Simulator: iPhone 17 / iOS 26.5
- build: Debug
- アカウント: 既存の署名可能アカウント
- 選択リレー: `wss://yabu.me/` から `wss://r.kojira.io/` へ切替
- 対象チャンネル: `さびれたスナック`
- ネットワーク条件: 通常接続

| シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- |
| L0-S01 | 成功 | 一覧、選択リレー、お気に入り操作、未読badgeを表示 |
| L0-S02 | 成功 | 接続状態付きメニューを表示し、`r.kojira.io` への切替後に一覧を更新 |
| L0-S03 | 成功 | 詳細にkind 40、最新kind 41、現在メタデータを表示 |
| L0-S04 | 成功 | `wss://yabu.me/` と `wss://relay-jp.nostr.wirednet.jp/` の推奨リレー2件を表示 |
| L0-S05 | 成功 | 詳細を取得中に閉じて再表示しても、閉じたダイアログの再出現やクラッシュなし |
| L0-S06 | 成功 | 同じチャンネルを開閉し、表示順の乱れと目視できる重複なし |
| L0-S07 | 成功 | process終了・再起動後、チャンネル画面で `r.kojira.io`、一覧、未読件数、キャッシュを復元。アプリ全体の開始画面はホーム |
| L0-S08 | 未実施 | 実リレーへのkind 5送信と実チャンネル削除を伴うため、明示確認なしでは実行しない。コード上は `e` と `k=40` を確認 |
| L0-S09 | 成功 | Homeへ移動後に復帰し、同一チャンネル・表示内容を維持。クラッシュと目視できる重複なし |
| L0-S10 | 未実施 | 登録済み5リレーがすべて接続済み。設定変更またはネットワーク障害注入が必要 |

### 22.5 設計書へのフィードバック

- 確定した仕様:
  - kind 41の有効性は所有者、対象channel ID、root marker、JSON解釈可能性で判定する。
  - 最新判定は `(createdAt, eventId)` で決定的に行う。
  - 詳細取得はダイアログのライフサイクルに束縛する。
- 変更した仕様:
  - L0-S08の対象をkind 42メッセージからkind 40チャンネル削除へ訂正した。
- 次ループへ送る課題:
  - Loop 1でUIローカルのmetadata選択関数を共通resolverへ移し、全経路で同じ検証規則を使う。
  - L0-S08はテスト専用チャンネルまたはpublish fakeを用意して、副作用を隔離して確認する。
  - L0-S10はテスト専用の接続不能リレー、またはネットワーク障害注入手順を用意して確認する。

Loop 0は実装と通常接続下の回帰確認を完了したが、L0-S08とL0-S10が未実施のため、第20.2節の定義に従い状態を `保留` とする。未解消のBlocker / Majorはなく、外部副作用を伴わないLoop 1の純粋ロジック設計・実装は並行して開始できる。

## 23. Loop 1 実施記録: モデルと純粋ロジック

- 状態: 完了
- 対象: URL正規化(FR-01)、`ChannelMeta.relays`、`ChannelMetadataResolver`(FR-02)、`ChannelRelayContextBuilder`(FR-05/FR-06)、`ChannelEventTags`(FR-03/FR-07)の各純粋ロジックと単体テスト
- 対象外: ネットワーク層・UI層への配線(PR2以降)、DB v7、kind 43/44/10005
- 開始時commit: `46d796b`(未コミット差分としてLoop 1対象ファイルが既に作業ツリーに存在していたため、そのレビューと不具合修正を本ループの実施内容とした)
- 対象ファイル:
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/model/ChannelMeta.kt`
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/model/ChannelRelayContext.kt`
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/model/ChannelEventTags.kt`
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/network/RelayStore.kt`
  - `composeApp/src/commonTest/kotlin/com/nostr/torinos/model/ChannelMetadataResolverTest.kt`
  - `composeApp/src/commonTest/kotlin/com/nostr/torinos/model/ChannelRelayContextTest.kt`
  - `composeApp/src/commonTest/kotlin/com/nostr/torinos/model/ChannelEventTagsTest.kt`
  - `composeApp/src/commonTest/kotlin/com/nostr/torinos/network/RelayUrlNormalizationTest.kt`

### 設計レビュー

| 重大度 | 指摘 | 対応 |
| --- | --- | --- |
| Major | `normalizeRelayUrl`がFR-01「query、fragmentを含むURLは採用しない」に反し、`?`以降をpathの一部として取り込んでいた(例: `wss://relay.example/path?foo=bar`が正規化・採用されていた) | 正規表現マッチ後に`suffix`へ`'?'`が含まれる場合`null`を返すよう修正し、回帰テストを追加した |
| Minor | 第16.3節の規則6「既定ポート`ws:80`/`wss:443`は省略する」が未実装で、既存テストは逆に既定ポートを保持する値を期待していた | `normalizeRelayAuthority`にscheme別の既定ポート省略処理を追加し、既存テストの期待値を訂正、専用テストを追加した |
| Note | `ChannelMetadataResolver`のmarker検証・tie-break(`(createdAt, id)`降順、同時刻はid最小勝ち)は設計どおりで、既存テストが受信順不定性・不正JSON・非rootマーカーの各ケースを網羅している | 対応不要。第16.4節の設計との差異なし |

### 実装レビュー

- `normalizeRelayUrl`/`normalizeRelayUrls`のシグネチャは維持し、内部の`normalizeRelayAuthority`にのみ`scheme`引数を追加した。呼び出し元は1箇所のみで影響範囲は限定的。
- `ChannelRelayContextBuilder`のwrite集合は「ブロック対象(NIP-65 write=false)がrecommended上限枠を消費しない」設計どおりに、正規化→ブロック除外→上限適用の順で処理されている。
- `ChannelEventTags`のrelay hint省略時は`""`を第3要素に置きmarkerの位置を保つ実装で、FR-07の例と一致する。
- `CancellationException`を握りつぶす変更は行っていない(対象が純粋関数のみのため該当なし)。
- Blocker / 未解消Majorはなし。

### 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test --tests 'com.nostr.torinos.network.RelayUrlNormalizationTest' --tests 'com.nostr.torinos.model.ChannelMetadataResolverTest' --tests 'com.nostr.torinos.model.ChannelRelayContextTest' --tests 'com.nostr.torinos.model.ChannelEventTagsTest' --tests 'com.nostr.torinos.network.RelayInformationRepositoryTest'` | 成功 | 22ケース(修正で2ケース追加) |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功 | commonTest全体で回帰なし |
| `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=D5D20B75-99E2-45AF-A1E7-848C6E1E6E76' -derivedDataPath /private/tmp/ToriNosDerivedData build`(DerivedData削除後のクリーンビルド) | 成功 | `ComposeApp.framework`のビルド時刻がRelayStore.kt編集後であることを確認し、Kotlin変更の反映を検証 |

### Simulatorテスト

本ループはネットワーク・UIへ配線しない純粋ロジック層のみが対象(PR1相当)のため、第21章のような機能シナリオは定義しない。起動可否とリレー関連コードパスの回帰有無のみをスモーク確認した。

実施環境:

- 実行日時: 2026-09-23 21:38-21:40 JST
- macOS: 26.5 (25F71)
- Xcode: 26.6 (17F113)
- Simulator: iPhone 17 / iOS 26.5
- build: Debug
- アカウント: 既存の署名可能アカウント

| シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- |
| アプリ起動、フォロー/グローバルフィード表示 | 成功 | クラッシュなし、`normalizeRelayUrl`経由のリレー接続・フィード取得に回帰なし(スクリーンショットで確認) |

実データ環境([[ios-sim-cliclick-real-relay-risk]]参照)でのリアクション誤爆を避けるため、フィード内投稿へのcliclickタップ操作は行わず、起動確認とプロセス生存確認に留めた。チャンネル画面固有のUIシナリオはLoop 5(UI連携)まで対象外。

### 設計書へのフィードバック

- 確定した仕様:
  - FR-01の既定ポート省略規則(`ws:80`/`wss:443`)をコードへ反映し、テストで固定した。
  - FR-01のquery/fragment拒否規則の欠落を修正し、テストで固定した。
- 変更した仕様: なし(第16.3/16.4/16.5/16.8節の記載どおりに実装を訂正したのみ)
- 次ループへ送る課題:
  - Loop 2で`RelayTarget.Explicit`の意味変更とDB v7 migrationに着手する際、本ループで確定した`normalizeRelayUrl`(既定ポート省略込み)を保存済み設定の読み込み経路でも一貫利用できているか再確認する。
  - Loop 0のL0-S08(kind 40削除の実リレー送信確認)とL0-S10(接続不能リレーでのUI確認)は引き続き未実施であり、保留のまま。

## 24. Loop 2 実施記録: 明示購読とDB v7

- 状態: 完了
- 対象: `RelayTarget.Explicit`の意味修正(FR-05基盤)、DB migration 6→7(`channel_recommended_relays`、`channels`への`metadataEventId`/`metadataKind`/`metadataCreatedAt`追加)、`ChannelCacheStore`の`getChannelMetadata`/`upsertChannelMetadata` API(第16.6節・第16.12節)
- 対象外: `ChannelController`のsession化・resolver接続、既存3箇所の`ChannelCacheStore.upsertChannel`呼び出し元の付け替え(Loop 3で resolver 出力に合わせて置換)、kind 42購読・投稿への反映(Loop 3/4)
- 開始時commit: `25156a9`
- 対象ファイル:
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/network/NostrRepository.kt`
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/network/ChannelCacheStore.kt`
  - `composeApp/src/mobileMain/kotlin/com/nostr/torinos/network/ChannelCacheStore.mobile.kt`
  - `composeApp/src/mobileMain/kotlin/com/nostr/torinos/network/cache/ChannelCacheDatabase.kt`
  - `composeApp/src/mobileMain/kotlin/com/nostr/torinos/network/cache/ChannelCacheDatabaseBuilder.kt`
  - `composeApp/src/commonTest/kotlin/com/nostr/torinos/network/RelayTargetUrlsTest.kt`

### 設計レビュー

| 重大度 | 指摘 | 対応 |
| --- | --- | --- |
| Note | 第16.6節は「`Explicit`の既存呼び出し箇所は意味変更の影響を監査する」ことを要求している | `FeedController`(engagement history再取得)と`ProfileRepository`(primary relay投稿)の既存呼び出しを確認した。いずれも渡す`relayUrls`が事前に`NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)`由来のenabled relay部分集合であり、意味変更後も返り値は変わらない。追加の積集合処理は不要と判断した |
| Note | `desiredActiveRelayUrlsLocked()`/`reconcileActiveRelaysLocked()`が`activeSubscriptions`全体のtarget集合の和で接続要否を決めるため、`Explicit`が未登録URLを含められるようになるだけで、FR-05の「一時接続」「画面終了時の専用購読解放」「他機能が使用中の接続を切断しない」は既存の参照カウント相当の仕組みでそのまま満たされる(第16.6節の設計どおり) | 対応不要。新規セッション管理クラスは追加しなかった |
| Minor | 第16.12節のMigration規則で`updatedAt`は「互換のため残し、`metadataCreatedAt`と同じ値へ更新する」とあるが、既存`createdAt`(チャンネル作成時刻)の扱いが未記載だった | `upsertChannelMetadata`では`createdAt`を`channelCreateEvent.createdAt`(kind 40由来、event idに対して不変)に固定し、`updatedAt`のみ`metadataCreatedAt`と同期させる設計とした |

### 実装レビュー

- `RelayTarget.urls()`の可視性を`private`から`internal`へ変更したのみで、シグネチャ・呼び出し箇所(8箇所、すべて`NostrRepository.kt`内)は変更していない。プロジェクトはexplicit API modeを使用していないため、可視性変更によるビルド設定への影響はない。
- `RelayTarget.Explicit`の正規化は`normalizeRelayUrls(urls, limit = Int.MAX_VALUE)`とし、チャンネル推奨リレーの上限10件(第11章)は`ChannelRelayContextBuilder`側で既に適用済みのため、ここでは二重に切り詰めない。
- `upsertChannelMetadata`のDB書き込みは`@Transaction`で「channels行更新→recommended relays全削除→再挿入→(任意)観測元リレー更新」を1トランザクションにまとめ、第16.12節の順序と一致する。
- 既存3箇所の`ChannelCacheStore.upsertChannel`呼び出し(`ChannelListViewModel.kt`×2、`ChannelController.kt`×1)は変更していない。新APIは追加のみで、既存の観測ベースのチャンネル保存経路と並存する。
- Blocker / 未解消Majorはなし。

### 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test --tests 'com.nostr.torinos.network.RelayTargetUrlsTest'` | 成功 | 4ケース(AllEnabled/Single/Explicit未登録URL許可/Explicit正規化重複排除) |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功 | commonTest全体、failures/errorsなし |
| Room schema export確認 | 成功 | `composeApp/schemas/.../ChannelCacheDatabase/7.json`が生成され、`channels`への3列追加と`channel_recommended_relays`テーブルを確認 |

Room DAOレベルの`getChannelMetadata`/`upsertChannelMetadata`往復テストは、本プロジェクトにRoom migration用のテスト基盤(instrumented test、`MigrationTestHelper`等)が存在しないため追加していない。代わりに次のSimulatorテストで実データに対するmigrationとAPI経路の健全性を確認した。

### Simulatorテスト

実施環境:

- 実行日時: 2026-09-23 21:55-22:05 JST
- macOS: 26.5 (25F71)
- Xcode: 26.6 (17F113)
- Simulator: iPhone 17 / iOS 26.5
- build: Debug
- アカウント: 既存の署名可能アカウント
- 対象DB: Loop 0/1のSimulatorテストで蓄積された実データ(DB v6、channels=83、channel_messages=2317、channel_relays=134)

| シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- |
| DerivedData削除後のクリーンビルドとインストール | 成功 | Kotlin変更(migration追加)がフレームワークに反映されたことをビルド時刻で確認 |
| 既存v6 DBを保持したまま新ビルドを起動し、migration 6→7を実行させる | 成功 | 起動後`PRAGMA user_version`が7になり、`channel_recommended_relays`テーブルが作成された |
| migration前後でのデータ保全確認 | 成功 | `channels`=83件、`channel_messages`=2317件、`channel_relays`=134件が移行後も同数のまま維持 |
| 既存行への`metadataEventId`/`metadataKind`/`metadataCreatedAt`バックフィル確認 | 成功 | サンプル行で`metadataEventId = channelId`、`metadataKind = 40`、`metadataCreatedAt = updatedAt = createdAt`を確認(第16.12節の規則どおり) |
| migration後のアプリ起動・フィード表示 | 成功 | クラッシュなし、フォロー/グローバルフィードとも表示継続(スクリーンショットで確認) |

実データ環境([[ios-sim-cliclick-real-relay-risk]]参照)のため、フィード内投稿へのcliclickタップは行わず、DBレベルの検証とプロセス生存確認・スクリーンショットに留めた。チャンネル一覧・詳細画面でのUI確認はLoop 3以降、resolverベースの表示経路が配線されてから改めて行う。

### 設計書へのフィードバック

- 確定した仕様:
  - `RelayTarget.Explicit`は第16.6節の設計どおり、`enabledRelayUrls`によるフィルタを行わず正規化のみを行う。既存呼び出し箇所への追加対応は不要と確認済み。
  - DB v7 migrationは実データに対して検証済み。既存行のバックフィル規則(`metadataEventId = channelId`、`metadataKind = 40`、`metadataCreatedAt = updatedAt`)が実際に機能することを確認した。
- 変更した仕様: なし
- 次ループへ送る課題:
  - Loop 3で`ChannelController`をsession化する際、既存3箇所の`ChannelCacheStore.upsertChannel`呼び出しを`ChannelMetadataResolver`の出力(`ChannelMetadataResolution`)経由の`upsertChannelMetadata`へ置き換える。
  - Room migrationの自動テストが恒久的に手動Simulator確認に依存しないよう、instrumented testまたはKMP対応のRoom in-memoryテスト基盤の導入を検討する(現状は本ループのように実データでの起動確認で代替)。

## 25. Loop 3a 実施記録: ChannelMetadataResolverの接続

Loop 3(チャンネル購読)はフルスコープで一括実装すると`ChannelController.kt`(860行超、エンゲージメント集計等と密結合)への変更が大きくなりレビュー粒度が粗くなるため、ユーザーの判断でスコープを分割した。3aは「resolver接続」のみを対象とし、3b(session化・kind 41二段階再購読・観測元リレーキャッシュの本格配線)は別ループとして残す。

- 状態: 完了
- 対象: `ChannelController`のkind 40/41ライブ購読ハンドラを、UIローカルの逐次判定から`ChannelMetadataResolver`(Loop 1で導入済み)へ置き換える。あわせて`ChannelCacheStore.upsertChannel`から`upsertChannelMetadata`(Loop 2で追加済み)への切り替えをこの経路に限り実施する
- 対象外: `SubscriptionSession`ベースへの購読方式そのものの移行、`ChannelRelayContext`に基づく推奨リレーへの動的購読・投稿切り替え、kind 41受信時の二段階再購読(FR-08、第16.7節)、観測元リレー集合の複数保持。これらは3bへ送る。`ChannelListViewModel.kt`の2箇所の`upsertChannel`呼び出しも本ループでは変更しない(Phase 2 UI改修時に合わせる)
- 開始時commit: `55a6018`
- 対象ファイル:
  - `composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/channel/ChannelController.kt`

### 設計レビュー

| 重大度 | 指摘 | 対応 |
| --- | --- | --- |
| Major(既存不具合、本ループで解消) | 旧実装は`event.createdAt <= latestMetaUpdateCreatedAt`のみで判定しており、Loop 0の設計レビュー(第22.1節)で`ChannelDetailsDialog`側に指摘したのと同種の「同一`created_at`のkind 41で受信順により表示が変わる」不具定が、実際のチャンネル画面(`ChannelController`)側には未反映のまま残っていた | 候補をevent ID単位で保持し`ChannelMetadataResolver.resolve()`を再実行する方式に置き換え、`(createdAt, id)`降順の決定的なtie-breakとroot marker検証をLoop 1のテスト済みロジックに委譲した |
| Note(副次的な改善) | 旧実装はkind 40のcontent JSONが不正な場合、`currentChannelOwnerPubkey`が設定されないままとなり、kind 41購読(`metaUpdateSubId`)自体が開始されず、後から有効なkind 41が届いても永久に回復できなかった | 新実装は`channelCreateEvent`の設定とkind 41購読開始をresolver結果の成否から独立させたため、kind 40のJSONが不正でも有効なkind 41受信時に回復できるようになった(第10章のエラー処理方針と整合) |
| Note | `saveThreadMeta()`(kind 41編集の自己発行)は本ループの対象外のため、発行直後に`currentChannelMeta`を直接更新する既存の楽観的更新ロジックのままとした。候補プールに登録されないため、直後に別の(自分より古い)kind 41がネットワークから届いた場合に上書きされうる潜在的なギャップは温存されている | 対応不要(Phase 2のkind 41編集強化、第16.10節で解消予定)。次ループへの既知課題として記録 |

### 実装レビュー

- `channelCreateEvent`/`metadataUpdateCandidates`/`effectiveMetadataSourceEventId`は、既存の`currentChannelMeta`等と同様に複数の`launch`ブロックから同期プリミティブなしで読み書きされる。これは本ファイル全体で既に採用されている一貫したスタイル(単一スコープのシーケンシャル実行前提)であり、新たなリスクを追加するものではない。
- `applyMetadataResolution()`内の`ChannelCacheStore.upsertChannelMetadata`呼び出しは`CancellationException`を再送出し、その他の例外は`logException`でログのみに留める既存方針を踏襲した。
- kind 40購読(`metaSubId`)は`event.id != channelId`のガードを追加した(フィルタ`NostrFilter(ids = listOf(channelId))`により実際にはid不一致は発生しない防御的チェック)。
- `channelCreateEvent != null`ガードにより、複数リレーから同一kind 40を重複受信しても`ChannelMetadataResolver.resolve()`の再実行やキャッシュ書き込みが不要に繰り返されない。
- 未使用となった`import com.nostr.torinos.model.toChannelMeta`を削除した。
- Blocker / 未解消Majorはなし。

### 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功 | commonTest全体、failures/errorsなし。`ChannelController`自体はNostrRepository/ChannelCacheStoreの実シングルトンに密結合しており(既存コードと同様)、単体テストの基盤が現状ない |

### Simulatorテスト

実施環境:

- 実行日時: 2026-09-23 22:18-22:25 JST
- macOS: 26.5 (25F71)
- Xcode: 26.6 (17F113)
- Simulator: iPhone 17 / iOS 26.5
- build: Debug

| シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- |
| DerivedData削除後のクリーンビルド・インストール・起動 | 成功 | ビルド成功、フィード表示正常、クラッシュなし |
| チャンネル画面を開いてkind 40/41メタデータ解決の実動作を確認 | 保留 | Mac側の画面ロック等によりSimulator GUIウィンドウが取得できず([[ios-sim-cliclick-coordinate-mapping]]記載の既知事象)、cliclickでの画面遷移ができなかった。パスワード入力はユーザーへの依頼が必要なため、本ループでは実施を見送った |

プロセスは実リレーからのフィード購読・表示を継続しクラッシュしていないことをスクリーンショットと`launchctl list`で確認済みだが、チャンネル画面固有の表示確認は次回Simulatorアクセス時に持ち越す。

### 設計書へのフィードバック

- 確定した仕様: なし(第16.4節の設計をそのまま実装し、仕様の変更はない)
- 変更した仕様: なし
- 次ループへ送る課題:
  - L3a-S01(仮称): チャンネル画面を開き、kind 40のみ/kind 40+41/同時刻複数kind 41のケースで表示メタデータが正しいことをSimulatorで確認する。Mac画面のロック解除後に実施する。
  - Loop 3bで`ChannelRelayContext`・`SubscriptionSession`・kind 41二段階再購読(FR-08)を`ChannelController`へ接続する。
  - `saveThreadMeta()`の楽観的更新を候補プール(`metadataUpdateCandidates`)経由に統合し、自己発行イベントも同じresolver経路で扱う(Phase 2 kind 41編集強化と合わせて検討)。
  - `ChannelController`向けのテスト基盤(NostrRepository/ChannelCacheStoreのfake化)導入を検討する。
