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

### FR-12: ローカル永続化

> [!NOTE]
> 2026-09-23 のレビューで方針を変更した（第22章参照）。チャンネル機能は利用チャンネル数・メッセージ数とも少なく（実測: チャンネル83件、蓄積メッセージ計2317件）、リレーが一次ストアとして存在する以上、メッセージ本体の全履歴をローカルDBへ複製する設計はコストに見合わないと判断した。本節は変更後の要件を記載する。旧要件（Room DBへのメッセージ全履歴キャッシュ）は第22.1節に経緯として残す。

- 次を端末に永続化する（`ChannelLocalState`、第22.2節）。
  - 実効メタデータ（kind 40/41 由来の name/about/picture）、`sourceEventId`/`sourceKind`/`sourceCreatedAt`、正規化済み推奨リレー。
  - お気に入りフラグ。
  - 既読位置（`lastReadAt` と直近のスクロール位置）。
  - 一覧プレビュー用に、チャンネルごと直近1件のメッセージ（event ID・created_at・pubkey・本文先頭の切り詰め）のみ。全履歴は保持しない。
- メッセージ本体の全履歴はローカルへ永続化しない。チャンネルを開くたびにリレーから取得し、表示用に一時的にメモリ上へ保持する（event ID による重複排除はメモリ上のセッション内で行う）。
- 未読件数はローカルに保持する数値ではなく、起動時にリレーへの一括問い合わせ（`kinds:42`、対象 `channelId` を `e` タグに列挙、`since` は保持済み `lastReadAt` の最小値）で取得した件数からセッション開始時に算出し、以後はライブ受信でメモリ上のみ加算する（第22.3節）。
- 永続化先は Room/SQLite ではなく、`RelayStore` と同じ `LocalSettingsStorage` ベースの JSON 保存（`Map<channelId, ChannelLocalState>`）とする。migration・DAO・schema export・prune はいずれも不要になる。

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

ローカル永続化（第16.12節、`ChannelLocalState`）は参加チャンネル一覧の表示速度・オフライン利用のキャッシュとして残し、kind 10005 を同期元として統合する。

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
4. 「イベントを観測したリレー」と「メタデータで推奨されたリレー」をローカル永続化状態の上でも別の概念として保持する（第16.12節、2026-09-23以降は DB ではなく `LocalSettingsStorage` 上の JSON）。
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
| `network/ChannelCacheStore.kt` | Room実装を撤去し、`ChannelLocalState`のJSON永続化（`LocalSettingsStorage`経由）へ置き換える(第16.12節) |
| `network/cache/ChannelCacheDatabase.kt` | 撤去（メッセージ本体キャッシュとDB v1〜v7 migrationを含め全体を削除） |
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

### 16.12 ローカル永続化の設計（FR-12）

> [!NOTE]
> 本節は 2026-09-23 の方針変更後の設計を記載する。Room/SQLite（DB v1〜v7）ベースの旧設計は Loop 0・Loop 2 で実装済みだが、本方針により置き換える。旧設計の経緯は第22.1節、詳細な実装記録は第21.1節の要約とgit commit historyを参照。

#### 概念分離（変更なし）

「メッセージを観測したリレー」と「メタデータで推奨されたリレー」を別概念として扱う方針（第16.1節の原則4）は維持する。ただし両者とも、メッセージ本体の全履歴を伴わない小さな状態としてのみ保持する。

#### 永続化するデータ

```kotlin
@Serializable
data class ChannelLocalState(
    val channelId: String,
    val ownerPubkey: String,
    val metadataEventId: String,
    val metadataKind: Int,
    val metadataCreatedAt: Long,
    val meta: ChannelMeta,
    val isFavorite: Boolean = false,
    val lastReadAt: Long = 0,
    val lastScrolledMessageId: String? = null,
    val lastScrolledCreatedAt: Long? = null,
    val lastScrolledOffset: Int = 0,
    val latestMessage: ChannelLatestMessagePreview? = null,
)

@Serializable
data class ChannelLatestMessagePreview(
    val eventId: String,
    val createdAt: Long,
    val pubkey: String,
    val contentPreview: String,
)
```

`unreadCount` はこの型に含めない（第16.12.3節）。`latestMessage` はチャンネルごと直近1件のみで、履歴は持たない。

#### Store API

```kotlin
suspend fun getChannelLocalState(channelId: String): ChannelLocalState?
suspend fun getAllChannelLocalStates(): Map<String, ChannelLocalState>
suspend fun upsertChannelMetadata(
    channelCreateEvent: NostrEvent,
    effectiveEvent: NostrEvent,
    metadata: ChannelMeta,
)
suspend fun upsertLatestMessagePreview(channelId: String, preview: ChannelLatestMessagePreview)
suspend fun markRead(channelId: String, readAt: Long)
suspend fun saveReadingPosition(channelId: String, position: ChannelReadingPosition)
suspend fun setFavorite(channelId: String, isFavorite: Boolean)
suspend fun deleteChannel(channelId: String)
```

