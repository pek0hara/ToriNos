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

候補のうち `created_at` が最大のものを採用する。`created_at` が同じ場合は event ID が辞書順で最小のものを採用し（NIP-01 の replaceable event の慣習。Loop 4 で端末ローカル保存の判定もこの規則へ統一）、端末ごとの不定な選択を避ける。

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
- 未読件数はローカルに保持する数値ではなく、起動時に現在選択中の単一リレーへの一括問い合わせ（`kinds:42`、対象 `channelId` を `e` タグへチャンク分割して列挙、`since` は各チャンク内の `lastReadAt` の最小値）で取得した件数からセッション開始時に算出し、以後はライブ受信でメモリ上のみ加算する（第16.12.1〜16.12.3節）。一度も開いていないチャンネル（`lastReadAt` 未設定）は件数を出さず「新着あり」フラグのみ表示する（第16.12.4節）。
- 永続化先は Room/SQLite ではなく、`RelayStore` と同じ `LocalSettingsStorage` ベースの JSON 保存（`Map<channelId, ChannelLocalState>`）とする。migration・DAO・schema export・prune はいずれも不要になる。

### FR-13: チャンネル作成シート（2026-09-24 追加）

現在の「新規チャンネル」ダイアログ（`CreateChannelDialog`、名前・説明・本文の3欄の `AlertDialog`）を、フィード投稿の `PostSheet` と同等の操作感を持つ全画面シートへ置き換える。

- 入力項目:
  - チャンネル名（必須、1行）
  - 説明（複数行）
  - アイコン画像（任意）。フィード投稿と同じ `ImageUploader` でアップロードし、`picture` に URL を入れる。
  - 推奨リレー（FR-04）。初期選択はユーザーの書き込みリレー。複数選択で、手入力の追加は FR-01 で検証する。
  - 最初の投稿（任意）。フィード投稿の本文入力と同じく、カスタム絵文字・画像添付を使える。
- 送信先の表示: 推奨リレーの選択結果と、実際の送信先（推奨 + ユーザーの書き込みリレー）をシート内で確認できる（FR-10 と同じ表示部品）。
- 送信中・結果: kind 40 と最初の kind 42 のリレー別結果を表示する。「kind 40 成功・初回投稿失敗」の場合はシートを閉じず、「投稿を再送信」で kind 42 だけを再送する（Loop 4 の L4-D3 の挙動を維持）。
- 閉じるとき: 入力があれば破棄の確認を出す。下書き保存（`PostSheet` の下書き一覧）とは共有しない。
- 実装方針: `PostSheet` をそのままチャンネル作成に流用せず、本文入力欄・絵文字/画像ツールバー・リレー選択ダイアログ（`PostRelaySettingsDialog`）を共通部品として切り出して再利用する。送信処理は `ChannelListViewModel.createChannel()`（Loop 4）を使う。

### FR-14: 一覧ヘッダーのキャッシュ一括削除ボタンの撤去（2026-09-24 追加）

チャンネル一覧ヘッダーの「お気に入り以外のキャッシュを削除」ボタン（`CleaningServices` アイコン、`confirmBulkDelete` → `ChannelLocalStore.deleteNonFavorites`）と確認ダイアログを撤去する。

理由（Loop 3.5 後の実装で確認）:

- ボタンの本来の目的は Room DB に溜まるメッセージ全履歴の容量削減だったが、Loop 3.5 でメッセージ本体は保存しなくなった。端末に残るのは1チャンネルあたり約0.9KBの `ChannelLocalState` だけで、1,000件の上限と自動退避もある。
- 押しても一覧の見た目はほぼ変わらない。そのセッションで取得済みのチャンネルはメモリ上の一覧に残り、次回起動時も kind 40 のページ取得で同じチャンネルが再び記録される。
- 実際に消えるのは、お気に入り以外の既読位置と一覧プレビューだけで、未読件数の起点（`lastReadAt`）を失う副作用のほうが大きい。

個別チャンネルの「この端末から削除」（長押し → 削除ダイアログ、削除要求を送らない方）は残す。

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
- [x] チャンネル推奨リレーがユーザー設定外でも kind 42 を購読できる。（Loop 3b、L3b-S01）
- [x] kind 42 がすべての選択済み推奨リレーへ送信される。（Loop 4、L4-S02）
- [x] kind 42 の `e` タグに root marker と relay hint が入る。（Loop 4、L4-S01/S02）
- [ ] 返信時に root/reply/p タグが正しく生成される。
- [ ] 閲覧先と投稿先を UI から確認できる。
- [ ] リレー別の送信成功・失敗を確認できる。
- [x] 一部成功時は投稿済み、全失敗時は未投稿として扱われる。（Loop 4、単体テスト。全失敗・一部失敗の実リレー確認は未実施）
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

#### Loop 3b の実装で確定した事項

- セッション API（`openSubscription`）への全面移行はせず、既存の互換 API `NostrRepository.subscribe(subId, filter, RelayTarget)` を使う（L3b-D1）。同じ購読 ID へ target だけ変えて呼び直すと、フィルターが同じリレーには REQ を再送せず、追加リレーにだけ REQ、外れたリレーにだけ CLOSE が送られるため、第16.7節の二段階更新をそのまま実現できる。`ChannelController` は購読 ID → フィルターの表（`liveFilters`）を持ち、context 変更時に全ライブ購読の target を張り替える。
- metadata 購読と message 購読は別 ID だが、target は同じ `readRelays`（推奨 + bootstrap）とする。bootstrap は常に `readRelays` に含まれるため、第2〜3手順の「metadata 購読の target に推奨リレーを加える」は context 更新と同じ操作になる。
- 返信数・リアクション・リポスト・引用の関連購読も同じ `readRelays` を使う。
- 初期化順の第1手順は、`ChannelLocalState` に保存済みの推奨リレーで暫定 context を作り、最初の購読からその集合を使う。保存済みの実効メタデータが kind 41 由来で、受信した kind 40 より新しい場合は、同じか新しい kind 41 が届くまで表示・購読先を kind 40 へ戻さない（L3b-R2）。
- 推奨リレーもユーザーリレーも無い場合だけ `RelayTarget.Single(navigationRelayHint)`、それも無ければ `AllEnabled` へ戻す。
- 複数リレーの履歴取得は、どれか1つが EOSE を返してから 1.5 秒で打ち切り、1件以上 EOSE があれば完了扱いにする（L3b-D2）。`NostrRepository` は `RelayOutcome.Unavailable` を出さないため、従来の「全リレーの EOSE」を条件にすると応答しない推奨リレーが1件あるだけで各ページが 10 秒待ちの未完了になる。
- ユーザーのリレー設定をチャンネル画面の表示中に変更しても context は再計算しない（次に開いたときに反映）。

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

Loop 3b の実装で確定した事項:

