# 巨大ViewModel責務分割仕様

## 1. 文書の目的

`FeedViewModel`、`ThreadViewModel`、`ChannelViewModel`、`JournalViewModel` に集中している通信、購読、集約、プロフィール取得、署名操作、画面状態更新を、ライフサイクルと状態所有権が明確なコンポーネントへ分割する。

分割後も画面から見えるViewModelは1画面につき1つとする。子ViewModelを増やすのではなく、ViewModelをUI向けFacadeとして残し、その配下を通常のKotlinクラス、純粋Reducer、UseCaseへ分離する。

本書は次の既存仕様を前提とする。

- [`account-session-viewmodel-architecture.md`](./account-session-viewmodel-architecture.md)
- [`subscription-architecture-design.md`](./subscription-architecture-design.md)

## 1.1 導入状況

2026年8月24日時点で次を導入済みである。

- 4画面のViewModelを公開APIとStateFlowの公開に限定したFacadeへ縮小
- 画面ごとのControllerへ購読、ページング、プロフィール取得、署名操作を移動
- `StateStore`、`ProfileHydrator`、`QuoteResolver`、`NoteEngagementCoordinator`、`SignedEventPublisher`を追加
- Threadツリー、Feedイベント、Channelメッセージ、Journalカレンダーの純粋Reducerを追加
- ControllerがViewModelの`viewModelScope`を共有し、独立Scopeを生成しない構成へ統一
- 互換用`ThreadStateAccessor`を撤去

## 2. 対象と現状

対象は次の4ファイルとする。

| ViewModel | 現在の規模 | 主な集中責務 |
| --- | ---: | --- |
| `FeedViewModel` | 約1,200行 | 履歴・ライブ購読、ページング、イベント正規化、フィルタ、プロフィール、引用、エンゲージメント、削除・通報 |
| `ThreadViewModel` | 約736行 | ルート・返信・集計購読、ツリー構築、プロフィール、引用、返信投稿、エンゲージメント |
| `ChannelViewModel` | 約941行 | チャンネル情報、メッセージ購読・ページング、フィルタ、プロフィール、投稿、メタデータ編集、既読、エンゲージメント |
| `JournalViewModel` | 約1,329行 | 日付・月移動、種別別取得、月バックフィル、メモ復号、ノート統合、プロフィール、参照先取得、削除、エンゲージメント |

行数そのものではなく、次の状態を問題とする。

- ViewModelが`SubscriptionSession`、`Job`、重複排除Set、イベントMapを多数直接所有している。
- 通信結果の解釈とUI状態更新が同じメソッド内にある。
- 画面ごとに似たプロフィール取得、引用取得、エンゲージメント購読を再実装している。
- `MutableStateFlow`への書き込み経路が多く、同時実行時の上書き条件を追跡しにくい。
- ページング、手動更新、ライブイベントの競合規則がViewModel内部状態に埋め込まれている。
- 単体テストにViewModel全体とネットワーク基盤が必要になりやすい。

## 3. 完了条件

次をすべて満たした時点で完了とする。

1. 対象ViewModelはUIイベントを受け、StoreへActionを送り、Effectを開始するFacadeに限定される。
2. 対象ViewModelから`NostrRepository`の購読・送信API直接呼び出しがなくなる。
3. 対象ViewModelが`SubscriptionSession`、購読ID、購読用Job一覧を直接保持しない。
4. UI状態を書き換える主体が画面ごとのStoreに一本化される。
5. 通信コンポーネントはUI状態を直接参照・変更せず、型付きResultまたはSignalを返す。
6. ReducerはCoroutine、時刻、乱数、Repositoryへ依存しない純粋関数になる。
7. すべての購読に単一の所有者があり、`close()`で確実に終了する。
8. 既存の公開ViewModel APIと画面挙動を、各ViewModel移行フェーズ中は原則維持する。
9. 各Reducer、ページング状態機械、主要UseCaseに単体テストがある。
10. アカウント切り替え、画面破棄、更新競合に関するテストがある。

