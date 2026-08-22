# Nostr 購読基盤 再設計

## 1. 目的

ToriNos の Nostr 購読を、次の性質を持つ基盤へ移行する。

- 同じ購読 ID に対する古い `REQ` と新しい `REQ` の `EOSE` を取り違えない。
- 購読対象リレーやフィルターの変更を差分同期する。
- WebSocket 再接続後に、最新の購読状態だけを復元する。
- `CLOSED`、接続失敗、タイムアウトを `EOSE` と区別する。
- 複数リレー由来の同一イベントを基盤側で重複排除する。
- 購読の開始、更新、終了を画面のライフサイクルに結び付ける。
- NIP-01 の複数フィルター `REQ` を利用し、リレー上の購読数を抑える。

設計方針は、Damus の「購読ストリームと所有者の寿命を一致させる方式」と、Amethyst の「desired state とリレー別 wire state を分離する方式」を組み合わせる。

## 2. 現状の課題

現在の `NostrRepository` は、次の状態だけを保持している。

```kotlin
subscriptionId -> Pair<NostrFilter, RelayTarget>
```

このため、以下を表現できない。

- どのリレーへ、どのフィルターを実際に送信したか
- 送信済み `REQ` が履歴取得中か、`EOSE` 後のライブ状態か
- フィルター更新が送信待ちか
- リレーが購読を拒否したか
- `CLOSED` が回復可能か、恒久的な拒否か
- 対象から外れたリレーへ `CLOSE` を送信済みか

また、`events(subscriptionId)` と `eoseRelays(subscriptionId)` がグローバルな共有バスを別々に監視するため、購読開始・終了と collector の寿命が一致しない。

## 3. 設計原則

### 3.1 desired state と wire state を分離する

- desired state: 呼び出し元が現在求めている購読
- wire state: 各リレー上で現在処理されていると推定する購読

desired state は常に最新値で上書きする。wire state は `REQ`、`EVENT`、`EOSE`、`CLOSED`、接続状態によって遷移させる。

### 3.2 状態更新と送信判断を同じ排他区間で行う

`REQ` を送ると判断した時点で、送信より先に wire state を `SENT` にする。実際の WebSocket 送信は Mutex の外で行う。

これにより、同時に走った更新処理と再接続処理が同じ `REQ` を二重送信することを防ぐ。

### 3.3 EOSE 前は同じ subId の更新 REQ を送らない

wire state が `SENT` または `QUERYING_PAST` の場合、フィルター更新は desired state にだけ保存する。

`EOSE` を受信して `LIVE` へ遷移した後、desired filter と sent filter が異なる場合に限り、最新のフィルターを送る。中間の更新値は送らない。

### 3.4 CLOSED を EOSE として扱わない

`EOSE` は正常な初期履歴完了、`CLOSED` はリレーによる購読終了である。履歴取得の完了待ちでは、どちらも「そのリレーからこれ以上待たない」という settled 状態にできるが、結果種別は保持する。

### 3.5 購読の所有者を明確にする

新 API は `SubscriptionSession` を返す。所有者が `close()` すると購読は必ず desired state から削除され、必要なリレーへ `CLOSE` が送られる。

## 4. 公開 API

### 4.1 フィルターと対象リレー

```kotlin
data class SubscriptionSpec(
    val id: String,
    val filters: List<NostrFilter>,
    val target: RelayTarget,
    val behavior: SubscriptionBehavior,
)

sealed interface SubscriptionBehavior {
    /** EOSE 後もライブイベントを受信する。 */
    data object Live : SubscriptionBehavior

    /** 全対象が settled、または timeout になったら自動 CLOSE する。 */
    data class Fetch(
        val timeoutMillis: Long = 10_000L,
    ) : SubscriptionBehavior
}
```

`filters` は空を禁止する。NIP-01 メッセージは次の形式で生成する。

```json
["REQ", "subscription-id", {"kinds":[1]}, {"kinds":[6]}]
```

### 4.2 セッション

```kotlin
interface SubscriptionSession {
    val id: String

    /** 単一 collector で順序を保って処理する。 */
    val signals: Flow<SubscriptionSignal>

    suspend fun update(
        filters: List<NostrFilter>,
        target: RelayTarget,
    )

    suspend fun close()
}
```

`signals` はセッション専用の buffered `Channel` を `receiveAsFlow()` したものとし、単一 collector を契約とする。画面内で複数の状態へ配布する場合は ViewModel が振り分ける。