- 待機条件は「追加リレーがすべて `Connected`」または 3 秒経過とした（第19章 未決5 を確定）。EOSE / EVENT は `Connected` の後にしか届かないため、接続状態だけで判定しても結果は変わらない。
- 追加が無い変更（推奨リレーを減らしただけ）は一段階で next へ移る。
- 追加リレーの接続後、その追加リレーだけへ最新ページ（30件）を問い合わせ、`ChannelHistory.supplement()` で統合する（L3b-R1）。履歴は切り替え前の集合で取得済みのため、これが無いと推奨リレーにしか無い最新メッセージが表示されない。読み込み済み範囲より古い投稿はページング境界を崩さないよう捨て、次の過去ページ取得（切り替え後の集合で行う）に任せる。スクロール位置は動かさず、既存の最新より新しい投稿はライブ受信と同じく新着数として扱う。
- 切り替え中は `UiState.Ready.isRelayTransitioning = true` とする（ヘッダー表示は Loop 5）。

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
    val ownerPubkey: String = "",          // 空 = kind 40 未取得のまま既読位置だけ保存した暫定行
    val channelCreatedAt: Long = 0,        // Loop 3.5で追加: 一覧のキャッシュ表示に kind 40 の作成日時が必要
    val metadataEventId: String = "",
    val metadataKind: Int = 40,
    val metadataCreatedAt: Long = 0,
    val meta: ChannelMeta = ChannelMeta(),
    val observedRelays: List<String> = emptyList(), // Loop 3.5で追加: kind 40 を観測したリレー(正規化済み、最大20件)
    val isFavorite: Boolean = false,
    val lastReadAt: Long? = null,          // Loop 3.5で変更: null = 未開封(第16.12.4節)。0 と区別する
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

`unreadCount` はこの型に含めない（第16.12.3節）。`latestMessage` はチャンネルごと直近1件のみで、履歴は持たない（本文は先頭200文字に切り詰める）。

`observedRelays` は Loop 3.5 の設計レビューで追加した（L35-D1）。旧 Room 実装の一覧は `channel_relays`（観測元リレー）で選択中リレーに絞り込んでいたため、これを持たないと別リレーで見つけたチャンネルまで一覧に混ざる。推奨リレー `meta.relays` とは別フィールドとし、第16.1節の原則4を JSON 上でも維持する。お気に入り・既読位置はリレーをまたいで共有する（旧実装と同じ）。

#### Store API

```kotlin
// 実装: network/ChannelLocalStore.kt の ChannelLocalStateStore(ストレージとスコープを注入してテスト可能)
fun observe(relayUrl: String): Flow<List<ChannelLocalState>>   // observedRelays に relayUrl を含み、メタデータ取得済みの行
suspend fun get(channelId: String): ChannelLocalState?
suspend fun recordChannelCreate(event: NostrEvent, meta: ChannelMeta, observedRelayUrl: String?)
suspend fun upsertChannelMetadata(
    channelCreateEvent: NostrEvent,
    effectiveEvent: NostrEvent,
    metadata: ChannelMeta,
    observedRelayUrl: String? = null,
)
suspend fun recordLatestMessage(channelId: String, event: NostrEvent)   // 新しい場合だけ置換。永続化は debounce
suspend fun clearLatestMessage(channelId: String, eventId: String)      // 削除要求を送った投稿をプレビューから外す
suspend fun markRead(channelId: String, readAt: Long)                   // 後退させない
suspend fun saveReadingPosition(channelId: String, position: ChannelReadingPosition)
suspend fun setFavorite(channelId: String, isFavorite: Boolean)
suspend fun deleteChannel(channelId: String)
suspend fun deleteNonFavorites(relayUrl: String)                         // 一括削除ボタン用(旧APIと同じ意味)
```

kind 41 の event ID を channel ID として保存しないよう、`upsertChannelMetadata` は create event と effective event を明示的に分ける（旧設計から変更なし）。

Loop 3.5 で確定したメタデータ保存規則:

- `recordChannelCreate`（一覧の kind 40 ページ取得）は、保存済みの実効メタデータが kind 41 由来なら `meta` を上書きしない（L35-R1）。旧 Room 実装の `upsertChannel` は一覧を読み直すたびに kind 40 の値で name/about を上書きしていた。
- `upsertChannelMetadata` は、保存済みの方が `(createdAt, id)` で新しければ維持する（L35-R2）。別リレーで開いて古い kind 41 しか届かなかった場合に巻き戻さないため。判定規則は第16.4節と同じ。
- 一覧は保存済みメタデータが kind 41 由来なら kind 40 の元値より優先して表示する。

#### 永続化先

`RelayStore` が使う `LocalSettingsStorage`（キー文字列 → JSON 文字列の read/write）を流用し、単一キー（例: `channel_local_state`）へ `Map<channelId, ChannelLocalState>` を JSON でまるごと保存・読込する。Room/SQLite、DAO、schema export、migration、prune はいずれも不要になる。

- 読込は初回アクセス時に1回、Map全体をデコードしてメモリへ載せる。キーは `channel_local_state_v1`。未知フィールドは無視し、デコード失敗時は空として扱う（ログに残す）。
- 書込は `upsertChannelMetadata` / `markRead` / お気に入り変更など、発生頻度が低い操作では即時に行う。`latestMessage` の更新はライブ受信のたびに発生しうるため 400ms の debounce で書込頻度を抑える。debounce はストア自身のスコープで動かし、画面・ViewModel の破棄で書込が失われないようにする。書込は直列化し、常に書込直前の最新スナップショットを書く。
- 書込失敗はネットワーク表示を止めずログに残す（第16.20節）。
- 保持件数の上限を 1,000 件とし、超過時は非お気に入り・未開封・活動の古いものから落とす（L35-R5）。一覧の kind 40 ページ取得で見えたチャンネルはすべて記録されるため、上限がないと無制限に増える。実測 1 件あたり約 0.9KB（50 件で 43KB）で、上限時でも約 0.9MB。
- チャンネル数が数千件規模まで増えた場合は分割保存（チャンネルIDでシャーディング等）を再検討する。
- 旧 Room DB ファイル（`torinos_channel_cache.db` と `-wal`/`-shm`/`-journal`/`.lck`）は起動時に削除する。旧 DB からの移行は行わない（L35-D2。Simulator 上の旧 DB は 83 チャンネル中お気に入り 0 件・既読行 3 件で、移行コストに見合わないと判断した）。

#### メッセージ本体の扱い

メッセージ本体はローカルへ永続化しない。`ChannelHistory`（第9.1節の処理フロー）がリレーから取得したイベントをメモリ上に保持し、event ID による重複排除もメモリ上のセッション内で行う。アプリ再起動後は毎回リレーから再取得する。

#### 未読件数の算出

第16.12.1節を参照。永続化された `lastReadAt` だけを起点に、起動時のリレー問い合わせとライブ受信の加算で算出し、`unreadCount` 自体はディスクへ書かない。

対象リレーは `ChannelListViewModel` が現在表示している選択中の単一リレー（`relayUrl`）のみとする。チャンネルごとの推奨リレー（`ChannelRelayContext`）は対象に含めない。これにより未読機能は `ChannelRelayContext` の実装（Loop 3b）に依存せず、Loop 3.5単体で完結する。推奨リレーを含めた集計は、Loop 3b以降で`ChannelRelayContext`が導入された後に再検討する。

##### 16.12.1 起動時キャッチアップ