ViewModel本体は250行以内を目安とする。超える場合でも、通信詳細、イベント集約アルゴリズム、永続化処理を含めてはならない。

## 4. 対象外

今回の分割には次を含めない。

- Nostrプロトコルやイベント形式の変更
- `NostrRepository`内部実装の全面的な再設計
- UIレイアウトや画面遷移の変更
- 複数画面を1つのViewModelで共有する設計
- すべての画面へ統一MVIフレームワークを導入すること
- 公開イベントキャッシュをアカウントセッション所有へ変更すること

既存購読APIから`SubscriptionSession`への未移行箇所は、各対象ViewModelの分割に必要な範囲で移行する。

## 5. 設計原則

### 5.1 ViewModelは画面Facadeとする

画面は従来どおり1つのViewModelだけを参照する。

ViewModelの責務は次に限定する。

- 画面から受けた入力をActionまたはUseCase呼び出しへ変換する。
- Storeが公開する`StateFlow`を画面へ公開する。
- ViewModelの`viewModelScope`を配下コンポーネントへ提供する。
- 画面開始・停止に応じてControllerを開始・終了する。
- `onCleared()`で所有コンポーネントを閉じる。

### 5.2 UI状態はStoreだけが変更する

Loader、Controller、UseCaseは`MutableStateFlow<UiState>`を受け取らない。処理結果をActionへ変換し、Storeへdispatchする。

```kotlin
internal class FeedStore(
    initialState: FeedState = FeedState(),
    private val reducer: FeedReducer = FeedReducer,
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    fun dispatch(action: FeedAction) {
        _state.update { reducer.reduce(it, action) }
    }
}
```

Storeの`dispatch`は短時間で完了し、I/Oを行わない。

### 5.3 非同期処理はEffectとして分離する

非同期コンポーネントは開始時に`requestId`または`generation`を払い出し、完了Actionへ含める。

```kotlin
sealed interface FeedAction {
    data class RefreshStarted(val requestId: Long) : FeedAction
    data class HistoryReceived(
        val requestId: Long,
        val events: List<NostrEvent>,
        val outcome: FetchOutcome,
    ) : FeedAction
}
```

Reducerは現在のrequestIdと一致しない完了Actionをno-opにする。古い通信結果をViewModel側のif文で個別に防がない。

### 5.4 子コンポーネントは独立Scopeを作らない

子コンポーネントはViewModelから渡された`CoroutineScope`を使用する。`SupervisorJob()`を使った永続Scopeを内部生成してはならない。

アカウント依存の署名操作は`AccountSession`所有のSignerまたは専用インターフェースを受け取る。グローバルな現在アカウントを再取得しない。

### 5.5 共通化は振る舞い単位で行う

画面ごとのStateを無理に共通型へ統合しない。共通化対象は次に限定する。

- プロフィール不足分の収集とキャッシュ監視
- 引用イベントの解決
- ノートエンゲージメント操作
- 購読の所有と終了
- イベント削除・通報などの署名済みコマンド

タイムラインの並び順、スレッドツリー、チャンネル既読、ジャーナルの日付集約は画面固有ロジックとして残す。

### 5.6 入力・通信・集約・表示を分離する

```text
Screen
  ↓ UI command
ViewModel (Facade)
  ├── Store / Reducer ───────────────→ StateFlow<UiState>
  ├── Subscription Controller ─┐
  ├── Loader / Resolver ───────┼──→ typed Action → Store
  └── Command UseCase ─────────┘
```

通信コンポーネントはNostrイベントを取得する。イベントをどう画面表示へ反映するかはReducerまたは画面固有Accumulatorが決定する。

## 6. 共通基盤

### 6.1 SubscriptionOwner

複数の`SubscriptionSession`をViewModelが直接管理しないための所有クラスを追加する。

```kotlin
internal class SubscriptionOwner {
    suspend fun replace(slot: SubscriptionSlot, session: SubscriptionSession?)
    suspend fun close(slot: SubscriptionSlot)
    suspend fun closeAll()
}
```