kind 41 の event ID を channel ID として保存しないよう、`upsertChannelMetadata` は create event と effective event を明示的に分ける（旧設計から変更なし）。

#### 永続化先

`RelayStore` が使う `LocalSettingsStorage`（キー文字列 → JSON 文字列の read/write）を流用し、単一キー（例: `channel_local_state`）へ `Map<channelId, ChannelLocalState>` を JSON でまるごと保存・読込する。Room/SQLite、DAO、schema export、migration、prune はいずれも不要になる。

- 読込はアプリ起動時に1回、Map全体をデコードしてメモリへ載せる。
- 書込は `upsertChannelMetadata` / `markRead` / お気に入り変更など、発生頻度が低い操作でのみ行う。`latestMessage` の更新はライブ受信のたびに発生しうるため、`ChannelController.saveReadingPosition` と同じ debounce パターン（第9.2節、400ms）で書込頻度を抑える。
- チャンネル数が数百件規模になっても JSON 全体のシリアライズ・デシリアライズは軽量である前提を置く。数千件規模まで増えた場合は分割保存（チャンネルIDでシャーディング等）を再検討する。

#### メッセージ本体の扱い

メッセージ本体はローカルへ永続化しない。`ChannelHistory`（第9.1節の処理フロー）がリレーから取得したイベントをメモリ上に保持し、event ID による重複排除もメモリ上のセッション内で行う。アプリ再起動後は毎回リレーから再取得する。

#### 未読件数の算出

第16.12.1節を参照。永続化された `lastReadAt` だけを起点に、起動時のリレー問い合わせとライブ受信の加算で算出し、`unreadCount` 自体はディスクへ書かない。

##### 16.12.1 起動時キャッチアップ

```text
1. 永続化済み ChannelLocalState 全件から lastReadAt の最小値 sinceFloor を求める
2. NostrFilter(kinds = [42], eTags = 全channelId, since = sinceFloor) で一括購読
3. 返ってきたイベントを channelId ごとに集計し、createdAt > その channel の lastReadAt であるものの件数を数える
4. 件数をメモリ上の unreadCounts[channelId] へ設定（表示は上限キャップ、例 "99+"）
5. 集計対象イベント本体は保持せず破棄する
```

一括問い合わせにする理由は、チャンネルごとに個別 REQ を送ると起動時に接続中の全リレーへ数十〜数百件の購読が同時発生するため。`eTags` に全 channelId を並べた単一フィルターへまとめ、クライアント側で振り分ける。

##### 16.12.2 セッション中の加算

チャンネル一覧が購読している全チャンネル横断の kind:42 ライブ購読（第16.13節、既存の `liveSubId` 相当）で新着を受信するたびに、対象チャンネルが現在開いていなければ `unreadCounts[channelId] += 1`（`createdAt > lastReadAt` の場合のみ）。ディスクへは書かない。

##### 16.12.3 既読化

チャンネルを開いて `markRead` を呼んだ時点で `unreadCounts[channelId] = 0` とし、`lastReadAt` を永続化する。次回起動時のキャッチアップは新しい `lastReadAt` を起点にする。

##### 16.12.4 オフライン・取得失敗時

起動時キャッチアップが失敗・タイムアウトした場合、その回は `unreadCounts` を更新せず、UI 上は「未読件数不明」として `hasUnread`（真偽値、旧 `lastReadAt` の有無から導出可能な範囲)のみ表示するか、前回値を維持する。件数の完全性よりも機能停止しないことを優先する。

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
| 3.5 | ローカル永続化の簡素化 | Room/SQLite撤去、`ChannelLocalState`のJSON永続化、未読件数のキャッチアップ方式への移行(第22章) | 計画中 |
| 4 | チャンネル投稿 | kind 40/41/42の配送、relay hint、リレー別結果 | 未着手 |
| 5 | UIと返信連携 | ヘッダー、投稿先、詳細、編集、通常返信画面 | 未着手 |
| 6 | モデレーション | kind 43/44 | 未着手 |
| 7 | 参加同期 | kind 10005 | 未着手 |

依存関係やレビュー結果によってループを分割・統合してよい。ただし、変更した理由を直前ループのフィードバックへ記録する。

## 21. 実施済みループの記録

### 21.1 概要