対象は「一度でも開いたことがある（`lastReadAt` が設定済みの）チャンネル」のみ。未開封チャンネルは第16.12.4節で別に扱う。

```text
1. 永続化済み ChannelLocalState のうち、選択中リレーで観測済みかつ lastReadAt が設定済みのものを対象にする
2. 対象を lastReadAt の降順に並べ、最大 CHUNK_SIZE 件ごとに分割する（初期値 200）。
   各チャンクの since はそのチャンク内の lastReadAt の最小値とする（L35-R3。全体の最小値より取得範囲が狭くなるだけで取りこぼしは増えない）
3. limit = min(NIP-11 の limitation.max_limit ?: 500, CATCH_UP_LIMIT=1000) とする（L35-R4）
4. 各チャンクごとに NostrFilter(kinds = [42], eTags = チャンク分の channelId, since, until = ライブ購読の since - 1, limit) で選択中リレーへ問い合わせる
5. 返ってきたイベントを channelId ごとに集計し、createdAt > その channel の lastReadAt であるものの件数を数える
6. 返却件数が limit に達したチャンクは「取りこぼしうる」。2件以上のチャンクなら半分に分割して再問い合わせする（L35-R6）。
   1件だけのチャンク、または REQ 総数が MAX_REQUESTS=16 を超える場合は、そのチャンクの全チャンネルを下限値（"N+"）として確定する
7. 件数をメモリ上へ設定する（表示は 99 超で "99+"、下限値は "N+"）。各チャンネルの最新1件は一覧プレビューへ反映する
8. 集計対象イベント本体は保持せず破棄する
```

Loop 3.5 の実装・Simulator 確認で確定した補足:

- `until = ライブ購読の since - 1` とすることで、キャッチアップとライブ受信（第16.12.2節）が時刻で重ならず、同じ event の二重計上を避ける。
- 要求 limit がリレー側の上限を超えると、リレーは黙って切り詰めて返すため「limit に達した」ことを検出できない（L35-R4）。strfry 等の既定値に合わせ、NIP-11 が取れないときは 500 を仮定する。
- 分割再問い合わせ（L35-R6）は Simulator で発見した。既読位置が 3 時間前・14 日前・30 日前の 3 チャンネルが1チャンクに入ると since が 30 日前になり、活発なチャンネルの既読済みメッセージで 500 件が埋まって全チャンネルが下限値表示（`26+`/`3+`/`13+`、実際は 26/3/15）になった。分割後は 26/3/15 と一致した。
- キャッチアップは状態の初回通知時ではなく、一覧の kind 40 初回ページ取得後に開始する（L35-R7）。初訪問のリレーでは観測元リレーがまだ記録されておらず、対象 0 件のまま終わっていた。

一括問い合わせにする理由は、チャンネルごとに個別 REQ を送ると起動時に選択中リレーへ数十〜数百件の購読が同時発生するため。`eTags` に channelId を並べた単一フィルター（チャンク分割あり）へまとめ、クライアント側で振り分ける。

キャッチアップ開始時に対象チャンネルの `lastReadAt` をスナップショットとして保持する。集計完了時に同じ値のままのチャンネルにだけ結果を適用し、完了前にユーザーが該当チャンネルを開いて既読化した場合は古い集計結果を破棄して `unreadCounts[channelId] = 0`（既読化による値）を優先する。

##### 16.12.2 セッション中の加算

チャンネル一覧が購読している全チャンネル横断の kind:42 ライブ購読（第16.13節、既存の `liveSubId` 相当）で新着を受信するたびに、対象チャンネルが現在開いていなければ `unreadCounts[channelId] += 1`（`createdAt > lastReadAt` の場合のみ）。ディスクへは書かない。

##### 16.12.3 既読化

チャンネルを開いて `markRead` を呼んだ時点で `unreadCounts[channelId] = 0` とし、`lastReadAt` を永続化する。次回起動時のキャッチアップは新しい `lastReadAt` を起点にする。

実装では件数を加算値として持たず、キャッチアップ結果（集計時の `lastReadAt` 付き）とセッション中にライブ受信した `(eventId, createdAt)` を別々に保持し、表示のたびに「集計時の `lastReadAt` が現在値と一致するキャッチアップ件数 + 現在の `lastReadAt` より新しいライブ受信件数」で求める（`ChannelUnreadTracker`）。既読化で `lastReadAt` が進むと古い集計は自動的に無効になり、既読化後に届いたメッセージだけが残る。

Loop 3.5 の Simulator 確認で、チャンネルを開いてスクロールせずに戻ると `markRead` が呼ばれない既存不具合を発見し修正した（L35-R8）。`ChannelScreen` の viewport 通知は最新位置への初回移動中は捨てられ、`distinctUntilChanged` のため移動完了後に再通知されなかった。旧 DB で既読行が 3 件しかなかった原因と考えられる。

##### 16.12.4 未開封チャンネル（`lastReadAt` 未設定）

一度も開いていないチャンネルは起動時キャッチアップの対象に含めない（`since` の起点がなく、全履歴取得になりコストが跳ね上がるため）。件数は算出せず、一覧には具体的な数値の代わりに「新着あり」相当の真偽値フラグのみを表示する。

フラグは、チャンネル一覧のライブ購読（第16.12.2節と同じ `liveSubId`）でその channelId の kind:42 をセッション中に1件でも観測したかで判定する。初回オープン（`markRead` 呼び出し）以降は、通常の件数ベースの未読表示（第16.12.1〜16.12.3節）へ切り替わる。

> [!NOTE]
> Loop 3.5 で「`ChannelLocalState.latestMessage` が設定済み」の条件を外した（L35-D3）。一覧は表示中チャンネルの最新1件を取得してプレビューに使うため、この条件ではほぼすべての未開封チャンネルに「新着あり」が付き、フラグの意味がなくなる。

##### 16.12.5 オフライン・取得失敗時

起動時キャッチアップが失敗・タイムアウトした場合、その回は `unreadCounts` を更新せず前回値を維持する。前回値も存在しない場合は件数を表示せず、既読/未読の区別だけを `lastReadAt` の有無から行う。件数の完全性よりも機能停止しないことを優先する。

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

### 16.20.1 チャンネル作成シートの設計（FR-13）