要件は次のとおり。

- 同じslotをreplaceする場合は旧Sessionを閉じてから新Sessionを登録する。
- `closeAll()`は冪等にする。
- Controllerの`close()`から呼び出す。
- ViewModelは個別の購読IDやSessionを保持しない。

購読IDの生成はControllerに閉じ込め、`sessionId`、画面インスタンスID、用途を含める。

### 6.2 ProfileHydrator

プロフィール取得とキャッシュ監視を共通化する。

```kotlin
interface ProfileHydrator {
    val updates: Flow<ProfilePatch>

    fun request(pubkeys: Set<String>, policy: ProfileFetchPolicy)
    fun requestMentioned(content: String)
    fun close()
}

data class ProfilePatch(
    val profiles: Map<String, NostrProfile>,
)
```

ProfileHydratorは対象公開鍵Setだけを保持し、画面Stateを保持しない。キャッシュ更新を受けた場合は対象公開鍵分だけを`ProfilePatch`として返す。

### 6.3 QuoteResolver

引用先イベント取得を共通化する。

```kotlin
interface QuoteResolver {
    suspend fun resolve(
        eventIds: Set<String>,
        relayTarget: RelayTarget,
    ): QuoteResolution
}

data class QuoteResolution(
    val events: Map<String, NostrEvent>,
    val missingIds: Set<String>,
)
```

呼び出し側は解決済みIDと処理中IDをStoreで管理する。同じIDへの並行取得はResolver内部でまとめてもよいが、UI状態をResolverのキャッシュに依存させない。

### 6.4 NoteEngagementCoordinator

既存の`NoteEngagementService`と`EngagementReducer`を利用し、画面ごとに重複している操作開始・署名・commit・rollbackをまとめる。

```kotlin
interface NoteEngagementCoordinator {
    suspend fun execute(
        current: NoteEngagementState,
        request: EngagementRequest,
        command: NoteEngagementCommand,
    ): Flow<EngagementOutcome>
}

sealed interface EngagementOutcome {
    data class Optimistic(val operation: PendingEngagementOperation) : EngagementOutcome
    data class Signed(val operationId: EngagementOperationId, val event: NostrEvent) : EngagementOutcome
    data class Committed(val operationId: EngagementOperationId, val event: NostrEvent) : EngagementOutcome
    data class RolledBack(val operationId: EngagementOperationId, val message: String) : EngagementOutcome
}
```

Coordinatorは画面StateのMap構造を知らない。対象イベントIDから`NoteEngagementState`を取り出し、戻す処理は画面固有Reducerが担当する。

### 6.5 EngagementSubscriptionController

kind 1、6、7などの受信イベントを監視し、重複排除後に型付きSignalを返す。

```kotlin
sealed interface EngagementSignal {
    data class Reaction(val targetId: String, val event: NostrEvent) : EngagementSignal
    data class Reply(val targetId: String, val event: NostrEvent) : EngagementSignal
    data class Repost(val targetId: String, val event: NostrEvent) : EngagementSignal
    data class Quote(val targetIds: Set<String>, val event: NostrEvent) : EngagementSignal
}
```

対象IDの更新、バッチ購読、受信イベントIDの重複排除はControllerが担当する。カウント更新と自分の操作との照合はReducerが担当する。

### 6.6 SignedNoteCommandService

エンゲージメント以外のノート操作をViewModelから除去する。

```kotlin
interface SignedNoteCommandService {
    suspend fun delete(eventId: String): Result<NostrEvent>
    suspend fun report(event: NostrEvent, reason: String, detail: String): Result<NostrEvent>
    suspend fun publishReply(command: ReplyCommand): Result<NostrEvent>
}
```

チャンネルメタデータやメモ削除など固有タグを必要とする操作は、画面固有UseCaseとして分ける。

## 7. 状態モデル

### 7.1 内部StateをSliceへ分割する

巨大な`copy()`を避けるため、内部Stateを意味単位へ分割する。

