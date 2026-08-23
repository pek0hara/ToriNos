# ノートエンゲージメント共通化仕様

## 1. 文書の目的

本仕様は、ノートに対するリアクションとリポストの処理を、複数の ViewModel に重複した実装から共通基盤へ移行するための実装仕様を定める。

共通基盤は、通信を担当する `NoteEngagementService` と、状態遷移を担当する純粋な `EngagementReducer` に分ける。各 ViewModel は画面固有の状態との接続、ジョブの起動、エラー表示だけを担当する。

## 2. 解決する問題

現在、次の ViewModel にほぼ同じ処理が存在する。

- `FeedViewModel`
- `ThreadViewModel`
- `ChannelViewModel`
- `JournalViewModel`

重複している処理は次のとおりである。

- 通常リアクションの追加と解除
- Unicode 絵文字リアクションの追加と解除
- カスタム絵文字リアクションの追加と解除
- リポストの追加と解除
- 上記操作の件数、自分のイベント ID、絵文字別件数に対する楽観的更新
- kind 7、kind 6、kind 5 イベントの生成、署名、送信

現状では通常・絵文字リアクションの追加失敗時にはロールバックする一方、リアクション解除、リポスト、リポスト解除では署名者不在、署名失敗、全リレー送信失敗を握りつぶす箇所がある。その結果、リレー上の状態と画面表示が一致しないことがある。

また、署名済みイベント ID が確定する前は空文字列を仮 ID として保存している。この状態で解除すると削除対象を特定できず、追加イベントだけが後から送信される競合が起こり得る。

## 3. 完了条件

本リファクタリングは、次の状態になった時点で完了とする。

1. 対象 ViewModel から kind 7、kind 6、kind 5 の生成、署名、送信処理がなくなる。
2. 件数と自分のイベント ID の楽観的更新、確定、ロールバックが `EngagementReducer` に集約される。
3. 追加、解除、リポスト、リポスト解除の全操作が同じ成功・失敗規則に従う。
4. 全リレーへの送信失敗または署名失敗時に、すべての操作が楽観的更新前と整合する状態へ戻る。
5. 同一ノートに対する処理中の競合で、イベント ID 不明のまま解除イベントを省略しない。
6. Reducer の全状態遷移を `commonTest` の単体テストで検証できる。
7. 各 ViewModel に残る処理が、画面状態の読み出し、Reducer の適用、Service の呼び出し、結果の反映に限定される。

## 4. 対象範囲

### 4.1 対象

- 通常リアクション `+` の追加と解除
- `ReactionOption.Unicode` の追加と解除
- `ReactionOption.Custom` の追加と解除
- kind 6 リポストの追加と解除
- kind 5 削除イベントの生成と送信
- 件数、絵文字別件数、自分のリアクションイベント ID、自分のリポストイベント ID の楽観的更新
- 処理中状態、成功時のイベント ID 確定、失敗時のロールバック
- `FeedViewModel`、`ThreadViewModel`、`ChannelViewModel`、`JournalViewModel` への導入
- 操作結果の統一的なエラー通知

`JournalViewModel` は現時点でリポスト件数を読み込むが、自分がリポストする公開操作を持たない。共通状態への接続は行い、公開操作は画面要件が追加された時点で同じ API を使用する。

### 4.2 対象外

- リアクション、リポスト、引用リポストの購読方式の再設計
- リレーから受信した他ユーザーのイベントの重複排除
- 引用リポストの作成 UI と投稿処理
- 返信、投稿、削除、通報など他の書き込み操作
- Nostr イベント仕様および `NostrRepository.publish()` の成功条件の変更
- 既存 UI デザインの変更

受信イベントによる集計は当面各 ViewModel に残してよい。ただし、ローカル送信イベントのエコーで二重加算しないよう、既存の `seenReactionIds`、`seenRepostIds` への登録は画面接続層で維持する。

## 5. 設計原則

### 5.1 通信と状態遷移を分離する

`NoteEngagementService` はイベントの生成、署名、送信だけを担当し、UI state を参照または変更しない。

`EngagementReducer` は状態と Action を受け取り、新しい状態を返す純粋関数とする。Coroutine、時刻取得、乱数生成、Repository、Signer、ログ出力には依存しない。

### 5.2 空文字列を処理中 ID として使用しない

処理中であることと、リレーへ送信済みのイベント ID を別の値として管理する。自分のイベント ID は実在する署名済みイベントの ID だけを保持する。

### 5.3 楽観的更新は必ず確定またはロールバックする

