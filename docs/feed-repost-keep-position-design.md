# リポストされても表示中の投稿を動かさない

## 目的
フォロー中タイムラインで投稿をリポストすると、その投稿が一覧の先頭へ移動し、読んでいた場所から消えたように見える（ユーザーからの指摘、2026-10-22）。
表示済みの投稿は、リポストされても位置を変えないようにする。スクロール位置も動かさない。

## 今の動き
- リポストを表示する一覧（フォロー中タイムライン、自分と他人のプロフィール。`includeRepostsInFeed = true`）で kind 6 を受け取ると、`appendRepostedEvent` が元の投稿を `appendEvent(target, timelineCreatedAt = repost.createdAt)` で取り込む。
- 元の投稿をすでに保持していると、`updateTimelineSortTime` が並び順の基準時刻（`eventSortTimes`）をリポスト時刻に上げ、一覧を並べ直す（`FeedController.kt` の `updateTimelineSortTime`）。
- その結果、表示中の投稿が先頭へ移る。自分のリポストに限らず、フォロー中の誰かのリポストでも同じことが起きる。

## 方針
「表示済み」の投稿は、リポストを受け取っても基準時刻を上げない。

表示済みの定義（`FeedController` の状態だけで判定する。画面の `UiState` は非同期で反映されるので使わない）:
- 保持中（`rawEvents` にある）
- 表示待ちのまとめ（`pendingTimelineEvents`）に入っていない
- ミュート・NGワードで隠れていない（`isFiltered` でない）
- 表示境界より新しい（今の基準時刻 ≥ `historyRevealOldestAt`。`null` のときは境界なし）

ページ送りの窓（`windowAnchorId`）の外にある投稿も、スクロールすればたどり着けるので表示済みとして扱う。

表示済みでない投稿は今までどおり基準時刻を上げる:
- まだ受け取っていない投稿（リポストで初めて届く）→ リポスト時刻の位置に出る
- 表示待ちのまとめに入っている投稿 → まとめて出すときにリポスト時刻で並ぶ
- 表示境界より古く隠れている投稿 → リポスト時刻で境界内に入り、先頭側に出る（`repostRevealedLaterKeepsItsRepostedByAndCount` の動き）
- ミュート中の投稿 → 解除で出るときはリポスト時刻の位置

### 基準時刻そのものを据え置く理由
並べ直しだけ止めて基準時刻を上げると、新しい投稿をまとめて出すとき（`flushPendingTimelineEvents`）や、ミュート変更・ページ送り（`rebuildFilteredEvents`）で全体を並べ直したときに結局移動する。基準時刻を据え置けば、どの経路で並べ直しても位置は変わらない。

基準時刻は並び順のほかに次でも使っているが、据え置いても問題ない:
- 表示境界の判定（`planFeedWindow`、`revealedEvents`）: 表示済みの投稿はもともと境界内なので変わらない。
- 表示できる投稿の最新時刻（`HeldFeedEvents.noteRevealable`）: 表示済みの投稿はすでに数えてあるので、更新しなくてよい。（保持数の上限で捨てる順は受け取った順で、基準時刻は使っていない。）
- 付随情報の取得範囲（`engagementFetcher` の `sortTimeOf`）: 表示位置どおりの扱いになる。
- 復帰時の差分取得の起点（`gapSince` = 基準時刻の最大値）: 小さくなる側にしか変わらないので、取りこぼしは起きない（取り直しが少し増えうるだけ）。
- 新着件数（`newPostCount`）: 表示待ちのまとめを出すときだけ数えるので変わらない。

### 変えないもの
- 「〇〇がリポスト」の表示（`markRepostedBy`）とリポスト数。動かない投稿にも付く。
- リポスト解除。もともと位置は戻していないので変化なし。
- 引っ張って更新（`refresh`）は保持中の投稿を捨てずに差分を取るので、更新しても表示済みの投稿は動かない。
- 保持をすべて捨てて取り直す場合（`resetFeedState`: 長時間のバックグラウンド復帰 `resetToLatest`、フォロー一覧の変更 `updateAuthors`、`resetHistory`）は、そのあとリポスト時刻の並びになる。
- グローバルタイムラインはリポストを出さないので対象外。

## 受け入れる差
- 初回表示の直後、元の投稿とリポストが別のリレーから前後して届くと、先に元の投稿が表示された場合は元の時刻の位置、リポストが先なら先頭側になる。表示境界が決まるまでは古い投稿は隠れている（`historyRevealOldestAt = historyFloor`）ので、起きるのは境界より新しい投稿だけで、ずれも小さい。
- フォロー中の人が古い投稿をリポストしても、その投稿をすでに表示していれば先頭には出ない。新着としては気づけなくなるが、読んでいる位置を守る方を優先する。

## 実装
`FeedController.updateTimelineSortTime` の先頭で表示済みかを判定し、表示済みなら何もしない。

```kotlin
private fun updateTimelineSortTime(eventId: String, timelineCreatedAt: Long) {
    if (isShownInTimeline(eventId)) return
    ...今までどおり
}

/** 表示済みの投稿。リポストを受け取っても位置を変えない。 */
private fun isShownInTimeline(eventId: String): Boolean {
    val event = rawEvents[eventId] ?: return false
    if (eventId in pendingTimelineEvents || isFiltered(event)) return false
    val sortTime = eventSortTimes[eventId] ?: event.createdAt
    return sortTime >= (historyRevealOldestAt ?: Long.MIN_VALUE)
}
```

`updateTimelineSortTime` を呼ぶのは `appendEvent` の「保持済み」の分岐だけ（リポスト経由の取り込みと、リポスト先の後追い取得 `repostTarget`）なので、ほかの経路には影響しない。

## テスト（先に書いて失敗を確認する）
`FeedControllerAsyncRegressionTest` に、`includeRepostsInFeed = true` のコントローラでライブ購読から届ける形で追加する。

1. 表示中の投稿がリポストされても位置が変わらない: A（新）・B（古）を表示 → B のリポストを受け取る → 並びは A, B のまま。「がリポスト」とリポスト数は付く。
2. 全体を並べ直しても位置が変わらない: 1 のあと新しい投稿 C が届く（表示待ちのまとめを出すとき保持中の全件を基準時刻で並べ直す）→ C, A, B。
3. 未受信の投稿はリポスト時刻の位置に出る: 新しいリポストで初めて届いた古い投稿は先頭に出る（今の動きの確認）。
4. 表示待ちのまとめにある投稿はリポスト時刻で並ぶ: 投稿とそのリポストが同じまとめの間に届く → リポスト時刻の位置。
5. 既存の `repostRevealedLaterKeepsItsRepostedByAndCount`（表示境界の外の投稿）が通り続ける。

## 確認
- `./gradlew :composeApp:allTests` 相当（少なくとも `jvmTest` と `iosSimulatorArm64Test`）。
- 実機: 回帰テスト F-8・F-20 に加え、「フォロー中で途中までスクロールし、表示中の投稿をリポストしても位置が変わらない」を `docs/regression-test.md` に足す。