```kotlin
data class FeedState(
    val timeline: TimelineSlice = TimelineSlice(),
    val engagement: EngagementSlice = EngagementSlice(),
    val profiles: ProfileSlice = ProfileSlice(),
    val loading: FeedLoadingSlice = FeedLoadingSlice(),
    val message: UiMessageSlice = UiMessageSlice(),
)
```

共通のSlice型は、意味と更新規則が完全に一致する場合だけ共有する。単に同じMapを持つという理由だけで共有しない。

### 7.2 公開UiStateの互換性

移行中は既存Screenの同時変更を避けるため、次のどちらかを使用する。

1. 既存UiStateをStoreのStateとしてそのまま使用し、Reducerだけ先に分離する。
2. 内部Stateから既存UiStateを生成する`UiStateProjector`を設ける。

最初の移行では1を推奨する。4画面の分割完了後にSlice化とScreen API整理を別変更として行う。

### 7.3 派生値

`JournalState.selectedEntries`や日付別件数など、元データだけで決まる値は次のいずれかへ移す。

- データ量が小さい場合: Stateの計算プロパティ
- データ量が大きい場合: Reducerが元データ更新時に生成するIndex

Composableの再読込ごとに大きな`groupBy`やソートを繰り返さない。Journalでは`JournalDateIndex`を内部Stateとして保持する。

## 8. FeedViewModel分割

### 8.1 分割後の構成

```text
FeedViewModel
├── FeedStore / FeedReducer
├── FeedSubscriptionController
├── FeedEventReducer
├── EngagementSubscriptionController
├── NoteEngagementCoordinator
├── ProfileHydrator
├── QuoteResolver
└── SignedNoteCommandService
```

### 8.2 FeedSubscriptionController

次を移動する。

- 履歴購読、ライブ購読、gap fill
- `loadMore()`と`refresh()`の通信処理
- EOSE、timeout、RelayOutcomeの解釈
- `oldestCreatedAt`、`nextHistoryUntil`、空ページ連続数
- 履歴・ライブ購読Sessionとgeneration
- 更新インジケータtimeout

公開Signalは次とする。

```kotlin
sealed interface FeedLoadSignal {
    data class Batch(val requestId: Long, val events: List<NostrEvent>) : FeedLoadSignal
    data class Live(val event: NostrEvent) : FeedLoadSignal
    data class Status(val status: FeedLoadStatus) : FeedLoadSignal
    data class Failed(val requestId: Long, val reason: FeedLoadFailure) : FeedLoadSignal
}
```

### 8.3 FeedEventReducer

次を移動する。

- kind 1とkind 6の正規化
- repost内容の復元
- `rawEvents`、`canonicalEvents`、sort timeの更新規則
- 重複排除
- ミュート・NGワード・返信除外
- 並び順と表示イベント生成

外部のStoreを書き換えず、`FeedTimelineSlice`とイベントを受けて新しいSliceを返す純粋Reducerとする。大量イベントの一括適用APIを持たせ、イベントごとのStateFlow更新を避ける。

### 8.4 ViewModelに残すAPI

- `startSubscriptions()`
- `stopSubscriptions()`
- `loadMore()`
- `refresh()`
- `react()`、`unreact()`、絵文字、リポスト
- `deleteEvent()`、`reportEvent()`
- `injectProfile()`

各メソッドはAction送信またはUseCase起動だけを行う。

## 9. ThreadViewModel分割

### 9.1 分割後の構成

```text
ThreadViewModel
├── ThreadStore / ThreadReducer
├── ThreadSubscriptionController
├── ThreadTreeReducer
├── ReplyPublisher
├── EngagementSubscriptionController
├── NoteEngagementCoordinator
├── ProfileHydrator
└── QuoteResolver
```

### 9.2 ThreadSubscriptionController

ルート、返信、返信数、リアクション、リポスト、引用リポストをControllerのslotとして管理する。ViewModel内の固定購読ID、購読Job一覧、seen ID Setを移動する。