- `ui/channel/ChannelCreateSheet.kt`（新規）。表示は `ModalBottomSheet` の全画面、ヘッダーに「キャンセル」「作成（または投稿を再送信）」。
- 状態は `ChannelListViewModel.CreateDialogState` を拡張して使う: `picture`・`pictureUploadState`・`selectedRelays`・`customRelayInput`・`publishState`・`createdChannel`（Loop 4）。
- `PostSheet` から次を共通部品として切り出す: 本文入力欄（カスタム絵文字候補を含む）、画像添付ツールバー、リレー複数選択ダイアログ（現 `PostRelaySettingsDialog`、第8.4節の `RelayMultiSelector` に相当）。`PostSheet` 側の挙動は変えない。
- 推奨リレーの選択は `content.relays` に、送信先は `selectedRelays + ユーザーの書き込みリレー`（`ChannelRelayContextBuilder` と同じ規則）にする。
- 最初の投稿に画像を添付した場合は、フィード投稿と同じく本文へ URL を追記する（imeta タグの扱いも `PostViewModel` に合わせる）。
- テスト: 状態遷移（検証エラー、画像アップロード中の作成禁止、kind 40 成功・kind 42 失敗からの再送、全件失敗）を ViewModel の単体テストで確認する。

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
5. ~~relay transition の待機条件を Connected のみとするか、EOSE / EVENT も成功扱いにするか。~~ Loop 3b で「Connected または 3 秒」に確定（第16.7節）。
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
| 3 | チャンネル購読 | 推奨リレーからkind 42取得、kind 41更新時の再購読 | 完了（3a `3e3464f`、3b `8b827d6`、第23章） |
| 3.5 | ローカル永続化の簡素化 | Room/SQLite撤去、`ChannelLocalState`のJSON永続化、未読件数のキャッチアップ方式への移行(第22章) | 完了（`f42dca2`、第22.5節） |
| 4 | チャンネル投稿 | kind 40/41/42の配送、relay hint、リレー別結果 | 完了（未コミット、第24章） |
| 5 | UIと返信連携 | ヘッダー、投稿先、詳細、編集、通常返信画面 | 未着手 |
| 5a | チャンネル作成シート・一覧ヘッダー整理（2026-09-24 追加） | FR-13 のシート（推奨リレー選択・アイコン画像・最初の投稿）、FR-14 のキャッシュ一括削除ボタン撤去 | 未着手 |
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
| 3.5: ローカル永続化の簡素化 | 完了 | Room/SQLite(DB v1〜v7)撤去、`ChannelLocalState`のJSON永続化、未読キャッチアップ | 一覧のリレー別絞り込み欠落(D1)／kind 40再取得でkind 41上書き(R1)／リレー上限で下限判定不能(R4)／キャッチアップが1チャンクで飽和(R6)／初訪問リレーでキャッチアップ未実行(R7)／スクロールしないと既読化されない既存不具合(R8)。すべて解消 | `f42dca2` |
| 3b: チャンネル購読の relay context 対応 | 完了 | 推奨リレーを含む `Explicit` 購読、kind 41 変更時の二段階再購読、複数リレー履歴取得 | 新規チャンネルで推奨リレーの最新履歴が欠落(R1)／kind 40 先着で保存済みkind 41から巻き戻り・購読先がばたつく(R2)／応答しないリレー1件で履歴ページが10秒待ち・未完了(D2)。すべて解消 | `8b827d6` |
| 4: チャンネル投稿 | 完了 | kind 40/41/42 の配送先・relay hint・リレー別結果、kind 41 編集の完全メタデータ化 | kind 41 編集で推奨リレーが消える(R1)／拒否したリレーも成功扱い(D2)／応答しないリレーで投稿が10秒待ち(R3)／同時刻 tie-break が resolver と逆(R2) 。すべて解消 | 未コミット |

設計レビュー・実装レビューの詳細、自動テストコマンド、Simulator実施環境（macOS/Xcode/Simulatorバージョン等）は各コミットメッセージ（`git log`）に記載している。

### 21.2 未解決の申し送り事項

1. ~~L0-S08（自分のkind 40削除要求の実リレー送信確認）~~ Loop 4 の L4-S04 で確認済み。L0-S10（接続不能リレーでのUI確認）は障害注入が必要なため未実施のまま。
2. L3a-S01（仮称）: チャンネル画面を開き、kind 40のみ／kind 40+41／同時刻複数kind 41の各ケースで表示メタデータが正しいことをSimulatorで確認する。Mac画面のロックでSimulator GUIが操作できず保留、ロック解除後に再試行する。
3. ~~Loop 3b: `ChannelRelayContext`・kind 41受信時の二段階再購読を`ChannelController`へ接続する。~~ Loop 3bで完了（第23章）。
4. `saveThreadMeta()`（kind 41自己編集）の楽観的更新を候補プール（`metadataUpdateCandidates`）経由に統合し、自己発行イベントも同じresolver経路で扱う（Phase 2 kind 41編集強化、第16.10節と合わせて検討）。
5. `ChannelController`向けのテスト基盤（NostrRepository/ChannelCacheStoreのfake化）導入を検討する。
6. ~~第22章の方針により、Loop 2で実装したRoom関連コードはLoop 3.5で置換する。~~ Loop 3.5で完了。
7. L3a-S01はLoop 3.5のSimulator確認で一部確認済み（kind 40+41のチャンネルで一覧・画面ともkind 41のaboutが反映される）。kind 40のみ／同時刻複数kind 41のケースは未確認のまま。
8. Loop 3.5の未確認シナリオ: L35-S12（ライブ受信による未読加算・未開封チャンネルの「新着あり」表示。新着待ち）、通信断・キャッチアップ失敗時の前回値維持（第16.12.5節、障害注入が必要）、自分の最新投稿の削除によるプレビュー除去（kind 5 送信が必要）、チャンネル新規作成（kind 40 送信が必要）。
9. 起動直後に `NostrRelay.disconnect` が一時リレー解放時の `JobCancellationException` をスタックトレース付きでログ出力している。Loop 3.5以前からの挙動で機能影響はないが、ログのノイズとして別途整理する（Note）。
10. 未読件数の対象は選択中の単一リレーのまま（第22.4節4）。Loop 3bで再検討し、現状維持とした（一覧は数十〜数百チャンネルを扱うため、チャンネルごとの推奨リレーへ問い合わせると外部接続数が膨らむ。第11章の接続数上限の方針と両立しない）。
11. Loop 3bで判明した `NostrRepository` の既存挙動（Note）: 接続待ち中に `subscribe()` した購読は、接続直後の再同期で同じ REQ がもう一度送られ、一部リレーが `Duplicate subscription` の NOTICE を返す。`auth-required` で CLOSED を返すリレー（NIP-42 未対応）では購読ごとに CLOSED が2回届く。いずれも無限ループにはならない（計30件で止まることを確認）。チャンネル固有の問題ではないため別途整理する。
12. Loop 3bの未確認シナリオ: 開いている画面で実際に kind 41 を受信して推奨リレーが変わるケース（他者のチャンネル編集が必要）。同等の経路（保存済み kind 40 → 受信 kind 41 で推奨リレーが 0→6 件）は L3b-S02 で確認済み。
13. `ChannelController` の結合テストは引き続き無い（第21.2節5）。Loop 3bでは判定ロジックを `ChannelRelayPlanner`・`shouldKeepCachedMetadata`・`ChannelHistory.supplement` の純粋関数へ切り出して単体テストした。

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
- 未読件数は永続化しない。対象は現在選択中の単一リレーに限定し、起動時に「対象channelIdをチャンク分割した一括問い合わせ」でキャッチアップし、以後はライブ受信のたびにメモリ上で加算する。未開封チャンネルは件数を出さず「新着あり」フラグのみ表示する(第16.12.1〜16.12.5節)。
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
3. 未読キャッチアップの`CHUNK_SIZE`(初期値200)と`CATCH_UP_LIMIT`(初期値1000)は暫定値であり、実際のリレー応答(フィルターサイズ上限、`limit`充足時の挙動)を見て調整する(第16.12.1節)。
4. 未読機能を`ChannelRelayContext`(Loop 3b)導入後も選択中リレー単一スコープのままにするか、推奨リレーまで対象を広げるかは、Loop 3b着手時に再検討する(第16.12節)。