```kotlin
sealed interface SubscriptionSignal {
    data class Event(
        val relayUrl: String,
        val event: NostrEvent,
        val isLive: Boolean,
    ) : SubscriptionSignal

    data class Eose(val relayUrl: String) : SubscriptionSignal

    data class Closed(
        val relayUrl: String,
        val reason: String,
        val retry: RetryDisposition,
    ) : SubscriptionSignal

    data class RelayUnavailable(
        val relayUrl: String,
        val reason: String,
    ) : SubscriptionSignal

    data class FetchCompleted(
        val outcomes: Map<String, RelayOutcome>,
        val timedOut: Boolean,
    ) : SubscriptionSignal
}
```

新規入口は次とする。

```kotlin
suspend fun openSubscription(spec: SubscriptionSpec): SubscriptionSession
```

同じ ID がすでに存在する場合は例外にする。既存購読の変更には、そのセッションの `update()` を使用する。これにより、異なる画面が偶然同じ ID を共有することを防ぐ。

## 5. 内部データモデル

```kotlin
private data class DesiredSubscription(
    val session: SubscriptionSessionImpl,
    val filters: List<NostrFilter>,
    val target: RelayTarget,
    val behavior: SubscriptionBehavior,
    val targetUrls: Set<String>,
)

private data class RelaySubscriptionState(
    val phase: RelaySubscriptionPhase = RelaySubscriptionPhase.Idle,
    val sentFilters: List<NostrFilter>? = null,
    val connectionGeneration: Long = 0,
    val refusal: RefusalMemory? = null,
)

private enum class RelaySubscriptionPhase {
    Idle,
    Sent,
    QueryingPast,
    Live,
    Closing,
    Closed,
    Suppressed,
}

private data class SubscriptionRecord(
    val desired: DesiredSubscription,
    val relayStates: MutableMap<String, RelaySubscriptionState>,
    val seenEventIds: BoundedIdSet,
    val fetchTracker: FetchTracker?,
)
```

レジストリは次の形にする。

```kotlin
private val subscriptions = mutableMapOf<String, SubscriptionRecord>()
```

すべての複合操作は `stateMutex` 内で行う。ただし、WebSocket 送信、Flow emit、ログ出力のように再入や待機が起きる操作は Mutex の外で行う。

## 6. 状態遷移

### 6.1 基本遷移

```text
Idle ──REQ送信──> Sent ──EVENT──> QueryingPast ──EOSE──> Live
                         └─────────EOSE──────────> Live

Live ──filter更新──> Sent
Live ──対象解除────> Closing ──CLOSE送信──> Closed

Sent / QueryingPast ──filter更新──> 状態維持
                                    desiredだけ更新
                                    EOSE後に最新REQを送信

任意状態 ──切断──> Idle
Idle ──再接続──> 最新desired filterでREQ送信
```

### 6.2 更新調停

`reconcileSubscriptionLocked(subId)` は、各リレーについて次の順に判断する。

1. desired target に含まれず、sent filter がある場合は `CLOSE`
2. desired target に含まれず、sent filter がなければ状態を削除
3. `Suppressed` で、フィルター形状が同じなら何もしない
4. sent filter がなければ最新フィルターで `REQ`
5. desired filter と sent filter が同じなら何もしない
6. `Sent` または `QueryingPast` なら更新を保留
7. `Live` または `Closed` なら最新フィルターで `REQ`

関数は送信すべき `RelayCommand` のリストを返し、呼び出し元が Mutex 解放後に送信する。

### 6.3 再接続

リレー接続ごとに `connectionGeneration` を増やす。

- 接続開始時に、そのリレーの全 wire state を `Idle` に戻す。
- 接続完了時に、desired target にそのリレーを含む購読だけを再調停する。
- 切断前のキュー済み `REQ` は世代番号が古ければ破棄する。
- 再接続時は必ず最新 desired filter だけを送る。

現行 `NostrRelay` の subId 単位キュー圧縮は維持できるが、キュー要素へ connection generation を追加する。

## 7. CLOSED と再試行

理由文字列の NIP-01 machine-readable prefix を解析する。

```kotlin
enum class RetryDisposition {
    RetryAfterAuth,
    RetryWithBackoff,
    RetryOnFilterChange,
    DoNotRetry,
}
```

| prefix | 方針 |
|---|---|
| `auth-required:` | 認証成功後に再送 |
| `rate-limited:` | 指数バックオフ後に再送 |
| `pow:` | 自動再送しない |
| `restricted:` | 同一フィルターの自動再送を抑止 |
| `unsupported:` | 同一フィルターの自動再送を抑止 |
| `invalid:` | 自動再送しない |
| `blocked:` | 自動再送しない |
| 不明 | 最大3回、指数バックオフ |

同じリレーと同じフィルター形状に対する構造的拒否を記録し、3回で `Suppressed` にする。フィルターが意味的に変更された場合、拒否履歴をリセットする。

初期段階で NIP-42 認証を実装しない場合、`auth-required:` は UI/ログへ通知し、その接続中は再送しない。

