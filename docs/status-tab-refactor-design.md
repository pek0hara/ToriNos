# ステータスタブ リファクタ設計

## 1. 目的

ステータスタブの表示、Nostr購読、置換可能イベントの集約、プロフィール取得、投稿処理を、
画面のライフサイクルと一致し、単体テスト可能な構造へ整理する。

本リファクタでは、特に次の性質を保証する。

1. ステータスタブが表示されている間だけ、選択中の1リレーを購読する。
2. リレー切り替え時に旧リレーの購読と関連Jobを終了してから、新しい購読を開始する。
3. kind `30315`のparameterized replaceable eventを、受信順に依存せず決定的に集約する。
4. カテゴリの選択状態と一覧の表示結果を一致させる。
5. 投稿失敗を成功として画面へ反映しない。
6. ステータスタブとプロフィール画面で、イベント解析・生成・投稿規則を共有する。

## 2. 対象範囲

### 2.1 対象

- `ui/status/StatusScreen.kt`
- `ui/status/StatusViewModel.kt`
- `ui/status/StatusComposerSheet.kt`
- `ui/profile/ProfileGeneralStatus.kt`
- `ui/profile/MyProfileViewModel.kt`のステータス関連処理
- `ui/profile/UserProfileViewModel.kt`のステータス関連処理
- kind `30315`の解析、比較、生成、投稿に必要な共通コード
- ステータス用のcommon test

### 2.2 非目標

本リファクタでは次を行わない。

- ステータスタブの大幅なデザイン変更
- 新しいカテゴリ仕様や「すべて」チップの追加
- 複数リレーをマージしたステータスタイムラインへの変更
- Nostr購読基盤全体の再設計
- リレーの`OK`応答を待つ投稿仕様への変更
- ステータスの永続キャッシュ追加
- プロフィール画面全体のViewModel再設計

リレー受理確認が必要になった場合は、現行の送信完了を成功とする仕様から独立した変更として扱う。

## 3. 現状と課題

### 3.1 ViewModelの寿命と画面の可視期間が一致しない

`StatusScreen`は`status-$activeRelayUrl`をキーとして、リレーごとに異なる`StatusViewModel`を生成する。
`accountSessionViewModel`の所有者はアカウントセッション単位であり、サービス内のタブ切り替えや
リレー切り替えだけでは`ViewModel`が破棄されない。

一方、`StatusViewModel`は`init`で次の処理を開始し、`onCleared()`まで継続する。

- 選択リレーのステータス購読
- 自分のgeneralステータス購読
- EOSE監視
- プロフィール変更監視
- ミュート変更監視
- 60秒ごとの期限切れ再評価

このため、リレーを切り替えるたびに旧リレーのViewModel、購読、Job、受信データが
セッション終了まで残る可能性がある。サービス内で別タブへ移動しても同様である。

### 3.2 カテゴリ未選択時の意味が画面と一致しない

現在は`selectedCategories.isEmpty()`の場合に全カテゴリを表示する。一方、チップはすべて未選択と
表示されるため、操作状態と結果が一致しない。

また、受信によって見つかったカスタムカテゴリは選択集合へ自動追加されない。この挙動自体は
「初期表示をgeneralとmusicに限定する」という意味で維持できるが、未選択を全件表示として扱うと
カスタムカテゴリまで突然表示される。

### 3.3 置換可能イベントの新旧判定が不完全

現在は`created_at`だけを比較し、同時刻のイベントを先着順で採用する。NIP-01の置換可能イベントは、
`created_at`が同じ場合にイベントIDも比較し、IDが辞書順で小さいイベントを採用する。

また、空本文を削除表現として受信した場合にMapからエントリ自体を削除している。この状態で、後から
古い非空イベントが届くと、削除済みステータスが復活する。最新バージョンの記録と、画面に表示する
ステータスを分けて保持する必要がある。

### 3.4 投稿中のローカル反映とネットワーク結果が一致しない

ステータスタブでは、署名後、リレー送信より先にローカル集約へイベントを追加する。送信に失敗しても
追加済みイベントを戻さないため、エラー表示と一覧の内容が矛盾する。

プロフィール画面では、ローカル状態を成功へ更新し、完了カウンタを増やした後にリレー送信を行っている。
送信失敗時には編集画面がすでに閉じ、エラーが利用者から見えなくなる可能性がある。

### 3.5 ステータスのドメイン規則が重複している

次の規則が`StatusViewModel`、`ProfileGeneralStatus`、`MyProfileViewModel`へ分散している。

- kind `30315`と`d`タグ
- expirationタグの解析
- reference URLタグの解析・生成
- カスタム絵文字タグの解析・生成
- generalステータスの判定
- 空本文による削除
- URL抽出
- 投稿完了状態

実装ごとにgeneralの大文字小文字、URL件数、楽観更新のタイミングなどが異なり、同じイベントでも
画面によって結果が変わり得る。

### 3.6 一時的なUIイベントを永続状態のカウンタで表している