ルートイベントが確定した後に必要になる子購読は、`RootResolved` Signalを受けたViewModelがControllerへ開始要求を出す。Controller自身がUI状態を参照して開始判断しない。

### 9.3 ThreadTreeReducer

次を純粋関数として分離する。

- rootと返信の関連付け
- `repliesByEventId`
- 表示順
- reply countとの統合
- 親イベント補完

同じイベントの再受信はno-opとする。返信イベント受信と集計イベント受信の順番が逆でも最終状態が一致することをテストする。

### 9.4 ReplyPublisher

`NoteContext`からタグとkindを生成し、Signerで署名して公開する。空文字、Signer不在、送信失敗を型付きFailureとして返す。

ViewModelは成功イベントを`ReplyPublished` ActionとしてStoreへ送り、入力欄をクリアする。失敗時は入力を保持する。

## 10. ChannelViewModel分割

### 10.1 分割後の構成

```text
ChannelViewModel
├── ChannelStore / ChannelReducer
├── ChannelSubscriptionController
├── ChannelMessageReducer
├── ChannelMessagePublisher
├── ChannelMetadataEditor
├── ChannelReadTracker
├── EngagementSubscriptionController
├── NoteEngagementCoordinator
└── ProfileHydrator
```

### 10.2 ChannelSubscriptionController

次を所有する。

- チャンネルメタデータ購読
- メッセージ履歴とライブ購読
- ページング、EOSE、timeout
- engagement対象IDのバッチ購読
- Controller内のSubscriptionSessionと購読Job

チャンネルID、RelayTarget、ページサイズを生成時に固定する。Relay変更は既存Controllerを閉じ、新しいControllerを生成するか、世代付き`restart()`で明示する。

### 10.3 ChannelMessageReducer

次を純粋関数として分離する。

- メッセージ重複排除とソート
- ミュート・NGワード除外
- メタデータとメッセージから`Ready`状態を生成
- 受信イベントによるページング境界更新

### 10.4 投稿・編集・既読

- `ChannelMessagePublisher`: 下書きからイベントを作成して公開する。
- `ChannelMetadataEditor`: タイトル・説明の検証、kind 41イベント生成、公開を担当する。
- `ChannelReadTracker`: 最新表示イベントと保存済み既読時刻から未読状態を更新・永続化する。

既読保存はReducerから行わず、`LatestVisibleChanged` Actionを受けたEffectとして実行する。

## 11. JournalViewModel分割

### 11.1 分割後の構成

```text
JournalViewModel
├── JournalStore / JournalReducer
├── JournalCalendarReducer
├── JournalLoader
├── JournalEventDecoder
├── JournalDateIndex
├── JournalDeletionService
├── JournalEngagementLoader
├── ProfileHydrator
└── ReferencedContentResolver
```

### 11.2 JournalCalendarReducer

次を純粋Actionとして扱う。

- 日付選択
- 前日・翌日
- 前月・翌月
- カレンダー表示切り替え
- 当日更新
- 読み込み種別変更

日付変更Actionは次に必要なLoadRequestをReducerの戻り値へ埋め込まず、State変更後にViewModelがEffect判定する。Reducerは時刻を直接取得せず、`today`をAction引数として受け取る。

### 11.3 JournalLoader

次を移動する。

- 月単位取得
- 日単位取得
- 未取得種別の判定
- 月バックフィル
- EOSEとtimeout待機
- LoadContext解決
- リレーURLと対象公開鍵の固定

```kotlin
data class JournalLoadRequest(
    val requestId: Long,
    val range: JournalRange,
    val kinds: Set<JournalLoadKind>,
    val targetPubkey: String,
    val relayTarget: RelayTarget,
)
```

完了結果には、取得できたkind、settledしたリレー、timeout、部分成功を含める。空配列だけで「イベントなし」と「取得失敗」を表現しない。

### 11.4 JournalEventDecoderとIndex

`JournalEventDecoder`はNostrイベントからメモ、ノート、記事等を分類・復号する。Signerまたは復号インターフェースを明示的に受け取る。

`JournalDateIndex`は次を保持する。

