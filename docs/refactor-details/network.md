# R4: 通信管理・信号配信の詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

SubscriptionRegistry、RelayConnectionPool、RelayPublishCoordinator、SubscriptionSessionImplへ責務を抽出した。Repositoryの単一mutexで登録・購読枠・接続世代を調停する。SessionSignalMailboxは満杯・handler失敗をNeedsResyncとして保持し、collectRecoveringが有限取得と反映barrier後に世代・revisionを照合してackする。

実装上の判断・制約: 既存互換APIは他の利用箇所のため残している。補完は有限の予算内で行い、上限や同秒境界で完全性を確認できなければNeedsResyncを維持する。未解消状態はStateFlowで公開するが、全画面共通の警告UIは未導入。

検証対象: `SessionSignalMailboxTest / RelayCoordinationTest / FiniteRangeFetcherTest`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 現状と分離方針

`NostrRepository` は接続、購読レコード、枠、再接続、取得終了、公開送信、キャッシュ、旧APIを所有する。`SubscriptionStateMachine`、`RelayOutbox`、`RelaySubscriptionSlots` はすでに存在するため、これらの判断を作り直さない。

Sessionのライブ信号は512件のChannelへ `trySend()` し、失敗時はログだけを出す。有限取得はlosslessキューを使う。先にライブ欠落の契約を追加し、責務の移動を後から行う。

## 2. 新しい構成

| 追加予定 | 所有する状態 | 外部との境界 |
| --- | --- | --- |
| `RelayConnectionPool` | 通常・一時接続handle、接続世代、参照数 | 接続と切断、世代付きメッセージの通知 |
| `SubscriptionRegistry` | 購読レコード、リレー別状態、枠、再試行、Fetchの結果 | 不変な送信・完了命令を生成 |
| `SessionSignalMailbox` | Sessionのキュー、配送欠落の状態、終了状態 | 非待機の信号投入、単一collector向けFlow |
| `RelayPublishCoordinator` | event ID×relayの受理待ち、期限、配送Job | 最初の成功と最終結果を別に返す |
| `EventObservationSink` | 新たなイベント状態は持たない | 既存ストア・relay hintへの投入を1か所から呼ぶ |
| `NostrRepository` | 上記の構成と旧API互換 | 既存呼び出し元の窓口 |

最初の抽出ではRegistryとPoolを呼ぶ調停部分が現在の `stateMutex` を1つだけ所有する。両クラスへ独立したMutexを追加して入れ子にしない。状態変更はこの排他区間、ネットワークI/O・close・利用者コールバック・待機は排他の外へ出す。より細かなロック分割は本移行に含めない。

Sessionのupdate / close / finishは、小さい `SessionController` インターフェースへ委譲する。Session実装からobjectへの直接参照をなくし、テストでは偽Controllerを使う。

## 3. ライブ信号欠落の契約

```kotlin
internal data class DeliveryGap(
    val targetGeneration: Long,
    val revision: Long,
    val relayUrls: Set<String>,
    val reason: String,
)
internal sealed interface DeliveryState {
    data object Healthy : DeliveryState
    data class NeedsResync(val gap: DeliveryGap) : DeliveryState
    data object Closed : DeliveryState
}
```

Sessionに `deliveryState: StateFlow<DeliveryState>` と、`acknowledgeResync(targetGeneration, revision, barrier)` を追加する。この状態はイベントキューと独立して保持するため、満杯のChannelに復旧通知を送れなくても状態を失わない。StateFlowは複数の欠落をまとめてよいが、未復旧リレーの和集合と最新revisionは保持する。

`emit()` がキュー満杯で失敗した場合、Event、EOSE、Closed、Resumed、RelayUnavailableのどれでも対象リレーをgapへ加える。理由の文字列は最後のものなどの上限つき情報とし、全エラー履歴を無制限に蓄積しない。close済みキューへの遅延投入は通常の終了として扱い、新しいgapを作らない。有限取得の配信方式はこの変更で変えない。

mailbox内の同期的なロックで、キューへの投入結果・gap revision・closeを直列化する。ロック内でsuspendしない。`acknowledgeResync()` は、まだ同じrevisionならHealthyへ戻し、復旧中に次の欠落があれば解除しない。

gapにはSessionの `targetGeneration` も持たせる。フィルター・対象更新ごとに世代を進め、旧世代の復旧結果で新世代をackできないようにする。不要になった旧範囲は破棄してよいが、新世代に必要な初期取得の完了を確認するまではHealthyへ戻さない。Sessionのcloseは復旧完了とは区別する。

さらに信号の「キューへ入った」と「利用者が反映した」を区別する。mailbox内部の各信号に単調増加の配送sequenceを付け、`consumeSignals(handler)` がhandlerの正常終了後に処理済みsequenceを記録する。Live利用者はこの単一collectorのヘルパーを使い、handlerは状態反映前に非同期Jobへ丸投げしない。復旧開始時の投入済みsequenceをbarrierとして記録し、そこまでの先着信号の反映と有限補完の両方が完了してからackする。キューに旧信号を残したまま復旧完了を宣言しない。handler失敗も欠落状態へ変換し、処理済み扱いにしない。