`publishCompletedCount`は投稿完了通知として使われる一方、`StateFlow`に永続状態として残る。
画面の再生成時にも過去の完了値が見えるため、ダイアログを閉じる処理が再実行される可能性がある。

## 4. 設計原則

1. ネットワーク購読の所有者と画面の可視期間を一致させる。
2. ViewModelの存在と、ネットワーク処理が動作中であることを分離する。
3. イベント解析・新旧判定・一覧導出は副作用のない純粋処理とする。
4. 最新イベントの保存と、表示可能イベントの導出を分離する。
5. 投稿は、ネットワーク送信成功後にだけローカル成功として確定する。
6. 画面間で共通のNostr規則を一つの実装へ集約する。
7. Composeには表示状態と利用者操作だけを残し、プロトコル規則を置かない。
8. 構造整理とUI機能追加を同じ変更へ含めない。

## 5. リファクタ後の構造

ステータス機能を、ドメイン、データアクセス、画面状態の3層へ分ける。

```text
StatusScreen / MyProfileScreen
            |
            v
StatusViewModel / MyProfileViewModel
            |
            +-- StatusSubscriptionController
            +-- StatusPublisher
            |
            v
StatusEventCodec + StatusEventReducer
            |
            v
NostrRepository / AccountSigner / ProfileRepository
```

### 5.1 共通ドメイン

新しいパッケージ`com.nostr.torinos.status`を追加し、UIから独立した次の型を置く。

```kotlin
internal const val STATUS_EVENT_KIND = 30315
internal const val GENERAL_STATUS_IDENTIFIER = "general"
internal const val MUSIC_STATUS_IDENTIFIER = "music"

internal data class StatusAddress(
    val pubkey: String,
    val identifier: String,
)

internal data class StatusEntry(
    val event: NostrEvent,
    val address: StatusAddress,
    val content: String,
    val expiration: Long?,
    val referenceUrls: List<String>,
    val customEmojis: Map<String, String>,
)
```

`identifier`はparameterized replaceable eventのアドレス要素なので、大文字小文字を区別する。
組み込みカテゴリとして扱うのは正規形の`general`と`music`だけとする。Composerから組み込みカテゴリを
投稿する場合は必ず小文字の正規形を生成する。カスタム値は前後空白だけを除去し、文字列自体は変更しない。

### 5.2 StatusEventCodec

`StatusEventCodec`はkind `30315`の解析とイベント生成用タグの構築を担当する。

```kotlin
internal object StatusEventCodec {
    fun parse(event: NostrEvent): StatusEntry?

    fun buildTags(
        identifier: String,
        content: String,
        expiration: Long?,
        explicitReferenceUrl: String?,
        customEmojis: List<CustomEmoji>,
    ): List<List<String>>
}
```

解析規則は次のとおり。

- kindが`30315`でなければ`null`
- `d`タグがない場合は、互換性のため`general`として扱う
- 空の`d`タグも`general`として扱う
- expirationが数値でない場合は期限なしとして扱う
- `r`タグは空値を除外し、受信順を維持したまま重複排除する
- 本文はイベントの署名対象なので、受信時にはtrimして別内容へ変更しない
- 空白だけの本文は表示上の削除として扱うが、Reducerには最新バージョンとして渡す

送信時には現在と同様に本文と入力値の前後空白を除去する。本文中URLの抽出は既存の
`ui/components/UrlExtraction.kt`を共通利用し、ステータス専用の正規表現を削除する。

### 5.3 StatusEventReducer

Reducerはアドレス単位で最新イベントを保持する。

```kotlin
internal data class StatusSnapshot(
    val latestByAddress: Map<StatusAddress, StatusEntry> = emptyMap(),
)

internal object StatusEventReducer {
    fun reduce(
        snapshot: StatusSnapshot,
        candidate: StatusEntry,
    ): StatusSnapshot

    fun visibleStatuses(
        snapshot: StatusSnapshot,
        nowEpochSeconds: Long,
        selectedCategories: Set<String>,
        mutedPubkeys: Set<String>,
    ): List<StatusEntry>
}
```

候補が既存イベントより新しい条件は次のとおり。

```kotlin
candidate.event.createdAt > current.event.createdAt ||
    (
        candidate.event.createdAt == current.event.createdAt &&
            candidate.event.id < current.event.id
    )
```

空本文や期限切れイベントも`latestByAddress`には残す。表示一覧を導出する段階で次を除外する。

- 本文が空白だけ
- `expiration <= nowEpochSeconds`
- ミュート中のpubkey
- 選択されていないカテゴリ

これにより、削除イベントの後に古いイベントが到着しても復活しない。

### 5.4 StatusPublisher

ステータスタブとプロフィール画面の投稿規則を統一する。

```kotlin
internal data class PublishStatusCommand(
    val identifier: String,
    val content: String,
    val expiration: Long?,
    val referenceUrl: String?,
)

internal sealed interface StatusPublishTarget {
    data class SelectedRelay(val relayUrl: String) : StatusPublishTarget
    data object WritableRelays : StatusPublishTarget
}

internal sealed interface StatusPublishResult {
    data class Published(val event: NostrEvent) : StatusPublishResult
    data class Rejected(val message: String) : StatusPublishResult
}
```