### 22.5 Loop 3.5: ローカル永続化の簡素化（実装）

- 状態: 完了（L35-S12 のみ保留。`f42dca2`、ブランチ `nip28-loop3.5-local-state`）
- 対象:
  - `ChannelCacheStore`/`ChannelCacheDatabase`関連コードのRoom実装を撤去し、`ChannelLocalState`のJSON永続化へ置き換える。
  - `ChannelController`・`ChannelListViewModel`の呼び出し元を新Store APIへ配線し直す。
  - 起動時キャッチアップ購読とメモリ上未読加算を`ChannelListViewModel`へ実装する。
  - 新設計の単体テスト(JSON永続化、未読キャッチアップの集計ロジック)を追加する。
- 対象外: Loop 3b(session化・kind 41二段階再購読)。kind 43/44/10005(Phase 3)。旧 Room DB からのデータ移行(L35-D2)。
- 開始時commit: `b265bfa`
- 対象ファイル:
  - 新規: `network/ChannelLocalStore.kt`（旧 `ChannelCacheStore.kt` を置換）、`ui/channel/ChannelUnreadTracker.kt`、`androidMain`/`iosMain` の `LegacyChannelCache.*.kt`、テスト `ChannelLocalStoreTest.kt`・`ChannelUnreadTrackerTest.kt`
  - 変更: `ChannelListViewModel.kt`、`ChannelController.kt`、`ChannelListScreen.kt`（未読バッジ）、`ChannelScreen.kt`（既読化不具合の修正）、`AppSessionCoordinator.kt`（prune → 旧DB削除）、`composeApp/build.gradle.kts`・`build.gradle.kts`・`gradle/libs.versions.toml`（Room/KSP/SQLite 依存の削除）
  - 削除: `mobileMain` の `ChannelCacheStore.mobile.kt`・`network/cache/*`、`android/iosMain` の `ChannelCacheDatabaseBuilder.*.kt`、`composeApp/schemas/`

#### 設計レビュー

| ID | 重大度 | 指摘 | 対応 |
| --- | --- | --- | --- |
| L35-D1 | Major | 第16.12節の `ChannelLocalState` には観測元リレーがなく、一覧のリレー別絞り込み（旧 `channel_relays`）が失われる | `observedRelays` を追加。`observe(relayUrl)` で絞り込む |
| L35-D2 | Minor | Room 撤去で旧DBのお気に入り・既読位置が失われる | 旧DB（83件中お気に入り0件・既読行3件）を確認し、移行しないと判断。起動時に旧DBファイルを削除する |
| L35-D3 | Minor | 未開封チャンネルの「新着あり」を `latestMessage` 有無でも立てると、ほぼ全件に付く | セッション中のライブ観測だけで判定する（第16.12.4節を更新） |
| L35-D4 | Note | 既読位置の保存が `relayUrl != null` の場合に限られていた（Room は実際にはリレー非依存） | ストアがリレー非依存のため条件を外した。リレー指定なしで開いた画面でも既読化される |
| L35-D5 | Note | 一覧の活動取得はチャンネルごとに最大 100〜200 件を取得して DB へ書いていた（未読件数算出のため） | 未読はキャッチアップで数えるため `limit = 1`（プレビュー用の最新1件）へ削減 |

#### 実装レビュー

| ID | 重大度 | 指摘 | 対応 |
| --- | --- | --- | --- |
| L35-R1 | Major | 一覧の kind 40 取得のたびに kind 41 由来の name/about を kind 40 の値で上書きする（旧 `upsertChannel` と同じ挙動を引き継ぐところだった） | `recordChannelCreate` は kind 41 由来のメタデータを維持。単体テスト追加 |
| L35-R2 | Minor | 別リレーで古い kind 41 しか届かないと保存済みメタデータが巻き戻る | `(createdAt, id)` で新しい方を維持。同時刻 tie-break もテスト |
| L35-R3 | Note | キャッチアップの since を全体の最小値にすると取得範囲が不要に広い | lastReadAt の近いもの同士でチャンク化し、チャンク単位の最小値にする |
| L35-R4 | Major | `limit = 1000` はリレー上限（strfry 既定 500 等）を超え、黙って切り詰められると飽和を検出できない | NIP-11 `max_limit`（取得不可なら 500）で limit を抑える |
| L35-R5 | Minor | 一覧で見えた全チャンネルを記録するため、JSON が無制限に増える | 1,000 件上限と退避順（非お気に入り・未開封・古い順）を追加 |
| L35-R6 | Major | （Simulator で発見）既読位置が離れたチャンネルが同じチャンクに入ると既読済みメッセージで limit が埋まり、全件が下限値表示・過少計上になる | 飽和したチャンクを半分に分けて再問い合わせ（REQ 総数 16 まで） |
| L35-R7 | Major | （Simulator で発見）初訪問のリレーでは状態の初回通知が空で、キャッチアップが対象 0 件のまま終わる | kind 40 初回ページ取得後に開始し、lastReadAt はストアから直接読む |
| L35-R8 | Major | （Simulator で発見、既存不具合）チャンネルを開いてスクロールせずに戻ると `markRead` が呼ばれない | viewport 通知の値に `navigating` を含め、初回移動の完了時に必ず再通知する |
| L35-R9 | Minor | 自分の最新投稿へ削除要求を送っても一覧プレビューに残る（Room は削除で再計算されていた） | `clearLatestMessage` を追加 |
| L35-R10 | Note | `CancellationException` の再送出、画面破棄時の書込喪失 | ストア内は再送出を確認。debounce 書込はストア自身のスコープで実行し、ViewModel 破棄の影響を受けない |

#### 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功（547件、失敗0） | 新規 `ChannelLocalStoreTest` 12件、`ChannelUnreadTrackerTest` 11件。既存 `ChannelHistoryTest` 18件も成功 |
| `./gradlew :composeApp:compileAndroidMain` | 成功 | Android の旧DB削除 actual を含む |
| `./gradlew check` | 成功 | `verifyNoDirectProfileSubscriptions` を含む |
| `xcodebuild … -configuration Debug`（DerivedData 再作成） | 成功 | `Task :composeApp` 49件で Gradle 実行を確認 |

途中で失敗したテストは1件（`ChannelUnreadTrackerTest.markingReadResetsCountButKeepsLaterLiveMessages`）。原因はテストの期待値の誤り（キャッチアップ5件 + ライブ2件 = 7件を6件としていた）で、実装は変更していない。

#### Simulatorテスト

環境: macOS 26.5 / Xcode 26.6 / iPhone 17 Simulator（iOS 26.5）/ Debug / ログイン済みテストアカウント / 選択リレー `wss://r.kojira.io`・`wss://yabu.me` / 実リレー接続 / 2026-09-24 12:06〜12:25。期待値は `nak req`（読み取りのみ）で同じリレーへ問い合わせて求めた。

