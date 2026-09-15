# フォローフィード リレー別履歴マージ設計

## 1. 目的

フォロー中フィードで複数リレーを利用するとき、応答しないリレーがあっても、応答可能なリレーの履歴読み込みを継続できるようにする。

同時に、応答しなかったリレーの履歴カーソルを進めず、復旧後に未取得範囲を同じ位置から取得できることを保証する。

本設計でいう「安全」は、すべてのリレーを待って完全な時系列を固定することではない。次の性質を満たすことを指す。

- あるリレーの失敗が、ほかのリレーのページングを停止させない。
- `EOSE` を受信していないリレーについて、取得済み範囲を過大に主張しない。
- タイムアウト、切断、`CLOSED` では、そのリレーの履歴カーソルを進めない。
- 復旧したリレーは、最後に正常完了した位置から再開する。
- 遅れて届いたイベントはイベント ID で重複排除し、時系列へ統合する。
- NIP-01だけでは完全性を証明できない境界秒は穴として保持し、完全取得済みと偽らず、それより古い読み込みは継続する。

## 2. 対象範囲

対象は、フォロー中フィードで「すべてのリレー」を選択した場合の kind 1、kind 6、およびフィード設定で有効な関連イベントの履歴取得とする。

次は本設計の対象外とする。

- ライブ購読のリレー別分割
- NIP-65 Outbox Model による投稿者単位の取得先最適化
- 履歴カーソルのアプリ再起動をまたぐ永続化
- リレー品質を利用した自動的な設定変更

ライブ購読は従来どおり複数リレーへまとめて送信できる。ライブイベントにはページ完了の依存関係がないため、イベント ID で統合できればよい。

## 3. 設計判断

### 3.1 事前ウォームアップを行わない

接続確認専用の WebSocket、Ping、NIP-11 HTTP、`limit: 0` の試験購読は、フィード取得前には実行しない。

接続成功だけでは、実際のフィルターに対する `EVENT`、`EOSE`、`CLOSED`、NIP-42 認証要求を判定できない。履歴取得の `REQ` 自体を疎通確認として扱う。

### 3.2 履歴取得をリレー単位、カーソルを論理フィルター単位にする

履歴ページの有限取得は、1セッションにつき1リレーを対象とする。

```kotlin
val activePartitions = feedFilterPartitions().filter { partition ->
    relayState.cursors.getValue(partition.key).let { cursor ->
        !cursor.exhausted && cursor.blockedReason == null
    }
}

SubscriptionSpec(
    id = historySubscriptionId(relayUrl, generation),
    filters = activePartitions.map { partition ->
        val cursor = relayState.cursors.getValue(partition.key)
        partition.toNostrFilter(
            until = cursor.requestedUntil,
            limit = cursor.requestedLimit,
        )
    },
    target = RelayTarget.Single(relayUrl),
    behavior = SubscriptionBehavior.Fetch(HISTORY_FETCH_TIMEOUT_MS),
    deduplicateEvents = false,
)
```

NIP-01の`limit`はフィルターごとに適用される。kind 1/6用フィルターが上限へ達する一方で、kind 1111用フィルターがより古いイベントを返すことがあるため、リレー全体で単一カーソルを共有してはならない。

`FeedFilterPartition` は安定した `FeedFilterKey` を持つ。現在のフォローフィードでは、少なくとも通常投稿・リポストとNIP-22コメントを別パーティションにする。1セッションへまとめるパーティション同士は、イベントを必ず一意に分類できる非重複なwire filterにする。条件が重複する場合、優先順位による分類は行わず、別の購読IDを持つ有限取得セッションへ分離する。リレーは重複するフィルター間でイベントをまとめることがあり、クライアント側の優先分類だけでは各フィルターの取得件数を復元できないためである。

リレーごとに異なるフィルター別 `until` と `limit` を送れること、完了と失敗をほかのリレーから独立して処理できることを優先する。通常は対象リレーごとに1件、重複フィルターの分離取得時は同じリレーで直列に複数件の有限購読が発生する。同時に存在する有限購読は1リレー1件以下とし、完了後に必ず閉じる。

### 3.3 表示完了と通信完了を分ける

画面の `isLoadingMore` と、リレーごとの通信中状態を別に管理する。

- `isLoadingMore`: ユーザーが開始した1回の追加読み込みに対する前景表示
- `inFlight`: そのリレーの有限取得セッションが動作中か

最初の正常結果を表示した後に前景表示を終了しても、遅いリレーの有限取得はタイムアウトまたは完了まで継続できる。前景表示が終了したことを理由にセッションを閉じない。

`loadMore()` はグローバルな `loadingMore` だけで拒否せず、リレーごとの `inFlight` を見て、進行可能なリレーだけを次のページへ進める。

### 3.4 参考実装との関係

Amethyst の `RelayLoadingCursors` は `requestedUntil` と `reachedUntil` をリレー別に持ち、空ページと `EOSE` だけを終端とする。`BackwardRelayPager` は取得不能なリレーを `stalled` として残し、ほかのリレーを独立して進める。本設計はこの安全性を採用する。