`StatusPublisher`は次の順番で処理する。

1. 入力を検証・正規化する。
2. 共通Codecでタグを構築する。
3. `AccountSigner`で署名する。
4. 指定されたtargetへ送信する。
5. 送信APIが成功した場合だけ`Published(event)`を返す。
6. 署名または送信失敗時は`Rejected`を返す。

ステータスタブは`SelectedRelay`、プロフィール画面は`WritableRelays`を指定する。初期実装では現行と同じく
WebSocketへの送信完了を成功とし、リレーの`OK`応答は待たない。

`WritableRelays`は最初の1リレーへの送信成功でUI上の投稿を完了し、残りのリレーへの送信は
`NostrRepository`のスコープで継続する。未接続リレーの接続タイムアウトによって、ステータスの
保存・削除シートが送信中のまま塞がれないようにする。全リレーが失敗した場合だけ`Rejected`を返す。

### 5.5 外部依存の境界

ViewModelのテストからグローバルなRepositoryを直接操作しないよう、購読、プロフィール、時刻を
コンストラクタ依存として渡す。本番のデフォルト実装だけが既存のシングルトンへ委譲する。

```kotlin
internal interface StatusSubscriptionGateway {
    fun events(subscriptionId: String): Flow<NostrEvent>
    fun eose(subscriptionId: String): Flow<Unit>

    suspend fun subscribe(
        subscriptionId: String,
        filter: NostrFilter,
        relayUrl: String,
    )

    fun close(subscriptionId: String)
}

internal interface StatusProfileGateway {
    fun observeChanges(): Flow<Set<String>>
    fun getCached(pubkeys: Set<String>): Map<String, NostrProfile>
    suspend fun ensureProfiles(pubkeys: Set<String>, relayHint: String)
}
```

`Clock`も注入し、期限境界と24時間後の計算を固定時刻で検証できるようにする。インターフェース追加が
既存設計に対して過剰になる場合は、同じ契約を関数引数の束として渡してもよい。ただしテストから
`NostrRepository`、`ProfileRepository`、`Clock.System`を直接差し替える構造にはしない。

## 6. StatusViewModel設計

### 6.1 単一ViewModel

`StatusScreen`はリレーURLを含まない固定キーで、一つの`StatusViewModel`を取得する。

```kotlin
val viewModel = accountSessionViewModel<StatusViewModel>(key = "status") { session ->
    StatusViewModel(accountSession = session)
}
```

リレーURLはコンストラクタへ固定せず、可視期間開始時に渡す。

### 6.2 可視期間API

```kotlin
fun start(relayUrl: String)
fun stop()
```

`StatusScreen`は`LifecycleStartEffect(viewModel, activeRelayUrl)`から呼び出す。

```kotlin
LifecycleStartEffect(viewModel, activeRelayUrl) {
    viewModel.start(activeRelayUrl)
    onStopOrDispose { viewModel.stop() }
}
```

`start()`の規則は次のとおり。

- 同じリレーですでに開始済みなら何もしない。
- 異なるリレーが開始済みなら、旧セッションを`stop()`してから開始する。
- セッションごとに一意な購読IDと世代番号を発行する。
- 前回と異なるリレーなら一覧、カテゴリ、プロフィール、ロード状態を新リレー用に初期化する。
- `stop()`後に同じリレーへ復帰した場合は表示スナップショットを保持し、バックグラウンドで再購読する。
- イベント、EOSE、タイムアウト、プロフィール、ミュート、期限更新のJobを同じセッションJob配下に置く。
- 旧世代から遅れて届いた結果は状態へ適用しない。

`stop()`は冪等とし、次を必ず行う。

- セッションJobをキャンセルする。
- ステータス購読と自分のgeneral購読を閉じる。
- 実行中のプロフィール取得バッチをキャンセルする。
- `isActive`をfalseにする。
- 表示用スナップショットは再表示の高速化のため保持してよいが、ネットワーク処理は残さない。

`onCleared()`も`stop()`を呼び、アカウントセッション終了時の安全網とする。

### 6.3 UI状態

```kotlin
internal enum class StatusLoadState {
    Idle,
    Loading,
    Ready,
}

internal sealed interface StatusPublishState {
    data object Idle : StatusPublishState
    data object Publishing : StatusPublishState
    data class Succeeded(val eventId: String) : StatusPublishState
    data class Failed(val message: String) : StatusPublishState
}

data class StatusUiState(
    val statuses: List<StatusEntry> = emptyList(),
    val ownGeneralStatus: StatusEntry? = null,
    val availableCategories: List<String> = listOf("general", "music"),
    val selectedCategories: Set<String> = setOf("general", "music"),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val loadState: StatusLoadState = StatusLoadState.Idle,
    val publishState: StatusPublishState = StatusPublishState.Idle,
)
```

`isInitialLoad`、`isPublishing`、`publishCompletedCount`、`errorMessage`の独立フィールドを状態機械へ置き換える。