| ID | シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- | --- |
| L35-S01 | 旧DBがある状態で新ビルドを起動する | 成功 | `Documents/torinos_channel_cache.db`（5.4MB）と `-wal`/`-shm`/`.lck` が削除された。旧DBは作業用に退避済み |
| L35-S02 | 空のストアでチャンネル一覧を開く | 成功 | r.kojira.io で50件表示。`channel_local_state_v1` に50件・43KB保存、`observedRelays` 記録 |
| L35-S03 | チャンネルを開く（ローカル履歴なし） | 成功 | さびれたスナックの履歴がリレーから表示された |
| L35-S04 | 再起動後に一覧を開く | 成功 | リレー応答前から kind 41 の about（2行目「ツケがたまってたり」）が表示された |
| L35-S05 | スクロールせずにチャンネルを開いて戻る | 修正後成功 | 修正前は `lastReadAt` が保存されず（L35-R8）。修正後はバーが既読色になり `lastReadAt` が保存された |
| L35-S06 | 既読位置を3h/14d/30d前に戻して再起動する | 修正後成功 | 修正前は `26+`/`3+`/`13+`（L35-R6）。修正後は 26/3/15 でリレー問い合わせ結果と一致 |
| L35-S07 | 未読バッジのあるチャンネルを開いて戻る | 成功 | バッジが消え、`lastReadAt` が更新された |
| L35-S08 | お気に入りを切り替える | 成功 | 即時に UI 反映、JSON に保存 |
| L35-S09 | 設定アプリへ切り替えて戻る | 成功 | 一覧・バッジ・お気に入りが維持された |
| L35-S10 | アプリを終了して再起動する | 成功 | お気に入り・既読状態は保存値から、未読件数はキャッチアップで再計算された |
| L35-S11 | 初訪問のリレー（yabu.me）へ切り替える | 修正後成功 | 修正前はバッジが出なかった（L35-R7）。修正後はスナック26・しおたぬ0でリレー結果と一致。r.kojira.io にしかないチャンネルは表示されない |
| L35-S12 | 一覧表示中に新着を受信する | 保留 | 12:21〜12:43 の約22分、yabu.me に kind 42 の新着が1件もなく未確認（`nak req -k 42 --since 1790220090` で0件を確認）。自分で投稿すると実リレーへの公開になるため行っていない。加算ロジックは `ChannelUnreadTrackerTest` の単体テストでのみ確認済み |

#### 設計書へのフィードバック

- 確定した仕様: 第16.12節の `ChannelLocalState`（`observedRelays`・`channelCreatedAt`・nullable の `lastReadAt`）、Store API、メタデータ保存規則、1,000件上限、旧DB削除。第16.12.1節のチャンク単位 since、NIP-11 による limit、飽和時の分割再問い合わせ、開始タイミング。第16.12.3節の件数算出方式。
- 変更した仕様: 第16.12.4節の「新着あり」判定から `latestMessage` 条件を削除（L35-D3）。第6章 FR-12 の since 記述をチャンク単位へ更新。
- 次ループへ送る課題: 第21.2節の 7〜10。未読キャッチアップの `CHUNK_SIZE`（200）は、実利用でチャンネル数が 200 を超えたときのリレーのフィルターサイズ上限を未確認（第22.4節3）。

## 23. Loop 3b: チャンネル購読の relay context 対応

- 状態: 完了（`8b827d6`）
- 対象: `ChannelController` の全ライブ購読・履歴取得を `ChannelRelayContext.readRelays` 由来の `RelayTarget.Explicit` へ切り替える。kind 41 による推奨リレー変更時の二段階再購読（FR-05、FR-08、第16.6〜16.7節）。
- 対象外: 投稿先（`writeRelays`）への配送と relay hint（Loop 4）。ヘッダー・投稿欄・詳細の表示（Loop 5）。チャンネル一覧の未読キャッチアップ（選択中リレーのまま、第21.2節10）。
- 開始時commit: `f42dca2`
- 対象ファイル:
  - 新規: `ui/channel/ChannelRelayPlanner.kt`、テスト `ChannelRelayPlannerTest.kt`・`ChannelCachedMetadataTest.kt`
  - 変更: `ChannelController.kt`、`ChannelViewModel.kt`（`relayContext`・`isRelayTransitioning`）、`ChannelHistory.kt`（`supplement`）、`ChannelListFetch.kt`（settle モード）、テスト `ChannelListFetchTest.kt`・`ChannelHistoryTest.kt`

### 23.1 設計レビュー

| ID | 重大度 | 指摘 | 対応 |
| --- | --- | --- | --- |
| L3b-D1 | Note | 第16.6節はセッション API への移行を想定していたが、互換 API が `Explicit` と同一 ID での target 更新（差分 REQ/CLOSE）にすでに対応している | 互換 API のまま target を張り替える方式にした。観測元リレー（`SubscriptionSignal.Event.relayUrl`）が必要になる Loop 5 の返信 hint で改めてセッション API を検討する |
| L3b-D2 | Major | `NostrRepository` は `RelayOutcome.Unavailable` を出さない。複数リレーの履歴取得で全リレーの EOSE を待つと、応答しない推奨リレー1件で各ページが 10 秒待ちの未完了になる | `fetchChannelEvents` に「最初の EOSE から 1.5 秒で打ち切る」settle モードを追加し、チャンネル画面の履歴取得で使う |
| L3b-D3 | Note | 第19章 未決5（切り替えの待機条件） | `Connected` または 3 秒に確定 |

### 23.2 実装レビュー

| ID | 重大度 | 指摘 | 対応 |
| --- | --- | --- | --- |
| L3b-R1 | Major | 履歴は kind 40 受信前の bootstrap リレーで取得するため、保存済みメタデータが無いチャンネルでは推奨リレーにしか無い最新メッセージが表示されない（ライブ受信だけが新リレーから届く） | 切り替え時に追加リレーへだけ最新ページを問い合わせ、`ChannelHistory.supplement()` で統合する |
| L3b-R2 | Major | （Simulator で発見）kind 40 が kind 41 より先に届くと、resolver は一時的に kind 40 を選ぶ。保存済みの kind 41 から表示が巻き戻り、所有者が kind 41 で外したリレー（`r.ydg.works`）へ一時接続して、kind 41 到着後に外す（gen=1 → gen=2 のばたつき）。表示の巻き戻りは Loop 3.5 から潜在していた | 保存済みの実効メタデータが `(createdAt, id)` で新しく所有者も一致する間は、解決結果を適用しない（`shouldKeepCachedMetadata`） |
| L3b-R3 | Note | 連続した kind 41 更新 | generation で古い切り替えの縮退更新を中止する。途中で中止された切り替えの新旧和集合は、次の切り替え開始時に次の和集合で置き換わる |
| L3b-R4 | Note | 画面終了時の解放 | 既存の `close()` が全購読 ID を閉じ、`reconcileActiveRelaysLocked` が他で使われていない一時リレー接続を切る。切り替えジョブも取り消す |

