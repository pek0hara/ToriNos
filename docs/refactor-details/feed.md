# R5: フィード状態更新の詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

FeedTimelineModelが保持データ、FeedWindowPolicyが表示範囲、FeedProjectionPipelineがキャッシュとプロフィール参照、FeedProjectorが純粋な状態組み立て、FeedStatePublisherが即時・150ms間引き公開を担う。Controllerはスナップショットを渡し、最新revisionの再確認を維持する。

実装上の判断・制約: timeline/decoration別のrevision最適化は追加せず、既存の一貫性確認を維持した。性能改善量は未計測。表示・保持上限を変更していない。

検証対象: `FeedWindowPolicyTestおよび既存FeedController・履歴・保持範囲テスト`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 維持する境界

`RelayFeedHistoryCoordinator`、`RelayFetchArbiter`、`EngagementFetcher`、`FeedHistoryStore`、`HeldFeedEvents` は再利用する。履歴の受信と確定、ライブ受信、表示窓の移動、集計の投影を分ける。800件の表示上限と、未公開ページ・表示外イベントに付随する情報を維持する規則は変更しない。

現行 `updateEvents()` のMutex、バックグラウンド計算、revision検査を保ったまま抽出する。初回から全状態を新しいReducerへ移す変更にしない。

## 2. ファイルと所有する状態

| 追加予定 | 所有・入力 | 責務 |
| --- | --- | --- |
| `FeedTimelineModel` | raw / canonicalイベント、並び時刻、確定待ちID | 投稿の採用・削除・確定・保持 |
| `FeedWindowPolicy` | anchor、公開境界、上限を入力 | 可視イベントと前後の保持済み有無を返す純粋関数 |
| `FeedProjectionInput` | 不変スナップショット | 表示計算が参照する全入力 |
| `FeedProjector` | input、解析済み本文、プロフィール | 表示に必要なフィールドだけを返す純粋関数 |
| `FeedStatePublisher` | 最新UiState、公開待ちJob | 既存の即時・遅延公開を管理 |
| `FeedController` | 上記部品とJob | 外部の取得結果・操作をモデルへ渡す調停 |

マッパーとメンション解析のキャッシュはControllerが所有する計算パイプライン内に残す。純粋なProjectorから `ProfileRepository` やキャッシュの可変フィールドを直接読まない。

## 3. スナップショットと計算結果

```kotlin
internal data class FeedProjectionToken(
    val sessionId: String?,
    val subscriptionGeneration: Int,
    val revision: Long,
)
internal data class FeedWindowProjection(
    val token: FeedProjectionToken,
    val visibleEvents: List<NostrEvent>,
    val hasOlderStoredEvents: Boolean,
    val hasNewerStoredEvents: Boolean,
    val requiredPubkeys: Set<String>,
)
```

第1段階は現在のUiStateを含むスナップショットで抽出し、現在と同じ全revision検査を行う。抽出後、入力をtimeline、window、引用・返信、プロフィール、集計へ分ける。

最終的なProjectorはUiState全体を返さず、タイムラインと表示窓の差分を返す。commitは最新UiStateのプロフィール・集計・エラー・送信中状態を保ち、計算の対象フィールドだけを更新する。引用など投影に使う入力はtokenの整合性検査対象に含める。

## 4. 更新フロー

1. UI用ディスパッチャでTimelineModelを更新しrevisionを進める。履歴ページは受信済みIDを記録し、確定までは可視集合に含めない。
2. 計算Mutexの中で保持イベント、並び時刻、window、文脈をコピーする。バックグラウンドへ可変Mapの参照を渡さない。
3. window候補と必要なpubkeyを純粋計算する。`ProfileRepository.getCached(requiredPubkeys)` はパイプライン側で読み、inputへ明示する。
4. `FeedItemMapper` と `MentionedPubkeysCache` は同じMutex内で直列に実行する。キャッシュ上限とLRU順を維持する。
5. 同じsession・購読世代・対象revisionなら最新状態へcommitする。不一致なら最新入力で再計算する。
6. 公開は既存の即時・間引き規則に従う。表示位置を勝手に先頭へ戻さない。

プロフィール・集計をwindow計算から完全に外した後でのみ、revisionをtimeline / decorationに分ける。旧コードの全revision検査を先に緩めない。close時は計算結果のcommitを拒否し、公開Jobと購読を止める。

## 5. 保持と状態の不変条件

- 保存・受信・公開は別状態。受信したページを確定前に表示したり、未取得範囲を取得済みにしない。
- ミュート・削除・作者変更後の計算が、古い結果で投稿を復活させない。
- 表示窓外でも追跡中・確定待ちの投稿の集計と引用を捨てない。
- 初期表示の公開条件はController側で1回決定し、UIの透明度や件数から完了を逆算しない。
- ライブ補完はリレー別の成功済み範囲を使う。表示中の最新投稿の時刻を全リレー共通の位置にしない。
- プロフィール・集計だけの更新でアンカーや新着件数を初期化しない。

## 6. 移行手順

1. `computeUpdatedFeedState()` の外部参照を引数へ出し、既存と同じ結果を返す関数として抽出。
2. windowポリシーを抽出し、境界・同時刻・アンカー不在をテスト。
3. TimelineModelを抽出。保持集合と確定待ちの更新を移す。
4. Projectorの戻り値を部分フィールドへ変え、最新状態へcommitする。
5. Publisherを抽出し、最後にrevision分割と差分最適化の必要性を計測する。

旧経路へ戻す場合は段階ごとに部品単位で戻せるようにし、保存形式は変更しない。旧・新の投影を比較する場合も購読・retain・送信を二重に開始しない。

## 7. 検証

`FeedControllerAsyncRegressionTest`、`FeedRelayMergeGapTest`、`HeldFeedEventsTest`、`FeedHistoryStoreTest` を維持する。追加する `FeedProjectorTest` では固定スナップショットに対する表示結果を検証する。

| ケース | 確認 |
| --- | --- |
| 計算中に削除・ミュート | 投稿が復活しない |
| 計算中にリアクション・プロフィール更新 | 新しい値を巻き戻さない |
| 表示外から窓へ戻る | 件数・引用・リポスト表示を失わない |
| 同秒・遅延・停止・復旧リレー | 順序と未取得範囲を保つ |
| 800件窓・アンカー消失・reset | 規定の窓と位置に決まる |
| close後に計算完了 | 公開しない |

性能は同じ固定データと同じ端末条件で、解析回数、全件投影回数、計算時間、スクロールのフレーム時間を比較する。構造抽出の段階は悪化しないことを条件とし、具体的な改善率は計測後に定める。

関連: [`feed-scroll-performance-design.md`](../feed-scroll-performance-design.md)、[`feed-relay-loading-fix-plan.md`](../feed-relay-loading-fix-plan.md)。
