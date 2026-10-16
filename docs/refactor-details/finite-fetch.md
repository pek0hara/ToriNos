# R1: 有限取得・同期位置の詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

FiniteEventFetcher / FiniteRangeFetcherに問い合わせ結果、受信元、終了理由、リレー別継続を集約した。記事、通知、チャンネルをSession経由へ移行し、部分失敗を成功扱いにしない。通知は初回取得とLiveの所有を分離し、フォロワー基準はrangeComplete時だけ保存する。Session終了はインスタンス一致で保護する。

実装上の判断・制約: 記事の一覧は包含untilと境界秒の再取得枠を用い、進捗を確認できないときはエラーで停止する。チャンネルの既存履歴カーソル構造は維持し、不完全ページでカーソルを確定しない方式とした。リレー別カーソルの全面置換、通知初回基準の画面内再試行UIは未導入。

検証対象: `FiniteEventFetcherTest / FiniteRangeFetcherTest / ArticlePaginationTest / NotificationSyncCoordinatorTest / ChannelListFetchTest`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 現状と変更範囲

[`NostrRepository.eose()`](../../composeApp/src/commonMain/kotlin/com/nostr/torinos/network/NostrRepository.kt) はEOSEとCLOSEDを同じ通知へ変換する。記事の `fetchArticleEvents()` と通知の `syncOnce()` は最初の通知を待つ。チャンネルの `fetchChannelEvents()` は一部リレーのEOSE後の打ち切りを `true` で返し、`ChannelHistoryPage.complete` へ渡す。

これらを `SubscriptionSession` ベースに移し、「表示可能」「問い合わせ終了」「ページ受信成功」「指定期間を取得済み」を分ける。既存の `RelayFetchSummary` は問い合わせ結果の分類に使い、期間の取得済み判定まで担わせない。

## 2. ファイルと責務

| 追加・変更予定 | 責務 |
| --- | --- |
| `network/FiniteEventFetcher.kt` | Sessionを開く、信号を集める、待ち打ち切り、終了時のclose |
| `network/FiniteFetchResult.kt` | リレー別応答、受信元、終了理由、ページ判定の材料 |
| `network/SubscriptionSession.kt` | Fetch開始時に確定した対象リレーを読み取る契約の追加 |
| `ui/article/ArticleEventSource.kt` | kind 30023・引用IDの問い合わせと記事の版選択への入力 |
| `ui/notification/NotificationSyncCoordinator.kt` | 同期期間、初回同期とLiveの接続、同期位置の更新条件 |
| `ui/channel/ChannelListFetch.kt` / `ChannelHistory.kt` | Booleanの結果を置換し、未取得リレーの継続位置を保持 |

## 3. 結果型

```kotlin
internal enum class FetchStopReason {
    AllRelaysFinished, SettleExpired, DeadlineExpired,
    EventBudgetReached, RequestedIdsResolved, NoTargets, UnexpectedEnd,
}
internal data class RelayPageObservation(
    val outcome: RelayOutcome,
    val receivedIds: Set<String>,
    val oldestCreatedAt: Long?,
    val limitMayHaveBeenReached: Boolean,
)
internal data class FiniteFetchResult(
    val expectedRelays: Set<String>,
    val eventsById: Map<String, NostrEvent>,
    val sourceRelays: Map<String, Set<String>>,
    val relays: Map<String, RelayPageObservation>,
    val stopReason: FetchStopReason,
)
```

`SubscriptionSession` にFetch用の不変な `expectedRelayUrls` を追加する。`openSubscription()` がレコードを登録する排他区間で対象を確定し、Sessionへ渡す。呼び出し側が `targetRelayUrls()` を別途読んでから開始すると設定変更と競合するため、その値を完了判定に使わない。テスト用Sessionもこの契約に対応させる。

キャンセルは結果型へ変換せず送出する。対象0件は `NoTargets` とし、空の成功にはしない。信号が完了通知なしに終わった場合は `UnexpectedEnd` とし、不明なリレーは未完了にする。既存の `TimedOut` は未応答結果の分類に再利用し、待ち打ち切りの理由は `stopReason` に残す。

要求目的も `Ids(requestedIds)` と `Range(filters)` で区別する。指定IDがすべて解決した早期終了は `RequestedIdsResolved` とし、ID要求の充足だけを成立させる。未応答リレーのoutcomeをEOSEへ変えず、期間のcoverageや同期位置の根拠へ転用しない。記事アドレスの「最新版」はID要求ではなく置換候補の範囲取得として扱う。