## 8. EOSE と有限取得

`Fetch` の開始時に対象リレー集合を snapshot する。各リレーは次のいずれかになる。

```kotlin
sealed interface RelayOutcome {
    data object Eose : RelayOutcome
    data class Closed(val reason: String) : RelayOutcome
    data class Unavailable(val reason: String) : RelayOutcome
    data object TimedOut : RelayOutcome
}
```

完了条件は次のいずれかとする。

- snapshot 内の全リレーが `Eose`、`Closed`、`Unavailable` のどれかになった
- セッションの timeout に達した

完了時は `FetchCompleted` を1回だけ送信してから、対象リレーへ `CLOSE` を送りセッションを終了する。

`CLOSED` を完了数には含めても、正常な EOSE 数には含めない。画面は `outcomes` を見て、完全取得・部分取得・失敗を区別できる。

リレー設定が取得中に変更されても、その取得の snapshot は変更しない。次のページまたは再取得から新設定を使用する。

## 9. 重複排除

各 `SubscriptionRecord` に最大件数付きのイベント ID 集合を持たせる。

```kotlin
private class BoundedIdSet(
    private val capacity: Int = 4_096,
)
```

- 同一セッションで同じイベント ID を複数リレーから受けた場合、最初の1件だけを `Event` として送る。
- リレー到達情報が必要な場合は、別途 `eventId -> relayUrls` を小容量で追跡する。
- 署名検証は現状どおり、重複判定より前に行う。
- replaceable event の新旧判定は購読層では行わず、`ProfileCache` などドメインキャッシュへ委譲する。

## 10. リレー接続の所有

接続すべきリレー集合は以下の和集合とする。

```text
全 desired subscription の targetUrls
＋ publish 中の一時接続
＋ temporary subscription の targetUrls
```

集合から外れたリレーは、必要な `CLOSE` をキューへ積んだ後に切断する。WebSocket切断自体でリレー上の購読は消えるため、CLOSE送信完了を無期限には待たず、短い猶予時間を設ける。

通常購読と temporary 購読は、別レジストリにせず target の種類として統合する。これにより、同じURLへ通常用と一時用のWebSocketが二重接続されることを防ぐ。

```kotlin
sealed interface RelayTarget {
    data object AllEnabled : RelayTarget
    data class Single(val url: String) : RelayTarget
    data class Explicit(val urls: Set<String>) : RelayTarget
}
```

## 11. フィードへの適用

### 11.1 履歴

履歴はページごとに一意な ID を持つ `Fetch` セッションとする。

```kotlin
val session = repository.openSubscription(
    SubscriptionSpec(
        id = nextHistorySubscriptionId(),
        filters = listOf(historyFilter),
        target = relayTarget,
        behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
    ),
)

session.signals.collect { signal ->
    when (signal) {
        is SubscriptionSignal.Event -> appendFeedEvent(signal.event)
        is SubscriptionSignal.FetchCompleted -> finishHistoryPage(signal.outcomes)
        else -> Unit
    }
}
```

ViewModel 側の `expectedEoseCount`、`completedHistoryRelayUrls`、`eoseRelays()` は不要になる。

### 11.2 ライブ

ライブは1つの `Live` セッションを維持する。

- 通常は接続が維持されている限り定期的な `REQ` 再送をしない。
- 再接続は購読基盤が自動処理する。
- 無通信検知が必要なら、購読REQではなくWebSocketのping/pongと最終受信時刻で接続健全性を判定する。
- ギャップ補完は、再接続後に別の短命 `Fetch` セッションで行う。

これにより、現在の60秒周期REQと5分重複窓を通常経路から削除できる。

### 11.3 エンゲージメント

リアクション、返信、リポスト、引用リポストは、1つの subId に4つのフィルターを入れる。

```kotlin
filters = listOf(
    NostrFilter(kinds = listOf(7), eTags = eventIds),
    NostrFilter(kinds = listOf(1), eTags = eventIds),
    NostrFilter(kinds = listOf(6), eTags = eventIds),
    NostrFilter(kinds = listOf(1), qTags = eventIds),
)
```

これにより、リレー上の購読数を4から1へ減らせる。イベント種別の振り分けは `event.kind` とタグで行う。

## 12. ライフサイクル

- ViewModel の表示中にセッションを開く。
- `onCleared()` で必ず `close()` する。
- タブ切替で短時間に戻る画面は、必要に応じて最大30秒の解除猶予を設ける。
- 猶予時間は購読基盤ではなく画面ライフサイクル層で管理する。
- アカウント情報や通知など常時必要な購読には猶予を適用しない。

セッションの `close()` は冪等にする。

## 13. エラーとバックプレッシャー