- [Amethyst RelayLoadingCursors](https://github.com/vitorpamplona/amethyst/blob/8d75a50df963eef46dfbdcdedb87c8658dae566a/quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip01Core/relay/client/paging/RelayLoadingCursors.kt)
- [Amethyst BackwardRelayPager](https://github.com/vitorpamplona/amethyst/blob/8d75a50df963eef46dfbdcdedb87c8658dae566a/commons/src/jvmAndroid/kotlin/com/vitorpamplona/amethyst/commons/relayClient/paging/BackwardRelayPager.kt)

Ditto は全readリレーへ同じREQを送り、最初の `EOSE` 後の短い猶予で結果を確定するため、停止リレーに待たされにくい。一方、フィード履歴は全リレー共通のカーソルであり、遅いリレーの未取得範囲を個別には保持しない。本設計はDittoの前景応答性だけを採用し、履歴カーソルは共有しない。

- [Ditto NostrProvider](https://gitlab.com/soapbox-pub/ditto/-/blob/82bc4d61aeacca6b1476e47cda6fb4460f1deb91/src/components/NostrProvider.tsx)
- [Ditto useFeed](https://gitlab.com/soapbox-pub/ditto/-/blob/82bc4d61aeacca6b1476e47cda6fb4460f1deb91/src/hooks/useFeed.ts)

### 3.5 有限取得シグナルを欠落させない

有限取得では、`EVENT`、`EOSE`、`CLOSED`、`FetchCompleted`の配送順序と欠落しないことを購読gatewayの契約にする。イベントを欠落したまま`EOSE`だけを処理すると、実際には保持していない範囲へカーソルを進めるためである。

現行の`SubscriptionSessionImpl`は容量512のChannelへ`trySend`しており、満杯時はシグナルをログだけで破棄する。`MAX_HISTORY_PAGE_SIZE`は512件を超えるため、このまま履歴マージへ利用してはならない。実装前に有限取得セッションを損失なしのsuspending sendへ変更し、リポジトリの状態mutex外で送信してbackpressureをかける。ライブ購読の配送方針は本変更の対象外とし、有限取得と別に扱える。

現行の`openSubscription()`はセッションを返す前にキャッシュ再生を行うため、単純に`trySend`をsuspending sendへ置き換えると、バッファ上限を超えた時点で呼び出し元がcollectorを開始できずデッドロックする。有限取得の開始APIは「セッションを返してcollectorを接続する」「ordered producerを開始してキャッシュ再生とwireシグナルを同じキューへ流す」の順序へ変更する。明示的な`start()`を設けるか、collector開始でproducerが動くcold Flowとし、セッション返却前に有限バッファへ全件を詰めない。

有限取得セッションは、同じリレーについてwire受信順にシグナルを送る。`EOSE`または`CLOSED`より前に受信した全`EVENT`がcollectorへ配送された後で終端を配送し、最後に`FetchCompleted`を配送してからFlowを閉じる。collectorのキャンセルやセッションcloseは送信待ちを解除できなければならない。

## 4. 状態モデル

### 4.1 フィード履歴スコープ

リレー・フィルター別カーソルがどの取得条件に対して有効かを、フィード履歴スコープで固定する。

```kotlin
internal data class FeedHistoryScope(
    val fingerprint: String,
    val historyFloor: Long,
)
```

`historyFloor` はフィード世代を開始した時刻のUnix秒とし、その世代の間は動かさない。初回履歴取得は全リレーで `until = historyFloor`、ライブ購読は `since = historyFloor` とする。境界秒はイベントIDで重複排除する。

`fingerprint` は少なくとも次を正規化して含める。

```text
accountSessionId
feedMode
filterSchemaVersion
sorted(authorPubkeys)
sorted(eventKinds)
includeReposts
includeReplies
hashtag
```

`filterSchemaVersion` は `FeedFilterKey` の構成やフィルターへの分類規則を変更したときに更新する。フォロー追加・解除、フィード種別、取得kindなどが変わり `fingerprint` が変化した場合、以前のカーソルを引き継がず、新しい `historyFloor` で履歴スコープを作り直す。カーソルはリレーだけでなくフィルター条件に対して獲得した状態だからである。

### 4.2 リレー・フィルター別カーソル

```kotlin
@JvmInline
internal value class FeedFilterKey(val value: String)

internal data class RelayFilterCursor(
    val filterKey: FeedFilterKey,
    val requestedUntil: Long,
    val reachedUntil: Long? = null,
    val requestedLimit: Int = FEED_PAGE_SIZE,
    val exhausted: Boolean = false,
    val blockedReason: FilterCursorBlockReason? = null,
    val boundaryEventIds: Set<String> = emptySet(),
    val unresolvedBoundaries: Map<Long, UnresolvedBoundary> = emptyMap(),
)

internal sealed interface FilterCursorBlockReason {
    data object CoverageGapLimit : FilterCursorBlockReason
    data class StructuralRefusal(
        val reason: String,
        val disposition: RetryDisposition,
    ) : FilterCursorBlockReason
}

internal data class UnresolvedBoundary(
    val createdAt: Long,
    val observedEventIds: Set<String>,
    val reason: String,
)

internal data class RelayHistoryState(
    val relayUrl: String,
    val cursors: Map<FeedFilterKey, RelayFilterCursor>,
    val status: RelayHistoryStatus = RelayHistoryStatus.Idle,
    val consecutiveFailures: Int = 0,
    val retryAtMillis: Long? = null,
)
```

`requestedUntil` はそのフィルターパーティションで現在または次の `REQ` に指定する包含境界、`reachedUntil` は `EOSE` まで受信して確定した最古の `created_at` とする。新しいカーソルの `requestedUntil` はスコープの `historyFloor` で初期化する。

通信状態と失敗回数は1リレー1有限セッションに対応するため `RelayHistoryState` に置く。ページ境界、上限、終端、境界秒IDは `RelayFilterCursor` に置く。あるパーティションが`exhausted`でも、同じリレーの別パーティションは継続できる。

### 4.3 リレー状態

```kotlin
internal sealed interface RelayHistoryStatus {
    data object Idle : RelayHistoryStatus
    data class InFlight(
        val requestId: Long,
        val startedAtMillis: Long,
        val terminalObserved: Boolean = false,
    ) : RelayHistoryStatus
    data object Parked : RelayHistoryStatus
    data class Stalled(val reason: StallReason) : RelayHistoryStatus
    data object AwaitingAuth : RelayHistoryStatus
    data class Suppressed(val reason: String) : RelayHistoryStatus
}

internal enum class StallReason {
    TimedOut,
    Unavailable,
    Disconnected,
}
```

- `Idle`: 未取得または次ページを要求できる。
- `InFlight`: 有限取得セッションが存続中。`EOSE`や`CLOSED`を観測済みでも、基盤の完了通知まではこの状態を維持し、同じリレーへ重複したページ要求を出さない。
- `Parked`: 1ページを正常完了し、次のユーザー要求を待つ。
- `Stalled`: 一時的に取得不能。カーソルを維持し、再試行可能。
- `AwaitingAuth`: NIP-42認証待ち。認証成功までは自動・手動とも再試行しない。
- `Suppressed`: 進行可能なフィルターパーティションが残らないリレー集約状態。設定またはフィルター変更まで自動再試行しない。

`exhausted` と `blockedReason` はフィルターパーティションごとに保持する。そのパーティションについて空ページと `EOSE` を受信した場合だけ `exhausted = true` にする。`StructuralRefusal`と`CoverageGapLimit`は該当パーティションだけを停止し、同じリレーのほかのパーティションを停止しない。

`unresolvedBoundaries`は、リレーが境界秒の全イベントを返したと証明できないまま、それより古い範囲の取得を継続するための明示的なcoverage gapである。値が残る限り、そのリレー・フィルターを「完全取得済み」と表示しない。

### 4.4 ページ内集計

```kotlin
internal data class RelayPageAccumulator(
    val requestId: Long,
    val scopeFingerprint: String,
    val requestedPartitions: Map<FeedFilterKey, FilterRequestSnapshot>,
    val eventIdsByFilter: Map<FeedFilterKey, Set<String>> = emptyMap(),
    val createdAtByFilter: Map<FeedFilterKey, Map<String, Long>> = emptyMap(),
    val terminal: RelayPageTerminal? = null,
    val terminalEffectsApplied: Boolean = false,
)

internal data class FilterRequestSnapshot(
    val requestedUntil: Long,
    val requestedLimit: Int,
    val boundaryEventIds: Set<String>,
)

internal sealed interface RelayPageTerminal {
    data object Eose : RelayPageTerminal
    data class Closed(
        val reason: String,
        val disposition: RetryDisposition,
    ) : RelayPageTerminal
    data object Unavailable : RelayPageTerminal
    data object TimedOut : RelayPageTerminal
    data object StorageFailed : RelayPageTerminal
}
```

受信イベントは、REQ作成時に固定したパーティション定義で `FeedFilterKey` へ分類してから集計する。ページ内の件数、最古時刻、空ページ、同一秒集中の判定は、全リレー共通の重複排除後件数でもリレー全体の合計でもなく、そのリレー・フィルターから受信したイベントIDで判定する。分類不能、または複数パーティションへ一致するイベントは、要求外イベントとして記録して破棄し、ストアや表示へ統合せず、いずれのカーソルも進めない。

`requestedPartitions`はREQ送信時のフィルター集合とカーソル境界を固定する。`EOSE`時の空ページ判定は、イベントを受信したキーの集合ではなく`requestedPartitions.keys`との差分で行う。状態変更後のカーソルを参照して完了計算してはならない。

ページイベントはaccumulatorへ一時保持し、`EOSE`または失敗結果でストアへcommitしてから表示へ通知する。これにより、表示済みだがストアに存在しない状態を作らない。

`terminal`は、wire終端を観測した時点ではなく、その終端に必要なストアcommitまで成功または失敗した後の確定結果を保存する。`EOSE`ならcommit成功後に`Eose`、commit失敗時に`StorageFailed`を保存する。`Closed`までに受信した不完全ページも同様に、commit成功後に`Closed`、失敗時に`StorageFailed`を保存する。結果に伴うカーソル、失敗回数、バックオフ、ストレージ状態の更新を`terminalEffectsApplied`で一度だけ適用し、後続の`FetchCompleted`は保存済み終端を上書きしない。終端処理は `requestId` 単位で冪等にする。

## 5. 状態遷移

```text
Idle / Parked / Stalled
        │ loadMore または再試行期限到来
        ▼
     InFlight
        ├─ EVENT ───────────────> ページ内集計を更新
        ├─ EOSE（イベントあり）─> カーソル確定 ─> InFlight(terminalObserved)
        ├─ EOSE（空ページ）─────> exhausted = true ─> InFlight(terminalObserved)
        ├─ transient CLOSED ─────> 失敗結果を確定 ─> InFlight(terminalObserved)
        ├─ structural CLOSED ────> 分離取得または抑止結果を確定 ─> InFlight(terminalObserved)
        ├─ timeout / unavailable ─> セッション終了 ─> Stalled
        └─ FetchCompleted ───────> 確定済み結果に応じて Parked / Stalled / AwaitingAuth / Suppressed
```

古いセッションのコールバックが新しいページへ混入しないよう、すべての更新で `requestId` を照合する。一致しない `EVENT`、`EOSE`、完了通知は破棄する。

## 6. カーソル更新規則

### 6.1 初回ページ

フィード履歴スコープ作成時に `historyFloor` を固定し、初回の `requestedUntil` に設定する。各リレーは同じ上端から最新ページを要求する。

初回ページで一部リレーが失敗しても、正常完了したリレーの各フィルターの `reachedUntil` は独立して確定する。失敗リレーの全フィルターは `requestedUntil = historyFloor` を維持して再試行する。再試行時刻が遅れても上端を現在時刻へ動かさない。

### 6.2 正常ページ

`EOSE` までにフィルターパーティションから1件以上受信した場合、そのパーティションの最古時刻を `pageOldest` とする。以下の規則は各 `RelayFilterCursor` へ独立して適用する。

- `reachedUntil = pageOldest` とし、実際に受信した最古時刻だけを記録する。
- `pageOldest < requestedUntil`なら、受信件数にかかわらず次回の`requestedUntil = pageOldest`とし、境界秒を包含して再取得する。`boundaryEventIds`には今回受信した`created_at == pageOldest`のIDだけを保持する。
- `pageOldest == requestedUntil`なら、受信件数にかかわらず6.3の同一秒集中規則を適用する。

NIP-01 の `until` は包含境界である。境界秒を再取得することで、同じ秒に複数イベントが存在しても、確認せず完全取得済みとは扱わない。

NIP-01では、`limit`は最大件数であり、リレーがそれより少ないイベントを返すことも許される。したがって、`EOSE`を伴う短い非空ページだけで境界秒の完全取得を証明してはならない。一方、NIP-01には同じ秒の途中から再開する継続トークンがないため、証明不能な境界は6.3のcoverage gapとして残し、それより古い範囲の読み込みを継続する。

### 6.3 同一秒に上限件数が集中する場合

あるフィルターパーティションで`pageOldest == requestedUntil`の場合は、受信件数が`requestedLimit`未満でも境界完了とはみなさない。カーソルを進めず、同じ`requestedUntil`で`requestedLimit`を2倍にし、`boundaryEventIds`へ今回のIDを統合する。

`requestedLimit` は `MAX_HISTORY_PAGE_SIZE` まで拡大する。最大値の応答でも`pageOldest == requestedUntil`なら、その秒を`UnresolvedBoundary`へ保存し、次回の`requestedUntil = pageOldest - 1`、`requestedLimit = FEED_PAGE_SIZE`、`boundaryEventIds = emptySet()`として古い範囲を継続する。これは境界秒を完全取得済みとする操作ではなく、未解決の穴を明示したまま別範囲へ進む操作である。

coverage gapは通常の末尾スクロールを妨げない。ユーザーの明示的再試行またはリレーの接続世代変更時に、`since = until = createdAt`、`limit = MAX_HISTORY_PAGE_SIZE`で低優先度の再確認を行う。空ページならリレー上から消滅したものとして解決し、新しいIDを得た場合は集合へ統合するが、非空応答だけで完全取得済みとはしない。

`unresolvedBoundaries`は1カーソルにつき`MAX_UNRESOLVED_BOUNDARIES`件、初期値128件まで保持する。上限へ達した場合は最古の穴を捨てず`blockedReason = CoverageGapLimit`として、そのパーティションの新しい通常履歴REQを停止する。履歴リセットでは穴も破棄する。

境界より古い時刻まで進んだ場合、またはcoverage gapを記録してその秒を離れた場合は、`requestedLimit` を `FEED_PAGE_SIZE` に戻す。

### 6.4 空ページ

あるフィルターパーティションについてイベントが0件で、リレーからセッション全体の `EOSE` を受信した場合のみ、そのパーティションの `exhausted = true` とする。

短い非空ページは、そのフィルターの現在クエリが完了したことにも、リレーの全履歴が終端であることにも使わない。非空ページでは6.2と6.3に従い、境界秒を再取得またはcoverage gapとして保持してから、さらに過去を確認する。

通常履歴が空ページへ到達して`exhausted = true`になっても、`unresolvedBoundaries`が残る場合は「走査可能な過去範囲は終端、ただし未解決境界あり」とする。全範囲を完全取得済みとは表示しない。

### 6.5 失敗

次の場合は、そのリレーに属する全フィルターの `requestedUntil`、`reachedUntil`、`boundaryEventIds` を変更しない。

- タイムアウト
- 接続失敗または切断
- `CLOSED`
- セッションキャンセル
- 古い `requestId` の完了通知

失敗までに受信したイベントは不完全ページとしてストアへID重複排除でcommitし、表示へ統合してよい。ただし、そのcommit結果をカーソル進行や`exhausted`判定には使わない。復旧後は同じ境界を再取得する。

### 6.6 終端通知の冪等化

現在の購読基盤は、正常時にリレー別の `EOSE` を送った後、同じ取得について `FetchCompleted(Eose)` を送る。拒否時も `Closed` と `FetchCompleted(Closed)` の両方が届き得る。

同じ結果を二重反映しないため、次の規則を適用する。

- `EOSE` でフィルター別ページ結果を計算し、イベントページを`FeedHistoryStore`へcommitする。commit成功後に`terminal = Eose`を保存し、各カーソルの`reachedUntil`、`requestedUntil`または`exhausted`を一度だけ更新する。状態は `InFlight(terminalObserved = true)` のままにする。
- ストアcommitに失敗した場合は`terminal = StorageFailed`を保存し、全カーソルを維持したまま`storageFailure`を設定する。セッションと前景表示は終了させるが、リレーの失敗回数には加算しない。
- `Closed`までに受信した不完全ページも先にストアへcommitし、成功後に`terminal = Closed`を保存して一時失敗または構造的拒否の結果を一度だけ確定する。commit失敗時は`StorageFailed`を優先する。基盤の完了通知までは `InFlight(terminalObserved = true)` を維持する。
- `RelayUnavailable` やタイムアウトなど、それ自体がセッション終了を表す通知では、その場で最終状態へ遷移する。
- `FetchCompleted` は、まだ結果を確定していない場合に限り結果を確定し、確定済みの場合はカーソルを更新せず、セッション参照の解放、最終状態への遷移、collector終了だけを行う。
- 失敗回数の加算、バックオフ予約、前景世代のsettled数更新は1リクエストにつき1回だけ行う。
- `EOSE` 後も、基盤が `FetchCompleted` を送って自動CLOSEするまでは同じリレーの次ページを開始しない。

実装では、ページ集計の`terminal`、`terminalEffectsApplied`、`requestId`、`scopeFingerprint`を同じ排他区間で確認・更新する。終端観測済みaccumulatorは`FetchCompleted`を処理するまで保持する。`StorageFailed`を保存した後に`FetchCompleted(Eose)`が来ても、カーソルを成功更新しない。commit中に履歴スコープが変わった場合はcommit結果を破棄し、旧スコープのカーソルも新スコープのストアも更新しない。

## 7. リレー選択と読み込み調停

### 7.1 `loadMore()` の対象

次をすべて満たすリレーを進行可能とする。

- 現在の `RelayTarget` に含まれる
- 1つ以上のフィルターパーティションが `exhausted` でなく、`blockedReason` もない
- `InFlight` ではない
- `AwaitingAuth` ではない
- `Suppressed` ではない
- `Stalled` の場合は再試行期限に達している、またはユーザーが明示的に再試行した

同じ `loadMore()` で対象になったリレーは並列に開始する。1リレーの開始失敗でほかの開始をキャンセルしない。

### 7.2 実対象が0件になった場合

`RelayTarget.Single(relayUrl)` は、購読を開く時点でそのURLが有効readリレーに含まれなければ実対象0件になる。設定変更との競合により、事前に取得したリレー一覧だけでは存在を保証できない。

coordinatorは、要求したURLと `FetchCompleted.outcomes` を照合する。outcomesが空の場合は次のように扱う。

- URLが現在のreadリレー集合から削除済みなら、対応する`RelayHistoryState`を削除し、失敗件数には含めない。
- URLが現在もreadリレー集合にあるなら、カーソルを進めず`Stalled(Unavailable)`とする。
- 空outcomesを`EOSE`または`exhausted`として扱わない。
- `InFlight`、セッション参照、前景世代の未完了数は必ず解放する。

購読gatewayは、可能であればセッション作成時の実対象URL集合を返す。現行基盤を維持する場合は、空の`FetchCompleted`を上記規則で明示的に処理する。現在宣言だけされている`RelayUnavailable`シグナルの受信を前提にしない。

### 7.3 前景読み込みの完了

前景の読み込み世代ごとに、開始したリレー集合を保持する。

前景の `isLoadingMore` は次のいずれかで `false` にする。

- 1リレー以上が `EOSE` を返し、受信イベントを表示した後、短い settle 時間が経過した
- 開始した全リレーが `EOSE`、失敗、抑止のいずれかになった
- 前景表示用の絶対タイムアウトに達した

前景表示用タイムアウトは通信セッションのタイムアウトより短くできる。前景表示を終了しても `inFlight` は変更しない。

推奨初期値は次とする。

```text
前景 settle:                 500 ms
前景表示の絶対タイムアウト: 2,500 ms
有限取得タイムアウト:       10,000 ms
```

### 7.4 読み込み可能状態

```kotlin
canAutoLoadMore = relayStates.values.any { relay ->
    !localHistoryLimitReached &&
        !storageFailure &&
        relay.cursors.values.any { !it.exhausted && it.blockedReason == null } &&
        relay.status !is RelayHistoryStatus.Suppressed &&
        relay.status !is RelayHistoryStatus.AwaitingAuth &&
        relay.status !is RelayHistoryStatus.InFlight &&
        (relay.status !is RelayHistoryStatus.Stalled ||
            relay.retryAtMillis?.let { it <= nowMillis } == true)
}

hasStalledRelays = relayStates.values.any { relay ->
    relay.cursors.values.any { !it.exhausted && it.blockedReason == null } &&
        relay.status is RelayHistoryStatus.Stalled
}

canRetryStalledRelays = hasStalledRelays
```

既存の `UiState.canLoadMore` は `canAutoLoadMore` の意味に限定する。再試行期限前の `Stalled` しか残っていない場合は `canLoadMore = false` とし、LazyList末尾から同じ無効な読み込みを繰り返さない。UIには通常の終端表示ではなく、`hasStalledRelays` と `canRetryStalledRelays` を使って部分取得状態と手動再試行を表示する。

## 8. 失敗と再試行

失敗分類は文字列の接頭辞を再解析せず、購読基盤が `SubscriptionSignal.Closed.retry` で通知する `RetryDisposition` を正とする。

| `RetryDisposition` | 履歴状態 | 再開契機 |
|---|---|---|
| `RetryWithBackoff` | `Stalled` | バックオフ期限、明示的再試行、ネットワーク変更 |
| `RetryAfterAuth` | `AwaitingAuth` | NIP-42認証成功 |
| `RetryOnFilterChange` | 原因パーティションを`StructuralRefusal` | 履歴スコープ変更 |
| `DoNotRetry` | 原因パーティションを`StructuralRefusal` | リレー設定または履歴スコープ変更 |

`FetchCompleted(Closed)` は disposition を保持しないため、先に届く `Closed` で確定した分類を維持する。基盤契約として有限取得でも `Closed` を `FetchCompleted` より先に配送することをテストする。契約を維持できない場合は、`RelayOutcome.Closed` に `RetryDisposition` を追加する。

現行の購読基盤にはNIP-42認証成功を通知するAPIがないため、`AwaitingAuth`からの自動復帰には基盤拡張が必要である。gatewayへリレーURL単位の認証状態Flowまたは`RelayAuthenticated(relayUrl, connectionGeneration)`を追加し、同じ接続世代の認証成功だけを再開契機にする。基盤拡張が未実装の段階では`AwaitingAuth`を維持して部分取得を表示し、自動再試行や成功したものとみなす代替処理は行わない。リレー設定または履歴スコープが変わった場合は新しい状態として再評価できる。

`CLOSED`は購読ID全体に対する応答であり、複数パーティションを含むREQでは原因フィルターを特定できない。`RetryOnFilterChange`または`DoNotRetry`を受けたセッションに2つ以上のパーティションが含まれていた場合は、次の分離取得を行う。

1. そのセッションに含まれた全カーソルを変更せず、リレーを一時的な`Parked`へ戻す。
2. 同じ`requestedUntil`と`requestedLimit`を使い、パーティションごとに1件ずつ別の購読IDで有限取得する。
3. `EOSE`を返したパーティションは通常規則で進め、構造的`CLOSED`を返したパーティションだけを`StructuralRefusal`にする。
4. 分離取得も共通arbiterで直列化し、同じリレーへ同時REQを出さない。

これは事前疎通確認ではなく、構造的拒否を受けたページの再取得である。一度分離が必要と判定したリレーでは、その履歴スコープ中は残りの正常パーティションも別購読として継続し、再び拒否パーティションと同じREQへまとめない。進行可能なパーティションが1つも残らない場合に限り、リレー集約状態を`Suppressed`とする。

`TimedOut`、実対象が存在する状態での接続不能、一時切断には指数バックオフとジッターを適用する。

```text
1回目: 5秒
2回目: 15秒
3回目: 60秒
4回目以降: 最大5分
```

- pull-to-refresh、リレー設定変更、ネットワーク変更では、一時失敗のバックオフを解除して再試行できる。
- `AwaitingAuth` は認証成功通知以外では解除せず、UIの手動再試行対象にも含めない。
- 自動再試行は画面の前景ローディングを表示せず、取得できたイベントだけを統合する。

`retryAtMillis` の到来は外部のFlow更新だけに依存しない。coordinatorは最も早い再試行期限に対して1件の `retryJob` を持ち、期限到来時に対象リレーを再評価する。

- より早い期限が追加された場合は `retryJob` を張り直す。
- 期限到来時は、画面がアクティブで同じ履歴スコープに属するリレーだけを再試行する。
- `stopSubscriptions()`、履歴スコープ変更、`resetToLatest()`、`close()` で `retryJob` をキャンセルする。
- 自動再試行が失敗した場合は次のバックオフ期限を設定する。

## 9. イベントマージ

### 9.1 重複排除

表示イベントはイベント ID をキーに1件へ統合する。

履歴ページングの進行判定に必要なため、基盤側でリレー情報を失う重複排除は行わない。有限取得は `SubscriptionSignal.Event.relayUrl` を保持した状態で `FeedController` へ渡し、次の2段階で処理する。

1. 実リレーからのイベントをリレー・フィルター別ページ集計へ必ず記録する
2. 表示用イベント集合へイベント ID で追加する

ローカルストアや別キャッシュから再生したイベントには実リレーのページ応答としての意味がない。`local://`などの合成URLを持つイベントは表示・ストア統合には利用できるが、ページ件数、最古時刻、空ページ判定、境界IDには含めない。有限取得のカーソルを進めるのは、現在の`requestId`が対象とする実リレーから届いたイベントだけとする。

### 9.2 並び順

表示順は次とする。

```text
PresentedFeedEntry.sortTime DESC, displayEventId ASC
```

遅いリレーの復旧や新しいリポスト取得で既存範囲へsourceが追加された場合も、この規則で再配置する。現在表示中の先頭displayイベントIDとオフセットを維持し、遅着イベントによるスクロールジャンプを防ぐ。

### 9.3 表示境界

複数リレー全体の単一カーソルは持たない。表示件数を1ページ30件へ厳密に固定せず、今回正常に受信したイベントをマージして表示する。

これは、停止リレーを待たずに完全な全体順序を確定することが不可能なためである。後から復旧したリレーのイベントが既存イベント間へ挿入されることを許容する。

### 9.4 キャッシュ保持範囲とカーソルの整合

ネットワーク取得カーソル、取得済みイベントを保持するストア、画面へ渡す表示ウィンドウを別の状態として管理する。イベントが表示ウィンドウから外れたことを理由に、ネットワーク取得カーソルを巻き戻してはならない。

```kotlin
data class StoredFeedEvent(
    val event: NostrEvent,
    val relayUrls: Set<String>,
    val filterKeys: Set<FeedFilterKey>,
    val sourceSortTime: Long,
)

interface FeedHistoryStore {
    suspend fun commit(
        scopeFingerprint: String,
        writeId: FeedStoreWriteId,
        events: Collection<StoredFeedEvent>,
    ): FeedStoreCommitResult
    suspend fun snapshot(scopeFingerprint: String): List<StoredFeedEvent>
    suspend fun replaceScope(previousFingerprint: String?, nextFingerprint: String)
}

sealed interface FeedStoreWriteId {
    data class HistoryPage(val requestId: Long) : FeedStoreWriteId
    data class GapFillPage(val requestId: Long) : FeedStoreWriteId
    data class LiveEvent(
        val eventId: String,
        val sourceRelayUrl: String,
    ) : FeedStoreWriteId
}

data class PresentedFeedEntry(
    val displayEvent: NostrEvent,
    val displayEventId: String,
    val sourceEventIds: Set<String>,
    val sortTime: Long,
)

interface FeedPresentationIndex {
    fun upsert(entry: PresentedFeedEntry)
    fun removeSource(sourceEventId: String)
    fun window(
        anchorDisplayEventId: String?,
        direction: FeedWindowDirection,
        limit: Int,
    ): List<PresentedFeedEntry>
    fun hasOlderThan(anchorDisplayEventId: String?): Boolean
    fun clear()
}
```

初期実装はアプリ再起動をまたがないセッション内`FeedHistoryStore`とし、現在の履歴スコープで履歴・ライブから取得した生イベントを、wire上のイベントIDで重複排除して保持する。`sourceSortTime`はその生イベントがフィードへ現れた時刻であり、通常投稿では投稿時刻、kind 6ではリポスト時刻とする。

`FeedHistoryStore`は取得の完全性と再フィルター用の生イベント保持を担当し、画面のanchorや表示ウィンドウを管理しない。表示可否、リプライ除外、ミュート、NGワード、kind 6から元投稿への変換は`FeedController`が行い、その結果を`FeedPresentationIndex`へ反映する。

`FeedPresentationIndex`は表示イベントIDをキーにする。直接取得した投稿と、その投稿を指す1件以上のkind 6が同じ表示イベントになる場合は、`sourceEventIds`を和集合にし、`sortTime`を全取得元の最大値にする。これにより、kind 6のwire IDと表示上の元投稿IDが異なっても、表示anchorを一意に解決できる。リポスト元が未解決の場合はsourceをpendingとして保持し、元投稿の解決後に同じ表示エントリーへ反映する。

UIへは`FeedPresentationIndex`から最大`MAX_TIMELINE_EVENTS`件の表示ウィンドウだけを渡す。スクロール方向に応じて表示イベントIDをanchorとしてウィンドウを切り替え、切り替え前の先頭表示イベントIDとオフセットを維持する。ミュート・NGワードなど表示条件が変わった場合は、ネットワークカーソルを変更せず、現在の`FeedHistoryStore.snapshot()`からpresentation indexを再構築する。

末尾到達時は、ネットワークREQより先にpresentation index内の次の表示ウィンドウを確認する。未表示の古い表示エントリーがある場合はウィンドウだけを移動し、履歴カーソルを変更せずREQも送らない。表示エントリーが不足していて、まだpresentationへ評価していない生イベントがストアにある場合は、それらを先に評価する。ストアにも未評価イベントがなく、ローカル上限・ストレージ障害がなく、進行可能なカーソルがある場合だけ`loadMore()`をネットワーク取得へ委譲する。

1回の前景`loadMore`要求では、ストアcommit後に表示可能な新規エントリーが0件でも、進行可能なカーソルがある限り最大`MAX_AUTO_SKIP_EMPTY_HISTORY_PAGES`ページまで続けて取得する。初期値は現行と同じ5ページとする。5ページでも表示エントリーが増えなければ自動継続を終了し、`requiresExplicitLoadMore = true`を公開する。LazyListが末尾に留まっているだけでは新しい要求を発生させず、ユーザーが末尾から離れて再到達するか、明示的な「さらに読み込む」を操作した場合だけ新しい前景要求を開始する。正常リレーのカーソル進行と`exhausted`判定は、表示件数が0件でも通常どおり保持する。

ストアには`MAX_CACHED_FEED_EVENTS`のソフト上限を設け、初期値を5,000件とする。開始済みページは原子的に全件commitし、ページ途中でイベントを捨てないため、一時的な上限超過を許容する。上限到達後は次の動作とする。

- `localHistoryLimitReached = true` とし、新しい履歴REQを開始しない。
- リレー・フィルター別カーソルは最後にcommit済みのページ位置を維持し、巻き戻さない。
- UIには通信上の全履歴終端と区別できる「この端末で保持できる履歴の上限」状態を表示する。
- UIの「履歴をリセット」操作または`resetToLatest()`で履歴スコープとストアを同時に破棄し、最新から再開できる。通常のpull-to-refreshは表示位置とストアを維持する。
- 上限到達後もライブイベントは受理する。最古の非表示イベントから削除し、削除対象が表示中ならウィンドウが移動するまで一時的な上限超過を許容する。削除してもネットワークカーソルは巻き戻さず、`localHistoryLimitReached`を維持する。

`FeedStoreCommitResult`は`Committed`、`RejectedStaleScope`、`Failed`を区別する。ストアは現在の`scopeFingerprint`と一致するcommitだけを受理し、同じ`FeedStoreWriteId`の再commitを冪等に扱う。同じイベントIDを別リレーまたは別取得種別から受信した場合は既存行を捨てず、`relayUrls`と`filterKeys`を和集合で更新する。`LiveEvent`の冪等キーには取得元リレーURLも含め、2つ目以降の取得元を失わない。通常履歴カーソルの更新は`HistoryPage`の`Committed`後に、coordinator側でも同じスコープと`requestId`が有効であることを再確認してから行う。`GapFillPage`と`LiveEvent`は通常履歴カーソルを変更しない。`RejectedStaleScope`では何も更新せず、`Failed`ではそのページのカーソルを進めず、coordinator全体の`storageFailure = true`で新規REQを停止し、ユーザーへローカル保存エラーを表示する。ストレージ障害をリレー障害として数えない。

将来、再起動をまたぐ永続ストアを導入する場合もネットワークカーソルと保持データを別テーブルで管理し、ページcommitとカーソル更新を同じトランザクションで行う。保持データだけを削除する場合は履歴スコープ全体を無効化して最新から再構築し、一部リレーのカーソルだけを削除境界へ巻き戻さない。

## 10. リレー設定変更

### 10.1 追加

追加されたリレーには、現在の全 `FeedFilterKey` に対応する新しい `RelayHistoryState` を作成する。

- フィードの状態にかかわらず、現在の履歴スコープの `historyFloor` を初期 `requestedUntil` とする。
- 追加リレーを `historyFloor` から取得し、既存リレーが到達している表示範囲までバックグラウンドで追いつかせる。
- 追いつき中のイベントもIDで重複排除して時系列へ統合する。

現在表示中の最古時刻から開始すると、そのリレーだけに存在する `historyFloor` と表示最古時刻の間のイベントを取りこぼすため禁止する。表示位置の変化は、現在表示中の先頭アイテムIDとオフセットを維持して防ぐ。

追いつき開始時に、フィルターごとの到達目標を既存リレーの`requestedUntil`の最小値からスナップショットする。対象フィルターを持つ既存リレーがない場合は`historyFloor`を目標とする。追加リレーの`requestedUntil`が目標以下になった時点、またはそのフィルターが空ページへ到達した時点で追いつき完了とする。開始後に目標を動かさない。

追いつき処理には次の制約を設ける。

- 1回の起動で最大`MAX_CATCH_UP_PAGES_PER_RUN`ページまでとし、初期値は3ページにする。同じリレーでは常に1ページずつ取得する。
- ユーザーの前景`loadMore()`を優先し、前景取得中は追いつき処理を開始しない。
- ページ予算へ達した場合はバックグラウンド処理を中断し、次のアイドル機会に続ける。
- 次のアイドル機会は、前景取得がなく画面がアクティブな状態で1秒以上経過した時点とする。追いつき再開用Jobは1件だけ保持する。
- 画面停止、履歴スコープ変更、対象リレー削除、ローカル履歴上限到達で即時キャンセルする。
- 停止・拒否・認証待ちは通常のリレー状態とバックオフへ合流させる。
- 開始後に既存リレーがさらに過去へ進んでも、今回の到達目標は延長しない。

### 10.2 削除

削除されたリレーの有限取得を閉じ、進行対象から除外する。そのリレーから取得済みのイベントは、同じイベントがほかのリレーに存在しなくても表示から即時削除しない。

リレーを再追加した場合は新規追加として扱う。セッション内カーソルを再利用するかは将来の永続化設計で決める。

## 11. UI状態

`UiState` には必要最小限の集約状態を公開する。

```kotlin
data class FeedRelayStatus(
    val totalCount: Int = 0,
    val inFlightCount: Int = 0,
    val stalledCount: Int = 0,
    val suppressedCount: Int = 0,
    val exhaustedCount: Int = 0,
    val blockedFilterCount: Int = 0,
    val unresolvedBoundaryCount: Int = 0,
    val canAutoLoadMore: Boolean = false,
    val hasStalledRelays: Boolean = false,
    val canRetryStalledRelays: Boolean = false,
    val awaitingAuthCount: Int = 0,
    val noReadableRelays: Boolean = false,
    val localHistoryLimitReached: Boolean = false,
    val storageFailure: Boolean = false,
    val requiresExplicitLoadMore: Boolean = false,
)
```

`totalCount`、`inFlightCount`、`stalledCount`、`suppressedCount`、`awaitingAuthCount`はリレー数、`exhaustedCount`は全パーティションが走査終端に達したリレー数、`blockedFilterCount`は`CoverageGapLimit`または`StructuralRefusal`になったフィルターパーティション数、`unresolvedBoundaryCount`は全リレー・フィルターに残るcoverage gap数を表す。

通常時はリレー別状態を常時表示しない。正常リレーが進行可能な限り、停止リレーの存在だけでエラー画面を出さない。

次の場合に部分取得を表示する。

- 進行可能な正常リレーがなく、`stalledCount > 0`
- 初回読み込みでイベントが0件かつ、1件以上のリレーが失敗した
- ユーザーが明示的にリレー状態を開いた

表示文言例:

```text
取得可能な投稿はここまでです。2件のリレーが応答していません。
[再試行]
```

`stalled` が残っている状態を「すべて読み込み済み」と表示しない。

実対象リレーが0件なら`noReadableRelays`を表示し、履歴終端とは扱わない。`AwaitingAuth`しか残っていない場合は認証待ち、`blockedFilterCount > 0`で進行可能なパーティションがない場合はcoverage gap保持上限またはフィルター拒否、`localHistoryLimitReached`の場合は端末保持上限、`storageFailure`の場合はローカル保存失敗として、それぞれ通信終端や停止リレーと区別する。`unresolvedBoundaryCount > 0`では「一部リレーに未確認の境界があります」と表示できるが、通常の過去読み込み操作は無効化しない。`requiresExplicitLoadMore`の場合は「表示できる投稿が見つかりませんでした。[さらに読み込む]」を表示し、同じ末尾位置からの自動再発火を抑止する。

## 12. FeedController への適用

`FeedController` から次の履歴状態を切り出す。

- `oldestCreatedAt`
- `nextHistoryUntil`
- `activeHistoryUntil`
- `nextHistoryPageSize`
- `activeHistoryPageSize`
- `shouldRetryHistoryPage`
- `historyPageCreatedAtByEventId`
- `historyPageEventIdsByRelay`
- `pendingHistoryRelayUrls`
- `currentHistorySession`
- `loadingMore`

新しい責務境界は次とする。

```text
FeedController
├── ライブ購読
├── 表示判定・リポスト変換
├── FeedPresentationIndex・表示ウィンドウ
├── プロフィール・エンゲージメント
└── RelayHistoryMergeCoordinator
    ├── リレー・フィルター別カーソル
    ├── リレー別有限取得セッション
    ├── ページ内集計
    ├── セッション内 FeedHistoryStore
    ├── リレー別有限取得arbiter
    ├── 復帰時GapFillCursor
    ├── timeout / CLOSED / EOSE 状態遷移
    ├── バックオフ
    └── 集約した履歴状態
```

`RelayHistoryMergeCoordinator` はイベントの表示可否を判定しない。ストアへcommitした生イベントと取得元情報を `FeedController` へ通知し、リプライ、ミュート、NGワード、kind別の表示判定、kind 6から表示対象への変換は従来どおり `FeedController` が行う。`FeedController`は判定結果を`FeedPresentationIndex`へ反映し、表示ウィンドウの更新結果を`UiState.events`へ公開する。

## 13. ライフサイクル

### 13.1 通常停止と再開

- `stopSubscriptions()` はライブ購読と全履歴セッションを閉じる。
- 画面停止や通常のライフサイクル停止で中断した `InFlight` は、失敗回数を増やさず`Idle`に戻す。実際のネットワーク切断通知で終了した場合だけ`Stalled(Disconnected)`とする。
- `startSubscriptions()` は同じ履歴スコープである限り既存のリレー・フィルター別カーソルを維持し、ライブ購読を再開する。
- 10分超の `resetToLatest()` は設計済みの要件どおり、リレー・フィルター別カーソルも破棄して最新ページから開始する。
- フィルターfingerprint変更時は、先に世代を更新して旧commitを無効化し、全履歴セッションと再試行Jobを閉じる。その後、表示ウィンドウを空にして`FeedHistoryStore.replaceScope()`を実行し、新しい`historyFloor`、ストア、カーソルを同じ新スコープで作る。旧フィルターのイベントを新しい表示へ残さない。
- `resetToLatest()`と明示的な履歴リセットでは、セッション内履歴ストア、表示ウィンドウ、全リレー・フィルター別カーソルを同じ世代変更で破棄する。通常のpull-to-refreshでは破棄しない。
- `close()` 後のセッションコールバックは `requestId` と coordinator の closed 状態で破棄する。

### 13.2 復帰時ギャップ補完

5秒超10分以内の復帰で行うギャップ補完は、過去方向の履歴カーソルを変更しない独立した有限取得とする。

- ギャップ範囲は停止時刻と復帰時刻から固定し、`since`と`until`を両方指定する。
- `relayUrl × filterKey`ごとに一時的な`GapFillCursor(since, requestedUntil, requestedLimit)`を作り、固定`since`へ到達するか空ページになるまで過去方向へページングする。1ページで全区間を取得できるとは仮定しない。
- `GapFillCursor`にも6.2と6.3の境界規則を適用する。証明不能な同一秒はギャップ補完専用のcoverage gapとして保持し、通常履歴カーソルへ混ぜない。
- ギャップ補完の`EOSE`、空ページ、タイムアウトを通常履歴の`RelayFilterCursor`にある`requestedUntil`、`reachedUntil`、`exhausted`へ反映しない。
- 受信イベントは通常の`FeedHistoryStore`へID重複排除でcommitし、現在の表示位置を維持して統合する。
- リレー別の有限取得とし、停止リレーがほかのリレーのギャップ補完を妨げない。未完了範囲は`relayUrl × filterKey`ごとに1件の`PendingGap`として保持し、次回の復帰またはネットワーク変更時に同じ境界から再試行する。新しいギャップが既存範囲と離れていても、`since`の最小値と`until`の最大値からなる1区間へ統合する。区間間を余分に再取得することは許容し、未完了範囲を捨てずに保留件数をリレー数×フィルター数へ抑える。
- 同じリレーでは、前景`loadMore()`、通常履歴の再試行、追加リレー追いつき、ギャップ補完の有限取得を同時に実行しない。共通のリレー別arbiterで直列化し、優先順位を「前景`loadMore()`、通常履歴再試行、ギャップ補完、追加リレー追いつき」とする。
- 画面停止、10分超の`resetToLatest()`、履歴スコープ変更、リレー削除でギャップ補完をキャンセルする。ライフサイクル都合のキャンセルは履歴リレーの失敗回数へ加算しない。

## 14. 受け入れ条件

### 14.1 必須テスト

1. 2リレー中1リレーがタイムアウトしても、正常リレーを連続して2ページ以上読み込める。
2. タイムアウトしたリレーの `requestedUntil` と `reachedUntil` が変化しない。
3. 停止リレー復旧後、失敗したページと同じ `until` から再取得する。
4. 復旧イベントを既存イベントとID重複排除し、正しい時系列へ挿入する。
5. 正常リレーのEOSE後に前景インジケーターが消えても、遅いリレーのセッションが継続する。
6. 遅いリレーが通信中でも、正常リレーの次ページを開始できる。
7. 空ページ＋EOSEだけが該当リレー・フィルターを`exhausted`にする。
8. 短い非空ページでは`exhausted`にしない。
9. 同一秒に初期ページ上限を超え、`MAX_HISTORY_PAGE_SIZE`以下のイベントがある場合、limit拡張でIDを取りこぼさない。
10. `CLOSED`、接続失敗、タイムアウトをEOSEとして扱わない。
11. 古い`requestId`のイベントと完了通知が新しいページ状態を変更しない。
12. リレー削除後、そのリレーが進行可能判定や部分取得件数に残らない。
13. 全リレー停止時に無限ローディングせず、部分取得と再試行を表示する。
14. `resetToLatest()`で全リレーのカーソルと保留中再試行を破棄する。
15. 初回取得に失敗したリレーを後から再試行しても、最初に固定した`historyFloor`を使用する。
16. 追加リレーは`historyFloor`から取得し、現在の表示最古時刻より新しい固有イベントも回収する。
17. `EOSE`と後続の`FetchCompleted(Eose)`でカーソルを二重更新しない。
18. `Closed`と後続の`FetchCompleted(Closed)`で失敗回数と再試行予約を二重更新しない。
19. 再試行期限前のstalledリレーだけが残った場合、`canLoadMore`をfalseにして部分取得を表示する。
20. 再試行期限到来時に外部状態変更がなくても自動再試行が起動する。
21. フォロー一覧、取得kind、またはfilterSchemaVersion変更時に古いフィルターのカーソルを再利用しない。
22. 同じリレーで通常投稿フィルターだけが上限へ達しても、コメントフィルターの古い時刻で通常投稿カーソルを進めない。
23. あるフィルターパーティションが空でも、同じリレーの別パーティションが非空なら非空側だけを継続する。
24. 同一秒の短い非空ページでもlimitを拡張し、短いことだけを理由に境界秒を完全取得済みとしない。
25. `MAX_HISTORY_PAGE_SIZE`でも同一秒を抜けられない場合、その秒をcoverage gapとして保持し、完全取得を偽らず古い範囲を継続する。
26. `RelayTarget.Single`の実対象が0件で空outcomesを受信しても、EOSEまたは履歴終端にしない。
27. `RetryAfterAuth`を手動再試行できず、認証成功時だけ再開する。
28. presentation indexの表示ウィンドウ切り替えでネットワークカーソルを変更せず、過去にcommitしたイベントを再REQしない。
29. ストア上限到達後は新規REQを止め、通信終端と異なる状態を表示する。
30. 追加リレーの追いつきがページ予算で中断され、前景取得と画面停止を優先する。
31. 正常ページでは`reachedUntil`に実際の最古時刻を保持し、次の`requestedUntil = pageOldest`として包含境界を再取得する。
32. 複数パーティションを含むREQが構造的`CLOSED`になった場合、分離取得で正常パーティションだけを継続する。
33. 分類不能または複数パーティションに一致するイベントが、ストアとカーソルへ混入しない。
34. ストアcommit失敗後の`FetchCompleted(Eose)`でカーソルを進めず、リレー失敗として数えない。
35. 10分以内のギャップにページ上限を超えるイベントがあっても、通常履歴カーソルを変えず複数ページで補完する。
36. 同じリレーの前景履歴、再試行、ギャップ補完、追いつき取得がarbiterにより同時実行されない。
37. coverage gapが残ったまま通常履歴が空ページへ到達しても、全範囲を完全取得済みと表示しない。
38. coverage gap数が上限へ達した場合、既存の穴を捨てず該当パーティションだけを停止する。
39. ストアに未表示の古いイベントがある場合、末尾到達でネットワークREQを送らず表示ウィンドウだけを移動する。
40. 複数パーティションREQでイベント0件のパーティションを、REQ送信時の`requestedPartitions`に基づいて正しく`exhausted`にする。
41. REQ開始後に履歴スコープが変わった場合、遅れて成功した旧スコープのcommitを拒否し、新しいストアとカーソルを変更しない。
42. `Closed`までに受信した不完全ページのcommitが失敗した場合、`StorageFailed`を維持し、リレー失敗回数を増やさない。
43. 認証成功通知のない`AwaitingAuth`が自動・手動再試行されず、同じ接続世代の認証成功通知でだけ再開する。
44. 同じリレー・フィルターに複数の未完了ギャップが発生しても、未完了範囲を捨てず1件の包含区間へ統合する。
45. 512件を超える有限取得イベントを連続投入しても、全イベント、`EOSE`、`FetchCompleted`が順序どおり欠落なくcollectorへ届く。
46. 有限取得collectorをキャンセルした場合、backpressure中の送信とセッションcloseがデッドロックしない。
47. バッファ上限を超えるキャッシュ再生があっても、有限取得のopenとcollector開始が相互待ちにならない。
48. 同一イベントを複数リレーまたは履歴・ギャップ・ライブからcommitした場合、1イベントへ統合しつつ取得元リレーとフィルターの集合を失わない。
49. kind 6のsourceイベントIDと元投稿のdisplayイベントIDが異なっても、presentation anchorを維持してウィンドウを切り替えられる。
50. ミュート、NGワード、返信除外により5ページ連続で表示追加が0件でも無限に自動REQせず、明示的な追加読み込みを表示する。
51. 表示追加が0件のページでも、正常完了したリレー・フィルターのカーソルが進み、停止リレーのカーソルは進まない。
52. ミュートまたはNGワード変更時に、ネットワークREQとカーソル変更なしで生イベントストアからpresentation indexを再構築する。

### 14.2 非機能条件

- 同時に動作する履歴セッション数は有効なreadリレー数以下とする。重複フィルターの分離取得も同じリレー内で直列化する。
- 同じリレーで同時に動作する履歴ページは1件以下とする。
- すべての有限取得セッションはEOSE、失敗、タイムアウト、画面終了のいずれかで閉じる。
- リレー・フィルターごとのイベントID追跡は上限を設け、フィードの寿命に比例して無制限に増加させない。
- `boundaryEventIds` は現在処理中の境界秒だけを保持し、境界が進んだら置き換える。各`UnresolvedBoundary.observedEventIds`にも`MAX_HISTORY_PAGE_SIZE`の件数上限を設ける。
- 停止リレーの自動再試行はバックオフし、前景スクロール操作ごとに即時再接続を繰り返さない。

## 15. 導入手順

1. `FeedFilterPartition`とイベント分類規則を追加し、通常投稿・リポストとNIP-22コメントが非重複の別キーになることをテストする。
2. 有限取得セッションをcollector接続後にordered producerが開始する損失なし配送へ変更し、512件超のバースト、キャッシュ再生、終端順序、キャンセル時のデッドロックがないことをテストする。
3. 購読gatewayへ実対象URL集合と、NIP-42を実装する場合の接続世代付き認証成功通知を追加する。認証機構をまだ実装しない場合も`AwaitingAuth`を解除しない契約をテストする。
4. 純粋な `RelayFilterCursorReducer` と、固定`historyFloor`を含む単体テストを追加する。
5. 要求時スナップショットと終端通知を冪等化する `RelayPageAccumulator` のテストを追加する。
6. スコープ世代を検証するセッション内`FeedHistoryStore`を追加し、履歴・ギャップ・ライブの冪等commit、旧スコープ拒否、上限処理をテストする。
7. リレー別arbiterと一時`GapFillCursor`を追加し、同時実行防止、保留区間統合、複数ページのギャップ補完をテストする。
8. `RelayHistoryMergeCoordinator` を追加し、Fake gatewayでリレー別並行取得、フィルター別カーソル、構造的拒否の分離、空outcomes、再試行Jobをテストする。
9. `FeedController`へ`FeedPresentationIndex`を追加し、生イベントIDと表示イベントIDの対応、表示条件変更時の再構築、表示0件ページの上限付き継続をテストする。
10. `FeedController` のネットワーク履歴状態をcoordinatorへ移す。
11. `UiState`へ自動読み込み・部分取得・認証待ち・保持上限・明示的追加読み込みの集約状態を追加する。
12. 現在の「未完了リレーだけを次の前景ページとして再試行する」分岐を削除する。
13. 実リレーで停止、遅延、`CLOSED`、認証、再接続、リレー追加を確認する。

移行中もライブ購読とエンゲージメント購読の動作は変更しない。