すべての操作は次の3段階を持つ。

1. `Begin`: UI を楽観的に更新し、操作を処理中として記録する。
2. `Commit`: 署名・送信成功後に署名済みイベント ID を確定する。
3. `Rollback`: 署名者不在、署名失敗、送信失敗、キャンセル時に対象操作の差分だけを戻す。

送信の成功条件は現行の `NostrRepository.publish()` と同じく、少なくとも1つの有効なリレーがイベントを受理したこととする。一部のリレーだけが失敗した場合は成功として確定する。

### 5.4 同一操作スロットを直列化する

同一ノートの「自分のリアクション」と「自分のリポスト」を、それぞれ操作スロットとして扱う。スロットが処理中の間は同じスロットへの新しい操作を受け付けない。

これにより、追加イベントの ID が未確定のまま解除が走ること、追加と解除の失敗結果が逆順に反映されることを防ぐ。リアクションとリポストは別スロットなので、互いに並行実行してよい。

## 6. 共通データモデル

画面ごとの Map 構造から1ノート分を取り出し、次の共通状態へ変換する。

```kotlin
data class NoteEngagementState(
    val reactionCount: Int = 0,
    val likeReactionCount: Int = 0,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
    val ownLikeEventId: String? = null,
    val ownEmojiReactionEventIds: Map<String, String> = emptyMap(),
    val repostCount: Int = 0,
    val ownRepostEventId: String? = null,
    val pendingOperations: Map<EngagementSlot, PendingEngagementOperation> = emptyMap(),
)

enum class EngagementSlot {
    Reaction,
    Repost,
}

data class EngagementOperationId(val value: String)
```

`EngagementOperationId` は ViewModel または注入された ID generator が操作開始時に生成する。Reducer 自身は ID を生成しない。

ViewModel から Reducer へ渡す要求と、Reducer が状態内へ保存する処理中情報を分ける。

```kotlin
sealed interface EngagementRequest {
    data object AddLike : EngagementRequest
    data class AddEmoji(val option: ReactionOption) : EngagementRequest
    data object RemoveLike : EngagementRequest
    data class RemoveEmoji(val option: ReactionOption) : EngagementRequest
    data object AddRepost : EngagementRequest
    data object RemoveRepost : EngagementRequest
}

data class EngagementDelta(
    val reactionCount: Int = 0,
    val likeReactionCount: Int = 0,
    val repostCount: Int = 0,
    val emojiOption: ReactionOption? = null,
    val emojiCount: Int = 0,
)

data class PendingEngagementOperation(
    val id: EngagementOperationId,
    val request: EngagementRequest,
    val removedEventId: String? = null,
    val appliedDelta: EngagementDelta,
)
```

`PendingEngagementOperation` は Reducer が `Begin` 時に生成する。`removedEventId` には解除前の ID、`appliedDelta` には件数へ実際に適用した差分を保存する。これにより、受信途中などで件数がすでに0だった場合も Rollback で余分な件数を加算しない。

件数は常に0以上とする。`ownEmojiReactionEventIds` のキーには既存の `ReactionOption.key` を使用する。

通常リアクションと絵文字リアクションは現行 UI と同じく排他的とする。`ownLikeEventId != null` または `ownEmojiReactionEventIds` が空でない場合、新しいリアクション追加は拒否する。

## 7. EngagementReducer

### 7.1 公開 API

```kotlin
object EngagementReducer {
    fun reduce(
        state: NoteEngagementState,
        action: EngagementAction,
    ): NoteEngagementState
}

sealed interface EngagementAction {
    data class Begin(
        val operationId: EngagementOperationId,
        val request: EngagementRequest,
    ) : EngagementAction

    data class Commit(
        val operationId: EngagementOperationId,
        val publishedEventId: String,
    ) : EngagementAction

    data class Rollback(
        val operationId: EngagementOperationId,
    ) : EngagementAction
}
```

不正または古い Action は例外にせず no-op とする。具体的には、対象スロットがすでに処理中の `Begin`、存在しない操作 ID の `Commit` と `Rollback`、現在状態と矛盾する追加・解除を no-op にする。ViewModel は `Begin` 前後の状態を比較し、操作が受理された場合だけ Service を呼ぶ。

### 7.2 Begin の状態遷移