投稿成功時は`Succeeded(eventId)`へ遷移する。画面がダイアログを閉じた後、
`consumePublishResult(eventId)`を呼んで`Idle`へ戻す。IDが現在値と一致する場合だけ消費することで、
古いEffectが新しい投稿結果を消費しないようにする。

### 6.4 投稿時の状態更新

投稿フローは次の順番とする。

```text
submit
  -> Publishing
  -> sign
  -> send
  +-- success -> Reducerへeventを適用 -> Succeeded(eventId)
  +-- failure -> 一覧を変更しない       -> Failed(message)
```

送信成功後のReducer適用によって、リレーからのエコーバックを待たずに一覧を更新する。同じイベントが
購読から再度届いてもReducerが同一バージョンとして無視する。

## 7. カテゴリ仕様

カテゴリは複数選択フィルタとして扱い、次の意味に統一する。

- 初期選択は`general`と`music`
- 選択集合に含まれるカテゴリだけを表示する
- 空集合は0件表示とする
- 空集合時は「カテゴリが選択されていません」と表示する
- 受信したカスタムカテゴリは`availableCategories`へ追加するが、自動選択しない
- カテゴリの比較は`d`タグの値どおり大文字小文字を区別する
- `availableCategories`は組み込みカテゴリを先頭にし、カスタムカテゴリは安定した辞書順にする

将来「すべて」を追加する場合は、空集合へ別の意味を持たせず、明示的なフィルタモードを追加する。

## 8. プロフィール画面との統合

`ProfileGeneralStatus`はプロフィールUI専用の表示モデルとして残してよいが、Nostrイベントの解析は
`StatusEventCodec`へ一本化する。

```kotlin
internal fun StatusEntry.toProfileGeneralStatus(): ProfileGeneralStatus?
```

変換条件は`address.identifier == "general"`とし、大文字小文字を無視しない。
空本文・期限切れの除外は共通の導出処理を利用する。

`MyProfileViewModel.publishStatus()`は直接署名・送信せず`StatusPublisher`を使用する。成功後にだけ
`generalStatus`を更新し、画面を閉じる。失敗時は編集画面を維持してエラーを表示する。

`UserProfileViewModel`と`MyProfileViewModel`の同時刻判定も、共通の置換可能イベント比較へ移行する。

## 9. Composerの整理

`StatusComposerSheet`のUI挙動は維持し、入力と検証を純粋な`StatusDraft`へ分離する。

```kotlin
internal data class StatusDraft(
    val category: StatusCategoryDraft,
    val content: String,
    val expiration: StatusExpirationDraft,
    val referenceUrl: String,
)

internal sealed interface StatusDraftValidation {
    data object Valid : StatusDraftValidation
    data class Invalid(val reason: String) : StatusDraftValidation
}
```

純粋処理として次をテスト可能にする。

- 組み込み／カスタムカテゴリからidentifierへの変換
- 空本文の拒否。ただし削除操作は明示的に空本文を送信できる
- 24時間後の期限計算
- カスタム期限が未来かどうかの検証
- DatePickerの日付とTimePickerの時刻からローカル時刻を生成する変換
- reference URLのtrim

メニューやPickerの開閉、フォーカス、IME制御はComposeローカル状態のまま残す。

## 10. プロフィール取得

プロフィール取得は現在のバッチ方式を維持するが、可視購読セッションの子Jobとして扱う。

- `pendingPubkeys`はセッション開始時に空にする。
- キャッシュ済みプロフィールは即時反映する。
- 300msのデバウンス後に、その時点の未取得pubkeyを一度だけ要求する。
- 取得要求へ渡したpubkeyはpending集合から除去する。
- プロフィール変更通知は、現在表示候補またはプロフィールMapに存在するpubkeyとの積集合だけを反映する。
- リレー切り替え後に旧リレーのプロフィール取得完了が届いても、新世代へ直接状態を適用しない。

プロフィール本体は共有キャッシュなので再利用してよい。ここで世代分離するのは、画面状態へ適用する処理である。

## 11. ファイル構成

想定する最終構成は次のとおり。

```text
composeApp/src/commonMain/kotlin/com/nostr/torinos/
├── status/
│   ├── StatusEvent.kt
│   ├── StatusEventCodec.kt
│   ├── StatusEventReducer.kt
│   └── StatusPublisher.kt
├── ui/status/
│   ├── StatusScreen.kt
│   ├── StatusViewModel.kt
│   ├── StatusComposerDraft.kt
│   └── StatusComposerSheet.kt
└── ui/profile/
    ├── ProfileGeneralStatus.kt
    ├── MyProfileViewModel.kt
    └── UserProfileViewModel.kt

composeApp/src/commonTest/kotlin/com/nostr/torinos/
├── status/
│   ├── StatusEventCodecTest.kt
│   ├── StatusEventReducerTest.kt
│   └── StatusPublisherTest.kt
└── ui/status/
    ├── StatusComposerDraftTest.kt
    └── StatusViewModelTest.kt
```