### 23.3 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功（560件、失敗0） | 新規: `ChannelRelayPlannerTest` 6件、`ChannelCachedMetadataTest` 3件、`ChannelListFetchTest` +3件（計7件）、`ChannelHistoryTest` +1件（計19件） |
| `./gradlew :composeApp:compileAndroidMain` | 成功 | |
| `./gradlew check` | 成功 | |
| `xcodebuild … -configuration Debug`（DerivedData 再作成） | 成功 | Simulator 確認用に `ENABLE_NETWORK_TRACE_LOGS` を一時的に `true` にしてビルドし、確認後に `false` へ戻した |

### 23.4 Simulatorテスト

環境: macOS 26.5 / Xcode 26.6 / iPhone 17 Simulator（iOS 26.5）/ Debug（ネットワークトレースログ有効）/ ログイン済みテストアカウント / 選択リレー `wss://yabu.me` / 実リレー接続 / 2026-09-24 13:25〜13:32。証跡はコンソールログの `[ChannelController] relayContext` と `[Repo] subscribe()` / REQ / CLOSE。

| ID | シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- | --- |
| L3b-S01 | ユーザー設定に無い推奨リレーを持つチャンネル（しおたぬの住処、kind 41 に6件）を開く | 成功 | `ch-msg` の target が `Explicit([yabu.me, r.kojira.io, nostream.ocha.one, nrelay.c-stellar.net, nostr-relay.moctane.net, nostr-relay-jp.moctane.net])`。未登録の4件へ新規接続し REQ を送信。関連購読（返信数・リアクション等）も同じ集合 |
| L3b-S02 | 保存済みメタデータを kind 40 のみ（推奨リレー0件）に戻して開く | 成功 | `initial read=1` → kind 41 解決後 `transition gen=1 added=5 read=6`。追加リレーへだけ `ch-hist-…-1` を送り60件受信（読み込み済み範囲より古いため統合対象外で、過去ページ取得に委ねられることを確認）。応答しない `nrelay.c-stellar.net` があっても画面は取得未完了エラーにならない |
| L3b-S03 | 上記チャンネルを閉じる | 成功 | 全購読の CLOSE を送信。未登録4リレーの WebSocket ループが取り消され、以後その4リレーとの通信0件 |
| L3b-S04 | 保存済み kind 41 と同じ推奨リレーを持つチャンネル（さびれたスナック）を開く | 修正後成功 | 修正前は kind 40 先着で `gen=1 added=1`（`r.ydg.works`）→ `gen=2` のばたつき（L3b-R2）。修正後は `initial read=2` のみで切り替え0回、表示も kind 41 のまま |
| L3b-S05 | 推奨リレーを持たないチャンネル（3001）を開く | 成功 | `read=1`（選択中の yabu.me のみ）で従来どおり表示。relays を持たない既存チャンネルの閲覧互換 |

### 23.5 設計書へのフィードバック

- 確定した仕様: 第16.6節「Loop 3b の実装で確定した事項」、第16.7節の待機条件・追加リレーの最新ページ補完、第19章 未決5。受け入れ条件「推奨リレーがユーザー設定外でも kind 42 を購読できる」を達成。
- 変更した仕様: 第16.6節のセッション API 前提を、互換 API での target 張り替えに変更（L3b-D1）。
- 次ループへ送る課題: 第21.2節の 11〜13。受け入れ条件「kind 41 のリレー変更を画面を開き直さずに購読と UI へ反映」は購読側のみ達成で、UI 側は Loop 5。Loop 4（投稿先・relay hint）では `relayContext.writeRelays` と `primaryHint` がすでに `UiState` と Controller にあるため、それを投稿 snapshot に使う。

## 24. Loop 4: チャンネル投稿の配送先・relay hint・リレー別結果

- 状態: 完了（未コミット）
- 対象: kind 42（チャンネル画面からの投稿）・kind 41（編集）・kind 40（新規作成と初回投稿）・kind 5（チャンネル内メッセージの削除要求）の送信先を `ChannelRelayContext.writeRelays` の投稿開始時 snapshot にし、NIP-28/NIP-10 形式の `root` marker と relay hint を付ける。リレー別の送信結果を `UiState` まで伝える（FR-03、FR-04、FR-06、FR-07、第16.8〜16.11節）。
- 対象外: 結果の画面表示（投稿先の概要・失敗リレーの一覧、Loop 5）。kind 40 作成時の推奨リレー選択 UI と kind 41 の picture/relays 編集 UI（Loop 5。本ループでは推奨リレーの初期値＝ユーザーの書き込みリレーを使い、編集では既存の relays を保持する）。通常投稿画面からのチャンネル返信（reply/p タグ、Loop 5）。チャンネル内のリアクション・リポストの送信先（従来どおりユーザーの書き込みリレー）。
- 開始時commit: `8b827d6`
- 対象ファイル:
  - 新規: `ui/channel/ChannelPublishState.kt`（`ChannelPublishUiState`・`ChannelPublishContext`）、テスト `ChannelPublishStateTest.kt`・`model/ChannelContentTest.kt`
  - 変更: `ui/timeline/SignedEventPublisher.kt`（指定リレー投稿・リレー別結果・逐次通知）、`network/NostrRepository.kt`（`publishToRelaysUntilFirstSuccess` に `awaitAcceptance`）、`model/ChannelMeta.kt`（`toChannelContent`）、`ChannelController.kt`、`ChannelViewModel.kt`（`publishState`）、`ChannelListViewModel.kt`、`ChannelListScreen.kt`（初回投稿の再送ボタン）、`network/ChannelLocalStore.kt`（tie-break）、テスト `SignedEventPublisherTest.kt`・`ChannelLocalStoreTest.kt`・`ChannelCachedMetadataTest.kt`

### 24.1 設計レビュー

| ID | 重大度 | 指摘 | 対応 |
| --- | --- | --- | --- |
| L4-D1 | Note | 第8.2節の `SignedPublishResult.Published` に `RelayPublishResult` を持たせる案 | 採用。全件失敗時も `Failed.relayResult` にリレー別の理由を残す。`relayUrls == null` の既存呼び出しは従来どおりユーザーの書き込みリレーへ送る |
| L4-D2 | Major | 既存の指定リレー投稿は「ソケットへ送れた」ことを成功としており、`auth-required` などで拒否したリレーも成功に数える。リレー別結果の表示（FR-10）と矛盾する | チャンネル系の投稿は OK 応答での受理を成功とする（`awaitAcceptance = true`） |
| L4-D3 | Minor | kind 40 作成で「kind 40 成功・初回 kind 42 失敗」のとき、ダイアログを閉じると本文が失われ、再試行するとチャンネルが二重にできる | ダイアログを残して送信済みの kind 40 を保持し、ボタンを「投稿を再送信」に変えて kind 42 だけを再送する。名前・説明は編集不可にする |
| L4-D4 | Note | 削除要求（kind 5）の送信先 | 削除対象のメッセージを配送したチャンネルの書き込み先へ送る |

### 24.2 実装レビュー