| 操作 | 楽観的更新 |
| --- | --- |
| 通常リアクション追加 | `reactionCount` と `likeReactionCount` を1増やす |
| 絵文字リアクション追加 | `reactionCount` を1増やし、対象の絵文字別件数を1増やす |
| 通常リアクション解除 | `reactionCount` と `likeReactionCount` を1減らし、`ownLikeEventId` を外す |
| 絵文字リアクション解除 | `reactionCount` と対象の絵文字別件数を1減らし、対象キーを自分の ID Map から外す |
| リポスト追加 | `repostCount` を1増やす |
| リポスト解除 | `repostCount` を1減らし、`ownRepostEventId` を外す |

すべての Begin は、要求、解除前のイベント ID、実際に適用した差分を持つ `PendingEngagementOperation` を対応スロットへ保存する。追加 Begin の時点では自分のイベント ID を追加しない。UI 上の選択状態は、確定済み ID と pending operation の両方から selector で導出する。

```kotlin
val NoteEngagementState.hasOwnReaction: Boolean
val NoteEngagementState.isRepostedByMe: Boolean
val NoteEngagementState.isReactionPending: Boolean
val NoteEngagementState.isRepostPending: Boolean
```

### 7.3 Commit の状態遷移

- 追加操作では `publishedEventId` を自分のリアクションまたはリポスト ID として保存する。
- 解除操作で `publishedEventId` は kind 5 削除イベントの ID である。元イベント ID は復元せず、pending だけを削除する。
- 対象の pending operation を削除する。
- 件数は Begin ですでに変更済みなので Commit では変更しない。

### 7.4 Rollback の状態遷移

- 追加操作は Begin で実際に加算した `appliedDelta` を逆適用し、自分の ID を追加しない。
- 解除操作は Begin で実際に減算した `appliedDelta` を逆適用し、`removedEventId` を元の場所へ戻す。
- 絵文字別件数も対象の `ReactionOption` だけを逆方向へ更新する。
- 対象の pending operation を削除する。

Rollback は状態全体のスナップショットを上書きしてはならない。操作開始後に購読から届いた他ユーザーの加算を失わないよう、当該操作の差分だけを逆適用する。

## 8. NoteEngagementService

### 8.1 責務

- `AccountSigner` によるイベント生成と署名
- `NostrRepository` への送信
- 署名済みイベントを呼び出し元へ返す
- 署名者不在、無効な入力、署名失敗、送信失敗を `Result.failure` として返す

Service は件数、選択状態、処理中状態、ロールバックを管理しない。

### 8.2 依存関係

テスト可能にするため、具体的な singleton を直接固定せず、送信関数を注入できる構造とする。

```kotlin
class NoteEngagementService(
    private val signer: AccountSigner?,
    private val publisher: suspend (NostrEvent) -> RelayPublishResult,
) {
    suspend fun execute(
        command: NoteEngagementCommand,
        onSigned: (NostrEvent) -> Unit = {},
    ): Result<NostrEvent>
}
```

`AccountSession` ごとに `signer` を固定して生成し、ViewModel の操作中にグローバルな現在アカウントを再取得してはならない。匿名状態などで `signer` がない場合、`execute` は送信せず失敗を返す。

Service は署名直後、送信開始前に `onSigned` を同期的に呼ぶ。ViewModel はここで追加リアクションを `seenReactionIds`、追加リポストを `seenRepostIds` へ登録する。これにより、`publish()` の完了より先に購読エコーが届いても二重加算しない。削除イベントはこれらの seen set へ登録しない。

`execute` は `CancellationException` を `Result.failure` に変換せず再送出する。また、`publisher` が成功した後に別の suspend point を挟まず署名済みイベントを返す。

### 8.3 Command

```kotlin
sealed interface NoteEngagementCommand {
    data class AddLike(val target: NoteTarget) : NoteEngagementCommand
    data class AddEmoji(
        val target: NoteTarget,
        val option: ReactionOption,
    ) : NoteEngagementCommand
    data class RemoveReaction(val reactionEventId: String) : NoteEngagementCommand
    data class AddRepost(val event: NostrEvent) : NoteEngagementCommand
    data class RemoveRepost(val repostEventId: String) : NoteEngagementCommand
}

data class NoteTarget(
    val eventId: String,
    val eventPubkey: String,
)
```

イベント ID、公開鍵、削除対象 ID は空文字列を禁止する。空文字列の場合は署名・送信を行わず失敗を返す。

### 8.4 イベント生成規則