- セッションの signal channel は有限容量とする。
- EVENT で容量を超えた場合は黙って捨てず、セッションをエラー終了させるか、専用メトリクスを記録する。
- `EOSE`、`CLOSED`、`FetchCompleted` は EVENT と同じキュー順序で配送し、追い越しを禁止する。
- グローバルバスは診断ログ用には残せるが、機能上の配送には使用しない。
- 1つの遅い画面が他の購読を止めないよう、リレー受信ループからセッションchannelへの配送は非ブロッキングとする。

初期値として、セッションごとの容量を512、イベントID保持数を4096とする。実測後に調整する。

## 14. 観測性

最低限、以下を構造化ログへ出す。

- subId、relayUrl、connectionGeneration
- desired filter hash、sent filter hash
- 状態遷移
- REQ/CLOSE送信理由
- EOSEまでの時間
- CLOSED prefixと再試行方針
- 再接続回数
- 重複イベント件数
- Fetchのリレー別結果とtimeout

フィルター全文や公開鍵一覧は通常ログへ出さず、件数とhashだけにする。

## 15. テスト設計

### 15.1 状態機械の単体テスト

1. 初回 desired 登録で各対象リレーへ1回だけREQを生成する
2. `SENT` 中の更新でREQを生成しない
3. 更新保留中のEOSEで最新フィルターだけを送る
4. 複数回更新しても中間フィルターを送らない
5. targetから外れたリレーへCLOSEを生成する
6. targetへ追加されたリレーへREQを生成する
7. 再接続で最新desired filterだけを再送する
8. 古いconnection generationのキューを送らない
9. closeとreconnectが競合しても購読を復活させない
10. 同一フィルターの構造的CLOSEDが3回続くと抑止する
11. フィルター変更後は抑止を解除する

### 15.2 セッションテスト

1. 複数リレーの同一EVENTを1回だけ配送する
2. EVENTの後にEOSEを順序どおり配送する
3. CLOSEDをEOSEとして配送しない
4. 全リレーsettledでFetchCompletedを1回だけ配送する
5. timeoutで未完了リレーをTimedOutにする
6. closeを複数回呼んでもCLOSEは最大1回にする
7. collector終了後にレジストリとrelay stateが残らない

### 15.3 統合テスト

fake relayを使い、次のシーケンスを再現する。

```text
REQ(old)
update(new)
EVENT(old)
EOSE(old)
REQ(new)
EVENT(new)
EOSE(new)
```

さらに、切断、再接続、CLOSED、リレー設定変更を各段階へ挿入する。

## 16. 移行手順

### Phase 1: プロトコルと状態機械

- `buildReqMessage()` を複数フィルター対応にする。
- pure Kotlin の `SubscriptionStateMachine` を追加する。
- fake relayを使わない単体テストを先に作る。

### Phase 2: Repositoryへの統合

- `SubscriptionSession` と専用signal channelを追加する。
- 再接続処理を状態機械経由にする。
- 既存 `subscribe/events/eose/close` API は互換アダプターとして残す。

### Phase 3: フィード移行

- 履歴購読を `Fetch` セッションへ移行する。
- ライブ購読を `Live` セッションへ移行する。
- 60秒周期REQを削除し、再接続時ギャップ取得へ置き換える。
- エンゲージメントを複数フィルター1購読へ統合する。

### Phase 4: 他画面の移行

- プロフィール、チャンネル、ライブ、記事、検索の順に移行する。
- 全呼び出し元移行後、グローバルbusと旧APIを削除する。
- temporary relayレジストリを通常レジストリへ統合する。

### Phase 5: EOSEカーソル

- 必要性を計測した上で、リレー別の最終EOSE時刻を保存する。
- 新しいキーやフィルター条件が追加された場合は、古いカーソルを無条件に流用しない。
- ローカル永続DB導入時は、Damus同様にローカル結果とネットワーク結果を統合する。

## 17. 完了条件

- 同一 `(subId, relay)` で未EOSEのREQが2本同時に存在しない。
- リレー対象変更時に、外れたリレーの購読が残らない。
- 再接続後に削除済み購読が復活しない。
- 恒久的なCLOSEDに対して無限再送しない。
- 履歴完了が設定リレー数ではなく、開始時snapshotのリレー別結果で判定される。
- 複数リレーの同一イベントが画面へ重複配送されない。
- 画面終了後に購読レジストリが増え続けない。
- フィードの初期表示、ページング、再接続ギャップ補完が既存動作を維持する。

## 18. 今回は対象外

- NIP-77 Negentropy の実装
- nostrdb相当のローカル永続DB導入
- リレーごとの完全なOutboxモデル
- NIP-42認証UI
- 配信リレーの自動品質評価と動的選択

これらを後から追加できるよう、購読状態とリレー接続状態を分離しておく。