| ID | 重大度 | 指摘 | 対応 |
| --- | --- | --- | --- |
| L4-R1 | Major | kind 41 編集の content が name/about/picture だけで、保存すると `relays` が消える（resolver は空の relays を採用し、以後の購読・投稿先から推奨リレーが外れる） | 実効メタデータを copy して完全な `ChannelMeta` を出力する `toChannelContent()` に統一。kind 40 作成も同じ関数を使う |
| L4-R2 | Major | Loop 3.5/3b で追加した `ChannelLocalStateStore.isNewer` は同時刻で ID の大きい方を新しいとしていたが、`ChannelMetadataResolver` は小さい方を選ぶ（NIP-01 の慣習）。同時刻の kind 41 が2件あると一覧と画面で表示が食い違い、`shouldKeepCachedMetadata` が resolver の選択を永久に上書きする | resolver と同じ「ID が小さい方を新しいとみなす」に統一し、テストを修正 |
| L4-R3 | Major | OK 応答をすべてのリレーから待つと、応答しない推奨リレーが1件あるだけで投稿ボタンが 10 秒（PUBLISH_TIMEOUT）塞がる | 最初の受理で戻る `publishToRelaysUntilFirstSuccess` に `awaitAcceptance` を追加して使い、残りのリレーの結果は `onRelayResult` で届けて `publishState` へ統合する（全件揃うまで `Sending`） |
| L4-R4 | Minor | 送信後の表示がリレーからの echo 待ち | 成功イベントを `ChannelHistory.receive` と一覧プレビューへ即時反映する（event ID で重複排除） |
| L4-R5 | Minor | kind 41 編集の権限確認が UI の表示条件だけ | `saveThreadMeta()` 冒頭で `ownPubkey == 所有者` を再検証する（第16.10節） |
| L4-R6 | Note | 自己発行 kind 41 の楽観的更新が resolver を通らない（第21.2節4） | 成功した kind 41 を候補へ追加して resolver を再実行する |
| L4-R7 | Note | 既存の呼び出し側が末尾ラムダで `publisher` を渡しており、引数追加でコンパイルエラー | 新しい `relayPublisher` 引数を `publisher` の前に置いた |
| L4-R8 | Note | 後から届くリレー結果が前の投稿の状態を上書きしうる | 投稿ごとの sequence で古い通知を捨てる |

### 24.3 自動テスト

| コマンド | 結果 | 備考 |
| --- | --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test` | 成功（569件、失敗0） | 新規: `ChannelPublishStateTest` 4件、`ChannelContentTest` 3件、`SignedEventPublisherTest` +1件、`ChannelCachedMetadataTest` +1件。`ChannelLocalStoreTest` の同時刻ケースを resolver の規則へ修正 |
| `./gradlew :composeApp:compileAndroidMain` | 成功 | |
| `./gradlew check` | 成功 | |
| `xcodebuild … -configuration Debug`（DerivedData 再作成） | 成功 | 確認中だけ `ENABLE_NETWORK_TRACE_LOGS = true`。確認後に戻した |

途中のコンパイルエラーは2件（公開関数が internal 型を露出、既存テストの末尾ラムダ）。いずれも実装側を修正し、テストは変更していない。

### 24.4 Simulatorテスト

ユーザーの許可を得て、テスト用チャンネルを実リレーへ作成して実施した。

環境: macOS 26.5 / Xcode 26.6 / iPhone 17 Simulator（iOS 26.5）/ Debug（ネットワークトレースログ有効）/ テストアカウント `aba863f5…` / 選択リレー `wss://yabu.me` / 書き込みリレー5件（yabu.me, r.kojira.io, relay-jp.nostr.wirednet.jp, nos.lol, relay.damus.io）/ 2026-09-24 13:44〜13:50。検証は `nak req` で各リレーから該当イベントを取得して行った。

| ID | シナリオ | 結果 | 証跡・備考 |
| --- | --- | --- | --- |
| L4-S01 | テスト用チャンネルを本文付きで作成する | 成功 | kind 40 `7d722b05…` の content に `relays`（書き込みリレー5件）が入る。初回 kind 42 `a031805b…` のタグは `["e", <kind40>, "wss://yabu.me", "root"]`。両方とも5リレーすべてが OK=true。作成後にチャンネル画面へ遷移 |
| L4-S02 | チャンネル画面から投稿する | 成功 | kind 42 `a44bb53e…`、タグは root + relay hint。5リレーが OK=true。送信から約1.2秒で入力欄が空になり、echo を待たずに一覧へ表示された |
| L4-S03 | チャンネル名を編集する | 成功 | kind 41 `19b67bcb…` のタグは `["e", <kind40>, "wss://yabu.me", "root"]`、content は name 以外（about・picture・relays 5件）を保持（L4-R1 の修正確認）。ヘッダーと一覧が編集後の名前に更新 |
| L4-S04 | テスト用チャンネルへ削除要求を送る | 成功 | kind 5 `324ce9b9…` のタグは `e` と `k=40`。5リレーが OK=true、一覧から消えた（L0-S08 も兼ねる） |
| L4-S05 | 一部失敗・全件失敗 | 未実施 | 応答しないリレー・拒否するリレーを書き込み先に含める障害注入が必要なため、単体テスト（`ChannelPublishStateTest`・`SignedEventPublisherTest`）のみで確認 |

備考:
- 作業中、日本語 IME のため cliclick の文字入力が化けた。作成ボタンは押さずにダイアログを取り消し、以後は `simctl pbcopy` と長押しメニューの「ペースト」で入力した（化けた入力は送信されていない）。
- 作成から約1分後、他ユーザー（`b737d876…`）がテスト用チャンネルに「👍」を投稿した。一覧のライブ受信でプレビューが更新されることも確認できた。チャンネルの削除要求は送ったが、他ユーザーの投稿と自分の kind 42 2件は各リレーに残りうる（削除要求は NIP-09 上もリレーの対応次第）。

### 24.5 設計書へのフィードバック

- 確定した仕様: `SignedPublishResult` の結果伝播（第16.9節）。チャンネル系の投稿は OK 受理を成功とし、最初の受理で戻って残りを逐次反映する。kind 40 初回投稿失敗時の再送（第16.11節 手順5）。kind 40/41 の content は常に完全な `ChannelMeta`（relays を含む）。同時刻の決定規則は resolver（小さい ID）に統一。
- 変更した仕様: 第16.4節「最大の `(created_at, id)`」の記述は実装（created_at 最大、同時刻は ID 最小）と異なる。実装側を正とし、Loop 1 以降の resolver と揃えた。
- 次ループへ送る課題:
  1. Loop 5: `publishState` の表示（投稿先の概要、`2件中1件に送信しました`、失敗リレーの一覧）、kind 40 作成時の推奨リレー選択、kind 41 の picture/relays 編集 UI、通常投稿画面からのチャンネル返信（reply/p タグと hint）。
  2. チャンネル内のリアクション・リポスト（`NoteEngagementCoordinator`）は従来どおりユーザーの書き込みリレーへ送っている。チャンネルの書き込み先へ揃えるかは Loop 5 で判断する。
  3. L4-S05（一部失敗・全件失敗の実リレー確認）は障害注入の手段を用意してから実施する。