| Command | kind | content | tags |
| --- | ---: | --- | --- |
| `AddLike` | 7 | `+` | `[e, targetId]`, `[p, targetPubkey]` |
| `AddEmoji` | 7 | `option.eventContent` | `option.eventTags(targetId, targetPubkey)` |
| `RemoveReaction` | 5 | 空文字列 | `[e, reactionEventId]` |
| `AddRepost` | 6 | 対象イベントの JSON | `[e, targetId]`, `[p, targetPubkey]` |
| `RemoveRepost` | 5 | 空文字列 | `[e, repostEventId]` |

リポスト content の JSON 化には、既存と同じ `NostrEvent.serializer()` を使用する。Feed ではラッパーではなく canonical event を Service に渡す。

## 9. ViewModel からの利用手順

各 ViewModel は1操作について次の順序だけを実装する。

1. 画面固有 state から対象ノートの `NoteEngagementState` を読み出す。
2. operation ID と `EngagementRequest` を作る。
3. `EngagementReducer.reduce(state, Begin(operationId, request))` を適用する。
4. Begin が no-op なら終了する。
5. 新しい共通状態を画面固有 state へ即時反映する。
6. ViewModel の Scope で対応する Service command を実行する。
7. 署名直後に追加イベントの ID を seen set へ登録し、送信成功時に `Commit` を適用する。
8. 失敗またはキャンセル時は `Rollback` を適用し、共通のエラーを画面へ通知する。

概念コードは次のとおりである。

```kotlin
private fun runEngagementOperation(
    eventId: String,
    operationId: EngagementOperationId,
    request: EngagementRequest,
    command: NoteEngagementCommand,
) {
    val before = readEngagement(eventId)
    val optimistic = EngagementReducer.reduce(
        before,
        EngagementAction.Begin(operationId, request),
    )
    if (optimistic == before) return
    writeEngagement(eventId, optimistic)

    launch {
        var committed = false
        var failure: Throwable? = null
        try {
            val published = noteEngagementService.execute(
                command = command,
                onSigned = { rememberPublishedEngagement(command, it.id) },
            ).getOrThrow()
            updateEngagement(eventId) {
                EngagementReducer.reduce(
                    it,
                    EngagementAction.Commit(operationId, published.id),
                )
            }
            committed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (!committed) {
                updateEngagement(eventId) {
                    EngagementReducer.reduce(it, EngagementAction.Rollback(operationId))
                }
            }
        }
        failure?.let(::notifyEngagementFailure)
    }
}
```

このオーケストレーション自体が重複する場合は、UI 型に依存しない小さな `EngagementOperationRunner` として共通化してよい。ただし、Reducer に Coroutine や Service 呼び出しを持ち込んではならない。

## 10. 画面固有状態への接続

### 10.1 FeedViewModel

イベント ID をキーとする既存 Map 群と `NoteEngagementState` を相互変換する adapter を持つ。`canonicalEvents[event.id] ?: event` の選択は ViewModel に残し、選ばれたイベントを `AddRepost` へ渡す。

### 10.2 ThreadViewModel

ルートと返信を同じ adapter で扱う。`reactionPubkeys`、`rootReactionsByPubkey`、`repostPubkeys` はスレッド画面固有の表示情報なので、共通状態に含めない。

自分の操作に伴うこれらの楽観的変更は、共通状態の selector を基準に Thread adapter が反映し、Rollback 時にも同じ adapter から復元する。

### 10.3 ChannelViewModel

`currentReactionCounts` などの内部 Map を adapter で読み書きし、1回の `writeEngagement` ごとに `syncReadyState()` を1回だけ呼ぶ。既存の `addOptimisticEmojiReaction`、`removeOptimisticLike`、`removeOptimisticEmojiReaction` は削除する。

### 10.4 JournalViewModel

`JournalState` の Map 群を adapter で読み書きする。既存のファイル先頭にある楽観的更新 extension は削除する。

現行画面には `repost()`、`unrepost()` がないため、リポスト件数の受信値だけを adapter に渡す。将来操作を公開するときは ViewModel 内に独自の署名・送信処理を追加せず、Service と Reducer を利用する。

## 11. エラーとキャンセルの規則

### 11.1 ロールバック対象

次のすべてを失敗として Rollback する。

- アクティブな `AccountSigner` を取得できない
- 入力 ID または公開鍵が不正
- JSON 化または署名に失敗
- 有効なリレーがない
- すべての対象リレーへの送信に失敗
- ViewModel のジョブが送信成功の確定前にキャンセルされた

### 11.2 キャンセル処理

`runCatching` で `CancellationException` を通常エラーとして握りつぶしてはならない。`try/finally` で未確定操作を Rollback した後、`CancellationException` を再送出する。

送信 API が成功を返した後は Commit を先に適用する。成功後の画面破棄ではロールバックしない。