- 日付別メモID
- 日付別ノートID
- 月別エントリID
- 日付別件数

イベント追加・削除時に差分更新し、`JournalState`のgetter内で全件`groupBy`し直さない。

### 11.5 削除と参照先取得

`JournalDeletionService`はメモ削除と通常ノート削除を別Commandとして扱う。削除成功後に削除対象IDと削除イベントを返し、Storeがローカル一覧を更新する。

`ReferencedContentResolver`は返信元と引用先をまとめて取得し、プロフィール取得とは分離する。処理中IDを管理し、同じ参照先の多重取得を避ける。

## 12. 依存関係の生成

画面ごとのFactoryで依存を個別生成するのではなく、ViewModel内部または専用Factoryで構築する。

```kotlin
internal class FeedViewModelFactory(
    private val accountSession: AccountSession?,
    private val dependencies: TimelineDependencies,
) {
    fun create(config: FeedConfig): FeedViewModel
}

data class TimelineDependencies(
    val subscriptions: SubscriptionGateway,
    val profiles: ProfileRepositoryGateway,
    val quotes: QuoteRepositoryGateway,
    val clock: Clock,
)
```

テストではGatewayをFakeへ差し替える。Productionコードで巨大なService Locatorやグローバル`object`を新設しない。

`AccountSession`から渡すものはSigner、MuteStore、NgWordStoreなど必要最小限にする。公開キャッシュは共有Gatewayとして渡してよい。

## 13. ライフサイクル

### 13.1 所有関係

| リソース | 所有者 | 終了契機 |
| --- | --- | --- |
| ViewModel scope | ViewModel | `onCleared()` |
| 画面購読Controller | ViewModel | stopまたは`onCleared()` |
| SubscriptionSession | 購読Controller | replace、restart、Controller close |
| ProfileHydrator監視 | ViewModel | `onCleared()` |
| 単発UseCase Job | ViewModel scope | 完了または`onCleared()` |
| アカウントSigner | AccountSession | セッションclose |

Controllerの`close()`はsuspendかつ冪等とする。`onCleared()`からsuspend終了が必要な場合は、ViewModel scopeがキャンセルされる前に個別Sessionの同期的close要求を発行できるAPIを用意するか、Controllerの各collectorの`finally`で必ずSessionを閉じる。

### 13.2 startとstop

- `start()`は冪等にする。
- `stop()`後の再startを許すかどうかを型または状態で明示する。
- Feedのタブ切替など再startが必要なものは`Stopped → Starting → Active`を許す。
- Thread、Channelのように画面寿命と一致するものは生成時start、close後再利用不可でもよい。
- Controllerをcloseした後のSignalはStoreへ反映しない。

## 14. エラー設計

通信層のThrowable文字列をそのままUIへ渡さない。

```kotlin
sealed interface TimelineFailure {
    data object NoRelay : TimelineFailure
    data object Timeout : TimelineFailure
    data object SessionClosed : TimelineFailure
    data class RelayRejected(val reasons: Map<String, String>) : TimelineFailure
    data class Unexpected(val cause: Throwable) : TimelineFailure
}
```

画面固有ReducerまたはMessageMapperが日本語表示文へ変換する。

`CancellationException`はエラーActionへ変換せず再throwする。セッション切り替えや画面破棄によるキャンセルでスナックバーを表示しない。

## 15. 並行処理規則

### 15.1 更新優先順位

- 手動refresh開始後に旧履歴requestが完了しても破棄する。
- ライブイベントは履歴取得中でもEvent IDで統合する。
- ページング中の二重`loadMore()`はno-opにする。
- Relay変更後の旧Relay結果はgeneration不一致で破棄する。
- 署名操作は同一`EngagementSlot`単位で直列化する。
- Journalの日付変更は旧日付の完了結果をキャッシュへ保存できるが、選択中表示を上書きしてはならない。

### 15.2 単一書き込み

Store更新はReducer経由に限定する。複数CoroutineからActionが届いても、ActionにrequestIdを含めることで決定的に処理する。