| Loop | 状態 | 対象 | 主な指摘（Blocker/Major） | Commit |
| --- | --- | --- | --- | --- |
| 0: 現在差分の安定化 | 保留（L0-S08/L0-S10未実施） | チャンネル詳細・一覧UI・履歴キャッシュ一括保存・DB v6・prune・kind 5の`k`タグ | 詳細取得Jobの再表示後キャンセル漏れ／kind 41同時刻tie-breakの受信順依存／不正メタデータ優先の3件。いずれも解消 | `25156a9` |
| 1: モデルと純粋ロジック | 完了 | URL正規化、`ChannelMeta.relays`、`ChannelMetadataResolver`、`ChannelRelayContextBuilder`、`ChannelEventTags` | `normalizeRelayUrl`がFR-01のquery/fragment拒否に違反。既定ポート省略も未実装。両方修正・回帰テスト追加 | `25156a9` |
| 2: 明示購読とDB v7 | 完了（Room実装はLoop 3.5で置換予定、第22章） | `RelayTarget.Explicit`の意味修正、DB migration 6→7、`ChannelCacheStore`のmetadata API | なし | `55a6018` |
| 3a: resolver接続 | 完了 | `ChannelController`のkind 40/41ライブ判定を`ChannelMetadataResolver`へ移行 | 同時刻kind 41の受信順依存が実チャンネル画面側に残存。resolver移行で解消 | `3e3464f` |

設計レビュー・実装レビューの詳細、自動テストコマンド、Simulator実施環境（macOS/Xcode/Simulatorバージョン等）は各コミットメッセージ（`git log`）に記載している。

### 21.2 未解決の申し送り事項

1. L0-S08（自分のkind 40削除要求の実リレー送信確認）、L0-S10（接続不能リレーでのUI確認）は副作用・障害注入が必要なため未実施のまま。
2. L3a-S01（仮称）: チャンネル画面を開き、kind 40のみ／kind 40+41／同時刻複数kind 41の各ケースで表示メタデータが正しいことをSimulatorで確認する。Mac画面のロックでSimulator GUIが操作できず保留、ロック解除後に再試行する。
3. Loop 3b: `ChannelRelayContext`・`SubscriptionSession`・kind 41受信時の二段階再購読（FR-08、第16.7節）を`ChannelController`へ接続する。
4. `saveThreadMeta()`（kind 41自己編集）の楽観的更新を候補プール（`metadataUpdateCandidates`）経由に統合し、自己発行イベントも同じresolver経路で扱う（Phase 2 kind 41編集強化、第16.10節と合わせて検討）。
5. `ChannelController`向けのテスト基盤（NostrRepository/ChannelCacheStoreのfake化）導入を検討する。
6. 第22章の方針により、Loop 2で実装したRoom関連コード（DB v7 migration、`ChannelListViewModel.kt`×2・`ChannelController.kt`×1の`upsertChannel`呼び出し）はLoop 3.5で置換する。

### 21.3 Simulator定義済みシナリオ（再利用可能）

Loop 0で定義したシナリオはLoop横断で再利用する。

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

## 22. アーキテクチャ変更: ローカル永続化の簡素化（2026-09-23）

### 22.1 経緯

Loop 3a完了後のレビューで、チャンネルメッセージのローカルDBキャッシュ機構(Room/SQLite、DB v1〜v7)についてユーザーから次の指摘があった。

1. メッセージはリレー自身が既に永続化しているため、ローカルDBへの全履歴複製は本質的に冗長である。
2. チャンネル機能はユーザー数・利用量とも少ない機能であり(実測値: チャンネル83件、蓄積メッセージ計2317件、平均約28件/チャンネル)、複製コスト(schema migration v1〜v7、prune戦略、3種類のリレー集合テーブル、cache/live間のdedup整合性)に見合わない。

検討の結果、次の対抗指摘も出た。

- 未読件数(`observeChannels`の`unreadCount`)は`channel_messages`を`lastReadAt`と突き合わせるCOUNTクエリで算出しており、メッセージ本体キャッシュを単純に撤去すると未読バッジと一覧の最新メッセージプレビューの両方が機能しなくなる。
- 実際の実装を確認したところ、`ChannelListViewModel`はチャンネル一覧画面を開いている間、全チャンネル横断のkind:42ライブ購読(`liveSubId`)を維持しており、受信するたびに`updateActivity()`が`ChannelCacheStore.upsertMessage()`を呼んで**現在開いていないチャンネルの分も含め全メッセージをDBへ書き込んでいた**。未読バッジをRoomの reactive Flow で自動更新するためだけに、ネットワーク全体のchannelトラフィックをディスクへ複製していたことになる。この事実確認により「冗長」という指摘の妥当性がより強く裏付けられた。

未読件数・最新メッセージプレビューを維持したまま複製コストを下げる設計として、次の方針に合意した。

- メッセージ本体の全履歴永続化は撤去する。
- 未読件数はメッセージ本体を保持せず、起動時のリレー一括問い合わせ(キャッチアップ)とライブ受信時のメモリ上加算だけで算出する(件数そのものはディスクへ書かない。`lastReadAt`だけが永続化の起点になる)。
- 最新メッセージプレビューは全履歴ではなく、チャンネルごと直近1件のみ保持する。
- 永続化先はRoom/SQLiteをやめ、`RelayStore`が既に使っている`LocalSettingsStorage`ベースのJSON保存に統一する。