### 11.3 利用者への通知

失敗時は少なくとも画面内の Snackbar または既存のエラー state に、次の共通文言を通知する。

- 追加失敗: `リアクションの送信に失敗しました` または `リポストの送信に失敗しました`
- 解除失敗: `リアクションの解除に失敗しました` または `リポストの解除に失敗しました`

技術的な例外メッセージはログへ残してよいが、そのまま利用者へ表示することを必須にしない。

## 12. 配置案

```text
composeApp/src/commonMain/kotlin/com/nostr/torinos/
├── engagement/
│   ├── NoteEngagementService.kt
│   ├── EngagementReducer.kt
│   └── NoteEngagementModels.kt
└── ui/
    ├── feed/FeedViewModel.kt
    ├── thread/ThreadViewModel.kt
    ├── channel/ChannelViewModel.kt
    └── post/JournalViewModel.kt

composeApp/src/commonTest/kotlin/com/nostr/torinos/engagement/
├── EngagementReducerTest.kt
└── NoteEngagementServiceTest.kt
```

`engagement` パッケージは Compose、画面の `UiState`、具体的な ViewModel に依存しない。

## 13. テスト仕様

### 13.1 Reducer 単体テスト

各操作について次を検証する。

- Begin で正しい件数と pending が反映される
- Commit で正しい自分のイベント ID が保存され、件数が二重更新されない
- Rollback で差分だけが戻り、pending が消える
- 件数が0未満にならない
- 通常リアクションと絵文字リアクションの排他制御
- 同じスロットで処理中の Begin が no-op になる
- 異なるスロットは同時に処理できる
- 古い operation ID の Commit と Rollback が no-op になる
- 解除失敗時に元のイベント ID が復元される
- 操作中に他ユーザー分の件数が増えた後でも Rollback がその増分を保持する
- カスタム絵文字と Unicode 絵文字の0件要素が List から削除される

### 13.2 Service 単体テスト

- 各 Command が正しい kind、content、tags で署名される
- カスタム絵文字に `emoji` tag が付く
- リポスト content が対象イベントの JSON になる
- 解除が元イベント ID を指す kind 5 を生成する
- publisher 成功時に署名済みイベントを返す
- signer または publisher の例外を失敗として返す
- 空 ID を署名・送信しない

### 13.3 ViewModel 結合テスト

4画面について、少なくとも次を検証する。

- 操作直後に楽観的表示へ変わる
- 成功時にイベント ID が確定する
- 署名失敗と全リレー送信失敗で表示が戻る
- 追加処理中の即時解除が二重実行されない
- 解除失敗で選択状態と件数が戻る
- リポストとリポスト解除の失敗でも同様に戻る
- 受信エコーで件数が二重加算されない
- Thread 固有の pubkey 一覧も Rollback 後に整合する

## 14. 移行手順

1. 共通モデルと `EngagementReducer` を追加し、Reducer の単体テストを完成させる。
2. `NoteEngagementService` と fake signer／publisher を使う単体テストを追加する。
3. `FeedViewModel` に adapter と共通処理を導入し、既存の楽観的更新 extension を削除する。
4. `ThreadViewModel` に導入し、ルート固有の pubkey 状態との接続を確認する。
5. `ChannelViewModel` に導入し、内部 Map と `syncReadyState()` の更新単位を確認する。
6. `JournalViewModel` に導入し、既存 extension を削除する。
7. 対象4ファイルから kind 5、6、7 の直接生成と engagement 用の `NostrRepository.publish()` 呼び出しがなくなったことを検索で確認する。
8. 共通テストと対象 ViewModel のテストを実行する。

移行は画面単位で行えるが、挙動差が残る期間を短くするため、同一変更セット内で4画面を移行する。

## 15. 受け入れ条件

- 通常リアクション、絵文字リアクション、解除、リポスト、リポスト解除が対象画面で従来どおり操作できる。
- 各操作は即座に UI へ反映される。
- 署名または送信に失敗すると、操作種別にかかわらず UI が一貫してロールバックされる。
- 解除失敗時に元の自分のイベント ID が復元され、再度解除できる。
- 処理中の連打で件数が重複加算・減算されない。
- 自分のイベント ID に空文字列を保存しない。
- 少なくとも1リレーへの送信成功時は Commit される。
- Reducer がプラットフォーム API や副作用へ依存せず、決定的な単体テストを持つ。
- 対象 ViewModel にイベント生成、署名、送信、個別のロールバック計算が残っていない。