Reducerが時刻や乱数を必要とする場合、それらをAction生成側で値として確定して渡す。

## 16. 配置

```text
ui/
├── timeline/
│   ├── ProfileHydrator.kt
│   ├── QuoteResolver.kt
│   ├── EngagementSubscriptionController.kt
│   └── SignedNoteCommandService.kt
├── feed/
│   ├── FeedViewModel.kt
│   ├── FeedState.kt
│   ├── FeedAction.kt
│   ├── FeedReducer.kt
│   ├── FeedSubscriptionController.kt
│   └── FeedEventReducer.kt
├── thread/
│   ├── ThreadViewModel.kt
│   ├── ThreadState.kt
│   ├── ThreadReducer.kt
│   ├── ThreadSubscriptionController.kt
│   ├── ThreadTreeReducer.kt
│   └── ReplyPublisher.kt
├── channel/
│   ├── ChannelViewModel.kt
│   ├── ChannelState.kt
│   ├── ChannelReducer.kt
│   ├── ChannelSubscriptionController.kt
│   ├── ChannelMessagePublisher.kt
│   ├── ChannelMetadataEditor.kt
│   └── ChannelReadTracker.kt
└── post/journal/
    ├── JournalViewModel.kt
    ├── JournalState.kt
    ├── JournalReducer.kt
    ├── JournalCalendarReducer.kt
    ├── JournalLoader.kt
    ├── JournalEventDecoder.kt
    ├── JournalDateIndex.kt
    └── JournalDeletionService.kt
```

既存パッケージとの大規模な移動を避ける場合、Journalは当初`ui/post/`内に置いてよい。分割とパッケージ移動を同じ変更で行わない。

## 17. 移行手順

### Phase 0: 特性テスト

1. 現在のReducer相当処理に対する入力・出力テストを追加する。
2. 履歴とライブの同一イベント統合をテストする。
3. EOSE、timeout、空ページ、部分成功をテストする。
4. エンゲージメントの受信順序と楽観更新の競合をテストする。
5. アカウント切り替え時のキャンセルをテストする。

### Phase 1: 共通部品

1. `SubscriptionOwner`を追加する。
2. `ProfileHydrator`を追加する。
3. `QuoteResolver`を追加する。
4. `NoteEngagementCoordinator`を既存Service/Reducer上に追加する。
5. `SignedNoteCommandService`を追加する。

共通部品は利用する最初の画面と同じ変更で追加し、未使用の抽象化だけを先行導入しない。

### Phase 2: ThreadViewModel

最も規模が小さく、ルート・返信という境界が明確なThreadをパイロットとする。

1. `ThreadReducer`と特性テストを追加する。
2. `ReplyPublisher`を抽出する。
3. プロフィール・引用処理を共通部品へ移す。
4. `ThreadSubscriptionController`を抽出する。
5. エンゲージメント処理をCoordinatorへ移す。
6. ViewModelをFacadeへ縮小する。

### Phase 3: FeedViewModel

1. `FeedEventReducer`を抽出する。
2. ページング状態機械をテスト付きで抽出する。
3. 履歴・ライブ・gap fillを`FeedSubscriptionController`へ移す。
4. プロフィール、引用、エンゲージメントを共通部品へ移す。
5. 削除・通報をCommand Serviceへ移す。
6. 高頻度StateFlow更新のバッチ規則をStoreへ移す。

### Phase 4: ChannelViewModel

1. メッセージReducerを抽出する。
2. メタデータ編集、投稿、既読を別UseCaseへ移す。
3. 購読とページングをControllerへ移す。
4. 共通プロフィール・エンゲージメント部品を適用する。
5. `Loading`と`Ready`の状態遷移テストを追加する。

### Phase 5: JournalViewModel

1. 日付・月操作を`JournalCalendarReducer`へ移す。
2. メモ復号とイベント分類を`JournalEventDecoder`へ移す。
3. 月・日取得を`JournalLoader`へ移す。
4. `JournalDateIndex`を導入する。
5. 削除、プロフィール、参照先、エンゲージメントを分離する。
6. ViewModelをFacadeへ縮小する。