ネットワーク由来の取得では `replayCachedEvents = false`、`deduplicateEvents = false` にする。表示用の重複排除はFetcherがイベントIDで行い、観測元は集合へ追加する。ページ件数はリレー別の一意IDを使い、別リレーの重複を混ぜない。キャッシュの表示は取得前の別経路で行う。

複数フィルターのREQは応答から一致したフィルターを特定できない。上限判定が必要な範囲取得は原則1フィルターに分け、NIP-11と現在の要求件数から上限到達の可能性を判定する。上限が不明な短い応答を、それだけで期間の終端と断定しない。既存フィードの境界処理を確認し、必要なページ単位の判定関数を共用する。

## 4. 取得フロー

1. Fetch用Specを構築し、一意な購読IDで開く。戻る前の即時応答は既存の有限取得キューに保持される。
2. Eventは検証後にイベント集合と受信元へ追加する。範囲・ページの観測件数は、UIのフィルターで非表示になる前に記録する。
3. EOSEでそのリレーのページ受信を記録する。画面の途中表示コールバックは渡してよいが、コールバック内で重いプロフィール取得や同期位置保存を行わない。
4. 待ち打ち切りを使う呼び出し元だけタイマーを開始する。チャンネルは現行どおり最後のEOSEから待ち直す。ジャーナルの最初のEOSEからの待ちと混用しない。
5. タイマー終了では理由を記録し `finishPendingRelays()` を呼ぶ。通常終了は最後の `FetchCompleted` まで信号を読む。イベント予算に達した場合は受理集合をそれ以上増やさず、未受理分をcoverageの根拠にしない。安全期限を超えてキューを無制限に排出し続けない。
6. `finally` の `NonCancellable` 区間でタイマーとSessionを閉じる。独自の安全期限に当たった場合も、欠けた応答を成功へ補わない。

全応答とタイマーが競合した場合、先に確定した終了要求を保持する。ただし確定結果で全リレーEOSEだった場合は、実際の成功集合を失敗扱いに変えない。終了処理とcloseは冪等にする。

`finishPendingFetchRelays(session)` もcloseと同じく、排他区間で登録レコードのSession同一性を照合する。現行はsessionからIDだけを取り出して `completeFetch(id)` へ渡すため、終了済みSessionの遅いタイマーが同じIDの新Sessionを完了させないよう修正する。タイマー、明示打ち切り、通常完了の各経路で所有者を確認する。

## 5. 呼び出し元ごとの適用

| 呼び出し元 | 表示 | 完了・同期位置 |
| --- | --- | --- |
| 記事アドレス取得 | キャッシュ・部分結果を表示し、後着の新版を既存の置換規則で選ぶ | 全体失敗を「記事なし」として確定しない。空結果でも失敗があれば再試行可能にする |
| 引用ID取得 | 解決したIDから反映 | 指定IDすべて解決なら早期終了可。未解決が残れば結果の区別を保つ。単発Fetchは閉じる |
| 通知の初回同期 | 届いた通知は先に表示し、重複排除して保存 | 必要な全リレー・全ページの範囲が確定したときだけ保存位置を進める |
| チャンネル最新履歴 | 一部成功でも本文を表示 | 全リレー成功と部分表示を分け、未取得リレーと範囲を残す |

通知は開始時刻 `until = syncStartedAt` を固定する。保存済み位置から重複期間を含めて取得し、since〜untilを取り終えた場合だけ `followSyncedAt = until` を保存する。当面は既存の単一時刻を維持し、リレー別の永続キー追加はしない。初回の基準位置がない場合は、既存の初回取得仕様を維持し、全履歴の取得済みを意味する位置は新設しない。

初回のフォロワー基準集合も同期位置と同じ確定条件で扱う。現行の `hasStoredFollowerList` は同期失敗時でも保存される経路があるため、`Missing / Building / Ready` の状態へ分ける。Building中の候補集合はメモリに残し、部分集合を「初回構築済み」として保存しない。基準の対象範囲を必要なリレーから取得できた時点で既存のknown-followersキーへ保存し、Readyへ移る。Ready前のイベントは初回基準構築と新規フォロー通知を区別し、不完全な基準との差分で大量の新規通知を生成しない。既存保存済み集合は互換読み込みするが、その存在だけで未確認の期間を取得済みにはしない。