小さな型のためにファイルが過度に分散する場合は、`StatusEvent.kt`へアドレス、エントリ、比較関数を
まとめてよい。ただしCodec、Reducer、Publisherの責務は分離する。

## 12. テスト設計

### 12.1 Codec

- `d`タグあり／なし／空値を解析できる。
- expirationの正常値、不正値、境界値を解析できる。
- 複数`r`タグの順序を保ち、空値と重複を除外する。
- カスタム絵文字タグを解析・生成できる。
- 本文内URLと明示URLを重複なくタグへ変換する。
- 組み込みカテゴリは正規形で生成される。
- カスタムカテゴリは前後空白以外を変更しない。

### 12.2 Reducer

- 新しい`created_at`を採用する。
- 古い`created_at`を無視する。
- 同時刻ではIDが辞書順で小さいイベントを採用する。
- 同時刻イベントの到着順を逆にしても結果が同じになる。
- 空本文の最新イベントが古い非空イベントの復活を防ぐ。
- 期限切れ最新イベントが古い無期限イベントの復活を防ぐ。
- expirationが現在時刻と同じ場合は表示しない。
- ミュート中のpubkeyを表示しない。
- 選択カテゴリだけを表示する。
- カテゴリ空集合では0件になる。
- 作成時刻の降順で安定して並ぶ。同時刻の表示順はイベントIDで固定する。

### 12.3 Publisher

- signerがない場合は送信しない。
- 空本文の通常保存を拒否する。
- 削除コマンドでは空本文を許可する。
- 選択リレー投稿は指定した1リレーだけへ送る。
- プロフィール投稿は書き込み可能リレーを使う。
- 送信成功時だけ`Published`を返す。
- 送信失敗時は署名済みイベントを成功として返さない。

### 12.4 ViewModelライフサイクル

- `start(A)`でAの購読が1組だけ開始される。
- 再度`start(A)`しても重複購読しない。
- `start(A)`後の`start(B)`でAを閉じてからBを開始する。
- `stop()`を複数回呼んでも安全である。
- `stop()`後の受信イベントは状態へ反映されない。
- 旧世代のEOSE、タイムアウト、プロフィール取得完了を無視する。
- タブ離脱中に期限更新ループが動作しない。
- 投稿失敗時に一覧と`ownGeneralStatus`を変更しない。
- 投稿成功時に一覧へ1回だけ反映し、成功結果を消費できる。

### 12.5 Composer

- general、music、カスタムカテゴリを正しくコマンドへ変換する。
- 空白だけの本文を無効とする。
- 過去のカスタム期限を無効とする。
- 日付境界と端末タイムゾーンを正しく変換する。
- reference URLの前後空白を除去する。

## 13. 移行手順

### Phase 1: Characterization testと共通ドメイン抽出

1. 現行の解析・カテゴリ・投稿入力に対するcharacterization testを追加する。
2. `StatusAddress`、`StatusEntry`、Codec、比較関数を追加する。
3. `ProfileGeneralStatus`と`StatusViewModel`の解析処理を共通Codecへ移す。
4. 既存の専用URL抽出を共通`extractWebUrls()`へ置き換える。

この段階では購読ライフサイクルと画面状態を変更しない。

### Phase 2: Reducer導入と集約不具合修正

1. `rawStatuses`を`StatusSnapshot.latestByAddress`へ置き換える。
2. 同時刻のイベントID比較を導入する。
3. 空本文の最新イベントをtombstoneとして保持する。
4. カテゴリ選択、期限、ミュート、並び順を純粋な導出処理へ移す。
5. カテゴリ空集合を0件表示へ修正する。

Phase 2は一部の不具合修正を含むため、純粋な動作維持リファクタとは別コミットにしてもよい。

### Phase 3: 購読ライフサイクル修正

1. `StatusViewModel`を固定キーの単一インスタンスへ変更する。
2. `start(relayUrl)`、`stop()`、世代番号、セッションJobを導入する。
3. `StatusScreen`から`LifecycleStartEffect`で可視期間を接続する。
4. リレー切り替え、タブ切り替え、アカウント切り替えの購読数をログで確認する。

### Phase 4: 投稿処理統合

1. `StatusPublisher`を追加する。
2. ステータスタブを送信成功後のローカル反映へ変更する。
3. 投稿状態を状態機械へ変更し、完了カウンタを削除する。
4. `MyProfileViewModel`を共通Publisherへ移行する。
5. プロフィール画面で失敗時にComposerが閉じないことを確認する。

### Phase 5: Composer整理

1. `StatusDraft`と純粋バリデーションを抽出する。
2. 日付・時刻変換をテスト可能な関数へ移す。
3. Composeには表示、フォーカス、IME、Picker開閉だけを残す。
4. 不要なimport、定数、重複関数を削除する。

## 14. 検証手順

各Phaseで次を実施する。

1. 追加したstatus関連common testを実行する。
2. common test全体を実行する。
3. Android mainをコンパイルする。
4. iOS Simulator向けにコンパイルする。
5. iOS Simulatorで対象操作を確認する。

手動確認項目は次のとおり。