### Phase 6: 旧実装撤去

1. ViewModel内の購読ID定数とSubscriptionSessionフィールドを削除する。
2. ViewModel内のseen ID Setと受信イベントMapを対応Controllerへ移す。
3. ViewModel内の`NostrRepository`直接参照を削除する。
4. 使われなくなったAccumulatorと互換Helperを削除する。
5. Screen側の公開APIを必要に応じてSlice型へ移行する。

## 18. テスト仕様

### 18.1 Reducer単体テスト

- 同じAction列から常に同じStateになる。
- 重複イベントがno-opになる。
- 古いrequestIdの完了がno-opになる。
- optimistic、commit、rollbackが対象イベントだけを変更する。
- 削除Actionがイベント、Index、エンゲージメント状態を一貫して削除する。
- イベント受信順序が異なっても最終集約結果が一致する。

### 18.2 Controller単体テスト

- startが冪等である。
- replace時に旧SubscriptionSessionが閉じる。
- closeですべてのSessionが閉じる。
- close後のSignalが流れない。
- refreshが旧requestを無効化する。
- timeoutとRelay拒否を空結果と区別する。

### 18.3 UseCase単体テスト

- Signer不在時に送信しない。
- 署名イベントのkind、content、tagsが正しい。
- 公開失敗時に型付きFailureを返す。
- キャンセルをFailureに変換しない。
- 旧AccountSessionのSignerでは操作できない。

### 18.4 ViewModel結合テスト

- UI commandが期待するControllerまたはUseCaseを1回だけ呼ぶ。
- Fake ControllerのSignalがStateへ反映される。
- 画面破棄ですべてのControllerがcloseされる。
- アカウント切り替え後に旧結果が新Stateへ入らない。
- 既存Screenが必要とする公開Stateとメソッドが維持される。

### 18.5 画面別回帰

#### Feed

- 初回読込、手動更新、追加読込、ライブ追加
- Following、Global、ユーザー別、ハッシュタグ、単一Relay
- repost、reply除外、Mute、NGワード

#### Thread

- root取得、返信ツリー、返信投稿、引用、各集計
- rootより返信が先に届くケース

#### Channel

- メタデータ取得、初回メッセージ、追加読込、ライブ受信
- 投稿、メタデータ編集、既読、Mute、NGワード

#### Journal

- 日・月移動、月バックフィル、種別切替
- メモ復号、通常ノート、記事、返信、リポスト、Like
- メモ削除、ノート削除、参照先取得

## 19. 静的確認条件

移行完了時に対象4ViewModelについて次を確認する。

```text
NostrRepository.
SubscriptionSession
SubscriptionSpec
private val .*SubId
private val seen.*Ids
private val received.*Events
SupervisorJob
KeyStorage
AccountSessions.manager.currentSession
```

`NostrRepository`、`SubscriptionSession`、購読ID、重複排除SetはControllerまたはUseCase内には存在してよい。ViewModel内の検索結果を0件にする。

また、次を確認する。

- `MutableStateFlow`の生成箇所が画面ごとにStoreの1箇所だけである。
- ReducerファイルにCoroutine、Repository、Clockの直接参照がない。
- ControllerとUseCaseが独立した`SupervisorJob`を生成していない。
- すべてのControllerにcloseテストがある。

## 20. 受け入れ条件

- 既存4画面の主要機能と表示順が維持される。
- ViewModelがUI Facadeとして読める規模と責務になる。
- 通信、集約、署名操作、プロフィール取得を個別にテストできる。
- 同じStateを複数コンポーネントが直接変更しない。
- 履歴、ライブ、手動更新、ページングの競合規則がテストで明文化されている。
- 画面破棄とアカウント切り替えで旧購読・旧処理が終了する。
- `./gradlew :composeApp:allTests`が成功する。
- iOS SimulatorとAndroid DebugのKotlinコンパイルが成功する。