この決定にともない、Loop 0(DB v6, schema/migration/prune整備)とLoop 2(DB v7, `channel_recommended_relays`と`upsertChannelMetadata`のRoom実装)で実装したRoom関連コードは、実装としては置き換え対象になる。両ループの設計判断・レビュー内容自体は第21.1節の要約とgit commit historyに残しており、変更しない。Loop 1(`ChannelMeta`/`ChannelMetadataResolver`/`ChannelRelayContextBuilder`/`ChannelEventTags`などの純粋ロジック)とLoop 3a(`ChannelMetadataResolver`の接続)は、ローカル永続化の実装方式に依存しないため影響を受けない。

### 22.2 新しいローカル永続化設計

第16.12節に詳細設計を記載した。要点は次のとおり。

- `ChannelLocalState`(channelIdをキーとするJSONオブジェクト): 実効メタデータ、推奨リレー、お気に入り、既読位置、直近1件のメッセージプレビューのみを保持する。
- メッセージ本体の全履歴はメモリ上のセッション内でのみ扱い、永続化しない。
- 未読件数は永続化しない。起動時に「対象全channelIdを`e`タグに列挙した単一フィルターでの一括問い合わせ」でキャッチアップし、以後はライブ受信のたびにメモリ上で加算する(第16.12.1〜16.12.4節)。
- 保存先はRoom/SQLiteではなく`LocalSettingsStorage`のJSON保存。migration・DAO・schema export・pruneはすべて不要になる。

### 22.3 影響範囲

| 領域 | 変更内容 |
| --- | --- |
| `network/cache/ChannelCacheDatabase.kt` | 撤去(Entity・DAO・Migration 1〜7をすべて削除) |
| `network/cache/ChannelCacheDatabaseBuilder*.kt`(android/ios/mobile) | 撤去 |
| `network/ChannelCacheStore.kt`(expect)/`ChannelCacheStore.mobile.kt`(actual) | API を全面的に置き換え(第16.12節のStore API) |
| `composeApp/schemas/.../ChannelCacheDatabase/*.json` | 撤去(Room schema exportそのものが不要になる) |
| `ui/channel/ChannelController.kt` | `upsertMessage`/`upsertMessages`/`getMessages`/`getMessage`/`deleteMessage`呼び出しを、メモリ上の`ChannelHistory`のみで完結する形へ置き換え。`upsertChannelMetadata`は新Store APIへ合わせて引数調整 |
| `ui/channel/ChannelListViewModel.kt` | `observeChannels`(Room Flow)を`ChannelLocalState`ベースの状態管理へ置き換え。`updateActivity()`の全件`upsertMessage`書き込みを撤去し、直近1件プレビュー更新とメモリ上未読加算に変更。起動時キャッチアップ購読を新設 |
| `ui/channel/ChannelHistory.kt` | キャッシュ初期値をローカルDBからではなく空(または直近プレビュー1件)から開始し、常にリレー取得へフォールバックする形へ調整 |

### 22.4 未決事項

1. `ChannelLocalState`の書込debounce間隔(本文では既存の400msパターンを流用と仮置きしたが、実測して調整する)。
2. チャンネル数が将来大きく増えた場合の単一JSONキー保存のスケーラビリティ(第16.12節に記載のとおり、数千件規模になったら分割保存を再検討)。
3. 起動時キャッチアップの一括フィルターが対象リレー・接続タイミングにより一部リレーへ未到達となるケースの扱い(タイムアウト時は「不明」表示にとどめるか、キャッシュ済み値を暫定表示するか)。

### 22.5 Loop 3.5 計画: ローカル永続化の簡素化（実装）

- 状態: 計画中(未着手)
- 対象:
  - `ChannelCacheStore`/`ChannelCacheDatabase`関連コードのRoom実装を撤去し、`ChannelLocalState`のJSON永続化へ置き換える。
  - `ChannelController`・`ChannelListViewModel`の呼び出し元を新Store APIへ配線し直す。
  - 起動時キャッチアップ購読とメモリ上未読加算を`ChannelListViewModel`へ実装する。
  - 新設計の単体テスト(JSON永続化のシリアライズ/デシリアライズ、未読キャッチアップの集計ロジックなど、pure logicとして分離できる範囲)を追加する。
- 対象外: Loop 3b(session化・kind 41二段階再購読)は引き続き別ループ。kind 43/44/10005(Phase 3)。
- 依存関係: Loop 1〜3aの成果物(resolver、relay context builder、event tags)は流用する。Loop 2で追加したRoom DB v7のmigration・エンティティはこのLoopで削除する。