上限到達・同秒境界・再試行予算到達では位置を維持する。継続が可能ならリレーごとに同秒の既読IDを保持して要求件数を増やす。取得不能な境界を時刻のマイナス1で飛ばさない。通知保存の失敗時も位置を進めない。データ保存→位置保存の順とし、位置保存の失敗は重複取得として回復する。

呼び出し元は `maxPages`、`maxAcceptedEvents`、`maxElapsedMillis`、同秒の要求拡大回数を有限な予算として渡す。同一カーソル・同一ID集合の応答が続いた場合は進捗なしとし、無限に取り直さない。上限不明の短い応答を取得済みと認められない場合も、予算で `Incomplete` として止め、既存表示と未取得範囲を残す。予算の値は既存の画面別制限から設定し、本移行で取得量を増やさない。

通知のLive移行はFetchを開く前にLiveを開始し、同期開始時刻から重複期間を含めて購読する。FetchとLiveの重複を既存の通知IDで排除する。画面がLive監視を要求しない場合は、初回同期後に両方を閉じる。後日Liveを開始するときも保存位置との間を補完し、閉じていた時間を飛ばさない。

初回同期のFetchと表示中のLiveは別の購読ID・所有slotにする。初回処理はFetchを常に所有し、重複期間を受けるLiveには `StartupBridge`、画面表示には `Visible` の利用要求を付ける。Liveは要求が1つでもある間だけ1本維持する。初回同期のfinallyはStartupBridgeと自分のFetchだけを解放し、画面が所有するLiveを閉じない。反対に画面を閉じても、継続中の初回Fetchを中断しない。再表示時の補完も同期コーディネーターへ集約する。

チャンネルは `ChannelHistoryPage.complete` を、結果とリレー別継続状態に置換する。部分成功で表示しても、失敗リレーのカーソルを成功リレーの最古時刻へ進めない。`latestConfirmed` は対象集合の最新ページが成功した場合だけ立てる。追加取得の終端判定はページ上限と同秒境界を含む別判定にし、EOSE単体では終端にしない。リレー集合変更時は要求世代を更新し、古い結果を新しい集合の完了に使わない。

## 6. 移行手順

1. 結果型とFetcherを追加し、既存利用先を変えず偽Sessionで検証する。
2. 記事の単発取得を移し、旧ヘルパーの呼び出しがなくなったことを確認する。
3. 引用を有限取得へ移す。継続監視が必要な用途がある場合は別のLive APIとして残す。
4. 通知同期を抽出し、位置更新の条件と保存順を変更する。
5. チャンネルのページ型・履歴状態・表示を同じ変更単位で移す。Booleanだけを新しい結果の `anySucceeded` へ置換しない。
6. 新経路で旧 `eose().first()` を使っていないことを検索で確認する。

`fetchChannelEvents()` は `ChannelListViewModel.fetch()` からも共有される。型を変える段階では、一覧の初回取得・活動取得・未読補完の各呼び出しも同時に更新する。一覧でページ継続が不要な用途は、結果から「その用途に必要な応答が成功したか」を明示して判定する。互換Booleanを残す場合も `anySucceeded` を全取得の意味で返さず、利用先と削除段階を限定する。

## 7. 必須テスト

`FiniteEventFetcherTest`、`NotificationSyncCoordinatorTest` を追加し、既存の記事・チャンネルテストを拡張する。

| ケース | 期待結果 |
| --- | --- |
| 早いリレー0件、遅いリレー1件 | 遅い結果を待つか未完了として保持し、空の全成功にしない |
| CLOSED / 未接続 / 時間切れ | 結果と再試行方針を保持。同期位置は進めない |
| 全EOSEだが要求上限・同秒境界に到達 | ページ応答成功と期間の取得済みを区別 |
| 対象0件 / 完了通知なしの終了 | 成功にしない |
| 同一IDを2リレーから受信 | 表示1件、受信元2件、各リレーの観測は1件 |
| 即時完了 / closeとの競合 / cancel | 先着イベントを保持し、購読枠を解放 |
| 通知保存・位置保存の失敗 | 欠落せず再取得可能。重複は既存規則で排除 |
| 部分取得後のリレー再試行 | 失敗リレーの未取得範囲を取得できる |

関連: [`relay-fetch-failure-policy-design.md`](../relay-fetch-failure-policy-design.md)、[`subscription-architecture-design.md`](../subscription-architecture-design.md)。