処理済み位置は単なる最後の成功sequenceではなく、barrier以下に未反映の受理済み信号がないことを表す。handler失敗でキューから取り出した信号は未解決として記録し、後続の成功だけで消さない。有限補完でその範囲を回復した時点でのみ解決済みへ移す。これにより、処理失敗を忘れてackすることと、すでにキューに存在しない信号を待ち続けることの両方を防ぐ。

## 4. 復旧フロー

1. 画面側のSession利用者は、signalsに加えてdeliveryStateを1本監視する。
2. NeedsResyncを受けたら同じSessionにつき1つの復旧Jobへ集約する。画面の通常更新と重複するときは既存の取得調停器に依頼する。
3. 閲覧中の対象・フィルター・リレー集合を固定し、最後に取得成功した位置から有限取得で補う。復旧中もLiveを維持し、重複はIDで排除する。
4. gapに含まれる必要なリレーの範囲と配送barrierを反映し終えた場合だけ、同じtargetGeneration・revisionをackする。失敗・上限到達・中断ではgapを維持する。
5. 復旧中に別のgapやフィルター変更があれば、古い結果を新しい対象の成功として使わず、最新の要求で再調停する。

イベントの `createdAt` だけから欠落区間の開始を作らない。最後に処理できたイベント時刻には未来時刻・順序の偏りがありうるため、利用者が保持する成功済み範囲を使う。成功位置がない場合は、現在画面の有限な表示対象を取り直す。予算で取り切れなければ不完全な状態を表示して再試行可能にする。

EOSEやCLOSEDが欠落した場合も、件数だけの再集計で済ませず購読状態を確認する。終端的な拒否なら停止理由を表示し、無限再接続しない。R1の `RelayFetchVerdict` と `RelayRetryPolicy` を利用する。

Sessionの既存利用者は、R4の導入時にすべて棚卸しする。Feedは既存のgap補完、Threadは対象投稿の再取得、その他は現在の可視範囲の再取得へ接続する。状態を公開しただけで復旧しない利用者を残して完了としない。旧bus経路はこのmailboxの保証対象ではなく、旧APIからの移行状況を別に管理する。

## 5. 接続・購読の境界

Registryは購読の作成順・対象・接続世代・フィルター世代から送信命令を作る。命令に接続世代とレコードの世代を持たせ、実行時に一致しなければ破棄する。再接続では現在のレコードから再調停し、破棄された古いREQを再利用しない。

closeはSessionの同一性を確認してレコードを外し、枠を解放し、待機中を作成順に調停する。IDだけを条件にして、同じIDを再利用した別Sessionを閉じない。一時接続は利用者の参照がなくなってから閉じ、通常の設定済み接続を巻き込まない。

イベント観測は `EventObservationSink` を通す。通常接続・一時接続・自分の公開成功が既存ストアに投入する契約を維持し、UIが受け取れたかどうかとは分ける。同じイベントの再観測は冪等でなければならない。

## 6. 公開送信の境界

公開送信は `network/PublishTicket`（R6）へ接続する。最初の受理でUIを進めた後も、残りのリレー結果を同じticketへ反映する。受理済みと書き込み完了を区別する現在の規則を保つ。ticketと開始済み配送JobはRepositoryのscope、署名・公開操作・ticket監視はセッションのscopeで所有し、画面の破棄後に画面オブジェクトを呼ばない。

アカウント切り替え前に配送開始した署名済みイベントは、現在の「最初の成功後も残りへ配送する」契約を維持し、切り替え後も有限な配送期限まで処理する。旧セッション終了後に新しい署名・retryを開始しない。セッションの監視を解除しても開始済みticketをキャンセルしたことにはせず、未回答を成功とも推定しない。ticketは最終結果が確定し利用者が解放した後に破棄する。

## 7. 移行順と必須テスト

1. mailboxの欠落状態・ack・closeを追加する。
2. Live Sessionの全利用者へ復旧監視を接続する。
3. SessionControllerを注入し、Session実装を別ファイルへ移す。
4. publish、Pool、Registryを1つずつ抽出する。各抽出で旧APIは同じ窓口を使う。
5. 使われなくなった旧APIだけを削除する。検索で利用箇所を確認する。

| ケース | 必須の結果 |
| --- | --- |
| 512件を超える遅いLive利用者 | gapが状態として残り、補完後にだけ解除 |
| gap通知自体が満杯時に発生 | 独立したStateFlowから復旧できる |
| 復旧中に再び満杯 | 古いackで新しいgapを消さない |
| closeとemitの競合 | 終了後に再同期を開始しない |
| 古い接続のコマンド | REQ/CLOSEを現在世代へ誤送信しない |
| 枠待ち・再接続・NOTICE拒否 | 枠を超えず、解放後に待機を再開 |
| 一時接続の共有 | 最後の参照まで維持し、その後終了 |
| 最初の受理後の遅い結果 | 同じ公開ticketへ反映し、別画面へ古いcallbackを送らない |

関連: [`subscription-architecture-design.md`](../subscription-architecture-design.md)、[R1](./finite-fetch.md)、[R6](./channel-publishing.md)。