- ステータスタブ初回表示でローディング後に一覧が表示される。
- generalとmusicの選択切り替えが一覧へ一致して反映される。
- 全カテゴリを解除すると専用の空表示になる。
- カスタムカテゴリを選ぶと対象ステータスだけが追加表示される。
- リレーをA→B→Aと切り替えても、重複行や旧リレーのイベントが混ざらない。
- ステータス→チャンネル→ステータスと移動しても、購読数が増加しない。
- 投稿成功時にComposerが閉じ、一覧へ反映される。
- 投稿失敗時にComposerが開いたままで、一覧へ失敗イベントが残らない。
- プロフィール画面からの追加、編集、削除も同じ成功・失敗規則になる。
- 期限到達後、最大60秒以内に一覧から消える。
- アカウント切り替え後に旧アカウントの状態が表示されない。

デバッグログでは、購読IDごとに`REQ`と`CLOSE`の対応を確認する。選択リレー1件につき、通常購読と
自分のgeneral購読の最大2本だけがアクティブであることを受け入れ条件とする。

## 15. リスクと対策

### 15.1 購読停止と再開の競合

旧購読のFlowにキュー済みイベントが残っている可能性がある。Jobキャンセルだけに依存せず、全状態適用時に
世代番号を照合する。

### 15.2 画面復帰時の空表示

`stop()`で表示スナップショットまで破棄すると、タブ往復のたびに空表示へ戻る。表示データは保持し、
復帰時だけ`Loading`表示へ全面置換しない。リレーが変わる場合は旧リレー混入を防ぐためスナップショットを初期化する。

### 15.3 プロフィール画面への影響

共通Publisher導入はプロフィールの編集・削除にも影響する。ステータスタブだけを先に移行し、テストが
安定してからプロフィール画面を移行する。旧実装と新実装を同時に呼ばない。

### 15.4 投稿成功の意味

現行APIは必ずしもリレーの`OK`受理まで待たない。本リファクタで成功判定を過度に拡張せず、まずは
「送信処理が例外なく完了した」を統一する。受理確認は別設計とする。

## 16. 完了条件

次のすべてを満たした時点でリファクタ完了とする。

- ステータスタブ非表示中にstatus購読と期限更新Jobが残らない。
- リレー切り替え後に旧リレーの購読が閉じられる。
- 同じリレーへ戻ってもアクティブな購読が重複しない。
- 同時刻の置換可能イベントを受信順に依存せず選択できる。
- 空本文の最新イベント後に古いステータスが復活しない。
- カテゴリ未選択時の表示とチップ状態が一致する。
- 投稿失敗時に一覧を変更せず、Composer内にエラーを表示する。
- ステータスタブとプロフィール画面が共通CodecとPublisherを使用する。
- `publishCompletedCount`系の完了カウンタがステータス機能からなくなる。
- status関連の新規単体テスト、common test全体、Android/iOSコンパイルが成功する。
- iOS Simulatorでリレー切り替え、タブ往復、投稿成功・失敗、プロフィール編集を確認する。

## 17. 導入状況

**状態: Phase 1〜5実装済み・自動テスト完了・ステータスタブ固有のSimulator手動操作は未確認。**

### 17.1 実装結果（2026年9月25日）

| Phase | 状態 | 実装内容 |
| --- | --- | --- |
| Phase 1 | 完了 | `status`パッケージへイベント型、Codec、比較規則を追加。カスタム絵文字タグ処理を`emoji`パッケージへ移し、プロフィールとステータスで共有 |
| Phase 2 | 完了 | `StatusSnapshot`と純粋Reducerを導入。同時刻ID比較、空本文tombstone、期限・ミュート・カテゴリ導出、空カテゴリ0件表示を実装 |
| Phase 3 | 完了 | `StatusViewModel`を固定キーの単一インスタンスへ変更。`start(relayUrl)`／`stop()`、世代照合、`LifecycleStartEffect`を導入 |
| Phase 4 | 完了 | `StatusPublisher`と投稿状態機械を導入。ステータスタブと自分のプロフィールを送信成功後のローカル反映へ統一し、完了カウンタを削除 |
| Phase 5 | 完了 | `StatusDraft`へカテゴリ、期限、送信値生成、バリデーション、日時変換を抽出 |

実装レビューでは、設計時の指摘に加えて次を修正した。

- 最新の空本文イベントをMapから消さず、古い非空イベントの再到着による復活を防止した。
- 投稿中にタブ離脱またはリレー変更が起きた場合、投稿Jobをキャンセルし、旧世代の結果を新しい画面状態へ適用しないようにした。
- プロフィール画面からmusicまたはカスタムカテゴリを投稿した場合、既存のgeneral表示を誤って消さないようにした。
- general識別子をparameterized replaceable eventのアドレスとして大文字小文字を区別し、組み込みカテゴリは小文字の正規形だけに限定した。

### 17.2 自動検証

次のコマンドが成功した。

```text
./gradlew :composeApp:allTests \
  :composeApp:compileAndroidMain \
  :composeApp:compileKotlinIosSimulatorArm64
```

追加したテストは次のとおり。

- `StatusEventCodecTest`
- `StatusEventReducerTest`
- `StatusPublisherTest`
- `StatusComposerDraftTest`
- `StatusViewModelTest`
- `ProfileGeneralStatusTest`のカテゴリ大小文字・期限境界ケース

iPhone 17 Simulator向けのXcode Debug build、インストール、起動にも成功し、フィード画面の描画を確認した。
画面操作用の自動化セッションがタイムアウトし、ステータスタブへの遷移、リレー切り替え、投稿成功・失敗の
Simulator上の手動確認は未実施である。このため16章のうち、最後のSimulator操作条件だけを未完了として残す。

完了後、一時的な移行手順を整理し、購読所有権など恒久的に守る規則を
`subscription-architecture-design.md`へ反映する。

## 18. 実装レビュー指摘の修正設計

**状態: 実装・自動テスト完了。Simulator手動確認は未実施。**

2026年9月25日の実装レビューで、プロフィール画面の置換順序、購読イベントの検証、
カスタムカテゴリの選択状態に3件の不整合が見つかった。本章では、その修正を既存の
Codec／Reducer設計を崩さずに行う。

### 18.1 修正の受け入れ条件

1. プロフィール画面の購読受信と投稿成功が、同じイベント検証・置換順序を使用する。
2. kind、pubkey、identifierのいずれかが対象と異なるイベントは、表示状態と最新版記録を変更しない。
3. 同一`created_at`では、購読受信・投稿成功の経路にかかわらず、IDが辞書順で小さいイベントを採用する。
4. 選択中のカスタムカテゴリは、アクティブなステータスが0件になってもチップから消えない。
5. 選択中でないカスタムカテゴリは、アクティブなステータスがなくなった時点でチップから除去できる。

### 18.2 プロフィール用イベント適用経路の統一

`MyProfileViewModel`と`UserProfileViewModel`が保持する`latestGeneralStatusEvent`を廃止し、
それぞれが`StatusSnapshot`を1つ保持する。購読受信と投稿成功の両方を、次の共通手順へ通す。

```text
NostrEvent
  -> StatusEventCodec.parse
  -> 期待するStatusAddressとの完全一致を確認
  -> StatusEventReducer.reduce
  -> generalの表示可能エントリを導出
  -> ProfileGeneralStatusへ変換してUiStateを更新
```

期待するアドレスは次の値で固定する。

```kotlin
StatusAddress(
    pubkey = profilePubkey,
    identifier = GENERAL_STATUS_IDENTIFIER,
)
```

検証規則は次のとおり。

- `StatusEventCodec.parse()`が`null`を返した場合は無視する。
- `candidate.address != expectedAddress`の場合は無視する。
- アドレス不一致イベントは`StatusSnapshot`へ保存しない。
- 空本文と期限切れは有効な最新バージョンとしてReducerへ保存し、表示導出時だけ非表示にする。
- 同一イベントまたは置換順序で古いイベントでは、UiStateを更新しない。

プロフィール用に別の新旧比較を再実装しない。単一アドレスの表示導出を明確にするため、
`StatusEventReducer`へ次の純粋関数を追加する。

```kotlin
fun activeStatus(
    snapshot: StatusSnapshot,
    address: StatusAddress,
    nowEpochSeconds: Long,
): StatusEntry?
```

この関数は指定アドレスの最新版だけを参照し、本文が空白、または
`expiration <= nowEpochSeconds`なら`null`を返す。プロフィール画面ではミュートフィルターを
適用しない。プロフィールを明示的に開いた場合の表示可否と、タイムラインのミュート規則を分離するためである。

### 18.3 投稿成功時の置換順序

`MyProfileViewModel.publishStatusCommand()`は、`StatusPublishResult.Published`を受け取っても
`latestGeneralStatusEvent`や`generalStatus`を直接上書きしない。generalの投稿である場合だけ、
18.2の共通適用処理へ`result.event`を渡す。

```text
Published(event)
  +-- general以外 -> generalStatusは変更しない
  `-- general      -> 共通適用処理 -> Reducerが採否を決定
```

Reducerで不採用になっても、ネットワーク送信自体は完了しているため投稿状態は
`Succeeded(event.id)`へ遷移する。これによりComposerの完了処理とNostrの正規イベント選択を混同しない。
同一秒の連続投稿で新しい投稿がID比較に負けた場合、プロフィール表示は既存の正規イベントを維持する。

### 18.4 選択中カスタムカテゴリの可視性

`selectedCategories`は利用者の選択なので、ステータスの期限切れ、削除、ミュートによって自動変更しない。
代わりに`availableCategories`を次の集合から構築する。

```kotlin
val activeCustomCategories = activeStatuses
    .map(StatusEntry::identifier)
    .filter { it !in DEFAULT_CATEGORIES }

val selectedCustomCategories = selectedCategories
    .filter { it !in DEFAULT_CATEGORIES }

val availableCategories = DEFAULT_CATEGORIES +
    (activeCustomCategories + selectedCustomCategories)
        .distinct()
        .sorted()
```

この規則により、選択中カテゴリの最後のイベントが期限切れになってもチップから解除できる。
利用者がそのチップを解除すると、アクティブなイベントがないカテゴリは一覧から消える。

空表示の意味は次のまま維持する。

- 選択集合が空: 「カテゴリが選択されていません」
- 選択集合は空でないが表示対象がない: 「ステータスがありません」

### 18.5 実装手順

#### Phase R1: Reducer APIの追加

1. `StatusEventReducer.activeStatus()`を追加する。
2. 空本文、期限境界、通常表示の単一アドレステストを追加する。
3. 既存の`activeStatuses()`と表示可否の規則が一致することを確認する。

#### Phase R2: プロフィール受信処理の統一

1. 両プロフィールViewModelの`latestGeneralStatusEvent`を`StatusSnapshot`へ置き換える。
2. Codec解析、期待アドレス検証、Reducer適用、UiState更新を行う小さな内部関数を設ける。
3. 購読collectorから内部関数を呼ぶ。
4. `MyProfileViewModel`の投稿成功からも同じ内部関数を呼ぶ。
5. 既存の`isNewerStatusEvent()`直接呼び出しと無条件代入を削除する。

内部関数は、イベントが不正・対象外・古い場合に状態を変更しないことが分かる戻り値または構造にする。
ただし、UIイベントやエラーメッセージを新しく増やさない。

#### Phase R3: カテゴリ導出の修正

1. `rebuildStatuses()`でアクティブなカスタムカテゴリと選択中のカスタムカテゴリをマージする。
2. 組み込みカテゴリを先頭、カスタムカテゴリを辞書順とする既存順序を維持する。
3. カテゴリを解除した直後に、非アクティブなカスタムカテゴリのチップが消えることを確認する。

#### Phase R4: 回帰確認

1. status関連テストを実行する。
2. common test全体を実行する。
3. Android mainとiOS Simulator向けコンパイルを実行する。
4. iOS Simulatorでプロフィール投稿とカスタムカテゴリ期限切れを確認する。

### 18.6 追加テスト

#### Reducer

- `activeStatus()`が対象アドレスの非空・期限内イベントを返す。
- 空本文の最新版では`null`を返すが、古い非空イベントを復活させない。
- `expiration == nowEpochSeconds`で`null`を返す。
- 別アドレスのイベントに影響されない。

#### プロフィールイベント適用

- kindが`30315`以外のイベントを無視する。
- pubkeyが表示対象と異なるイベントを無視する。
- identifierが`general`以外のイベントを無視する。
- 同一時刻でIDが小さいイベントを採用する。
- 同一時刻でIDが大きい投稿成功イベントを送信成功として扱いつつ、表示には採用しない。
- 空本文の最新イベントで表示を消し、その後届く古い非空イベントを無視する。
- general以外の投稿成功でgeneral表示を変更しない。

ViewModelをグローバルRepositoryから分離する変更が大きくなる場合は、イベント適用部分を純粋な内部クラスへ
抽出して単体テストする。ただし、最終的に購読受信と投稿成功が同じ関数を通ることはコード上で保証する。

#### カテゴリ

- 選択中カスタムカテゴリの最後のイベントが期限切れになってもチップが残る。
- 選択中カスタムカテゴリが空本文で削除されてもチップが残る。
- 非アクティブなカスタムカテゴリを解除するとチップが消える。
- アクティブな未選択カスタムカテゴリはチップに残る。
- カスタムカテゴリの並び順が選択状態に依存せず安定する。

### 18.7 修正完了条件

- 両プロフィールViewModelに独自の最新イベント比較が残っていない。
- 購読受信と投稿成功が同じCodec、アドレス検証、Reducerを通る。
- 対象外イベントでプロフィールのgeneral表示と最新版記録が変化しない。
- 同一秒の連続投稿後もNIP-01の置換順序と画面表示が一致する。
- 選択中のカスタムカテゴリが操作不能にならない。
- 18.6の追加テストと既存テストが成功する。
- Android/iOSのコンパイルが成功する。

### 18.8 実装・検証結果（2026年9月25日）

- `StatusEventReducer.activeStatus()`を追加し、一覧と単一アドレスで有効判定を共有した。
- `reduceProfileGeneralStatus()`を追加し、両プロフィールViewModelの受信処理を共通化した。
- 自分のプロフィールの投稿成功も同じ共通処理へ通し、同一時刻のID順序を維持した。
- 対象外のkind、pubkey、identifierをスナップショット更新前に拒否した。
- 選択中のカスタムカテゴリを`availableCategories`へ残し、解除後に非アクティブなら除去するようにした。
- Reducer、プロフィールイベント適用、カテゴリ残存の回帰テストを追加した。

次のタスクをキャッシュなしで実行し、すべて成功した。

```text
./gradlew :composeApp:allTests \
  :composeApp:compileAndroidMain \
  :composeApp:compileKotlinIosSimulatorArm64 \
  --rerun-tasks
```
