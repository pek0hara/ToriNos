# フィード初回表示 リファクタ設計

## 1. 目的

フィードの初回表示（コールドスタート、長時間バックグラウンドからの復帰、対象著者の変更）で、
投稿を「いつ」「どう」見せるかの判断を整理する。本設計では次の性質を保証する。

1. 表示処理が中断・取り消し・競合しても、画面が「透明な投稿だけが並ぶ」状態に取り残されない。
2. 「最初の投稿群を見せてよい」の判断を `FeedController` の1か所に置き、仮想時間の単体テストで検証できる。
3. 初回の一括フェードイン演出（製品要件。[`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md) 2章・5章）を維持する。

## 2. 対象範囲

### 2.1 対象

- `ui/components/NoteTimeline.kt` の初回表示まわり（staging、空表示の遅延、表示時のスクロール）
- `ui/components/NoteListItems.kt` の `eventContentVisible` / `eventEnterFadeMillis`
- `ui/components/InitialEventReveal.kt`
- `ui/feed/FeedController.kt` の初回公開タイミングと `initialFeedState`
- `ui/feed/FeedScreen.kt` の `FeedTimelinePane`（`shouldStageInitialEvents`、`resetToTopRequest`）

### 2.2 非目標

- 履歴ページング、リレー別カーソル、エンゲージメント取得の変更
- トップバー／ボトムナビの折りたたみ演出（[`feed-chrome-interaction-design.md`](./feed-chrome-interaction-design.md)）
- プロフィール画面のタイムライン（staging を使っていないため対象外）
- 通常スクロール中・ライブ新着・ページ追加時の行演出の追加

## 3. 経緯

2026-09 に「アプリ起動時にフィードが真っ白になり、タップだけ効く」不具合を調査した。
原因は `NoteTimeline` の初回表示処理である。

- 表示処理が「組み立て済み」を先に記録し、1フレーム待ちと `scrollToItem(0)` を挟んでから
  透明を解除していた。
- 静穏待ち（200ms）と最大待ち（500ms）の2つの合図が互いを止めず、処理中に2つ目の合図が来ると
  再実行された処理が「組み立て済み」を見て即終了した。
- `scrollToItem(0)` はユーザーのドラッグで取り消される。取り消されると透明解除に到達しない。
- 行は `alpha(0)` で隠れているだけなので、見えないのにタップは効く。

応急処置として、表示状態を `InitialEventReveal` に切り出し、`finally` で必ず透明を解除し、
完了判定を「透明解除済みか」に変えた。また、フォロー一覧の変更ごとに `FeedViewModel` を
作り直していた問題は `FeedController.updateAuthors()` で解消した。

応急処置で症状は止まるが、次章の構造的な課題は残っている。

## 4. 現状と課題

### 4.1 初回表示の判断が2層に分かれている

| 層 | 持っている判断 |
|---|---|
| `FeedController` | 受信の一括反映（`TIMELINE_BATCH_DELAY_MS` = 150ms）、履歴ページ境界での公開（`revealHistoryThrough`）、`initialFeedState`（Loading → Slow（2.5s）→ ContentReady / Empty / Failed） |
| `NoteTimeline` | 最初の投稿到着後の静穏待ち 200ms・最大待ち 500ms、空表示の 500ms 遅延、`Empty` を `Slow` に見せかける上書き、透明解除 |

コントローラが「公開した」状態を、UI がもう一度タイマーで待ち直して上書きしている。

- 表示結果を知るには、両層のタイマーと状態を同時に追う必要がある。
- UI 側のタイマーは Compose の `LaunchedEffect` 群と共有変数で連動しており、単体テストできない
  （`commonTest` に Compose UI テスト基盤はない）。
- 同じ「待ち」の概念が 150ms（コントローラ）と 200ms / 500ms（UI）で二重に存在する。

### 4.2 透明で隠す方式は、失敗時に最悪の見え方になる

`NoteListItems` は `eventContentVisible = false` の間、各行を `alpha(0)` で描く。
初回表示処理が完了しないと、行は組み立て済み・操作可能・不可視のまま残る。

- 利用者からは原因が分からず、自分で抜け出す手段もない（引っ張って更新も見えない）。
- 「組み立て前」の段階は既にスピナー表示で表現できている（`!isContentComposed` のとき）。
  透明にしている目的はフェード演出だけである。

演出のための状態が、失敗すると内容そのものを消す。演出は失敗しても省略されるだけであるべきである。

### 4.3 表示処理がスクロールを抱えている

表示処理の中で `scrollToItem(0)` を呼んでいる。これは `followingListState` / `globalListState` が
`FeedTimelinePane` の外で保持され、ViewModel の切り替えをまたいで使い回されるためである。

- スクロールはユーザー操作で取り消される処理であり、表示完了の条件に置くべきではない。
- スクロール位置のリセットは「フィードを取り直した」というデータ側の出来事に属する。

### 4.4 リセットの合図が UI のローカル状態に分散している

`FeedTimelinePane` は `shouldStageInitialEvents` と `resetToTopRequest` をローカルに持ち、
`resetToLatest()` や `updateAuthors()` が `true` を返したときに手で更新している。

- コントローラが状態を捨てた事実と、UI の staging 状態のリセットが別々に管理されている。
- ページャーのページ破棄・再生成で `NoteTimeline` の `remember` が失われると、
  ViewModel 側の状態とずれる余地がある。

## 5. 設計方針

1. **演出は失敗しても省略されるだけにする（fail-open）。** 内容の表示可否を演出の完了に依存させない。
2. **「見せてよい」の判断はコントローラに一本化する。** UI はタイマーを持たず、受け取った状態を描くだけにする。
3. **リセットはデータの出来事として表す。** 取り直しの世代番号を `UiState` に載せ、UI はそれをキーにする。

## 6. 設計

### 6.1 コントローラの初回公開ゲート

初回フェーズ（`isInitialLoad` かつ未公開）の間、コントローラは受信した投稿を
`pendingTimelineEvents` に保持し、`UiState.events` へは公開しない。次のいずれかで最初の一括公開を行う。

| 条件 | 既定値 | 意図 |
|---|---|---|
| いずれかのリレーの初回履歴ページ境界（`onPageBoundary`） | — | ひとまとまりの結果が揃った |
| 最後の受信から静穏時間が経過 | 200ms | 到着が落ち着いた |
| 最初の受信から最大待ち時間が経過 | 500ms | 到着が続いても待たせすぎない |

- 公開と同時に `initialFeedState = ContentReady` とし、`isInitialLoad = false` にする。
- 公開後は現行どおり `TIMELINE_BATCH_DELAY_MS` の一括反映に戻る。
- 静穏・最大待ちは1つの Job で管理し、公開・`resetFeedState()`・`close()` で必ず取り消す。
  二重公開は「公開済みフラグ」で防ぎ、合図の重複は冪等にする。
- `Empty` / `Failed` / `Slow` の判定は現行の `onState` のまま据え置く。

これにより、`UiState.events` が空でないことと「見せてよい」が一致する。UI 側の静穏・最大待ちの
タイマー、`hasInitialEventBatchStarted`、`initialEventRevealRequest` は不要になる。

**確認事項:** エンゲージメント履歴の取得開始は現在、受信時（`appendFeedEvent` 経由）に予約されており、
公開を待たない（`initialEngagementHistoryStartsBeforeEveryFeedRelaySettles` で固定済み）。
ゲート導入後もこのテストが通ることを確認する。

### 6.2 取り直しの世代番号

`UiState` に `feedGeneration: Int` を追加する。

- `resetFeedState()`（`resetToLatest()` / `updateAuthors()` の共通経路）で1増やす。
- UI は `feedGeneration` をキーにして初回演出とスクロール位置のリセットを行う。
- `FeedTimelinePane` の `shouldStageInitialEvents` と `resetToTopRequest` を廃止する。
  `resetToLatest()` / `updateAuthors()` の戻り値に UI が反応する必要はなくなる。

### 6.3 fail-open な一括フェード

`eventContentVisible`（外から与える「透明/不透明」）を廃止し、**初回公開の瞬間に一度だけ走る入場アニメーション**に置き換える。

- `NoteTimeline` は `feedGeneration` ごとに「このリストはまだ初回演出前か」を覚える。
- 初回公開（`events` が空 → 非空）を検知した行群を、`MutableTransitionState(initialState = false)`
  を `targetState = true` にした `AnimatedVisibility(enter = fadeIn(tween(eventEnterFadeMillis)))`
  （またはリスト全体の `graphicsLayer { alpha }` を同様の遷移状態で駆動）で包む。
- 遷移はフレームクロックで進むため、コルーチンの取り消しやユーザー操作で途中停止しない。
  最悪でも演出が一瞬で終わるだけで、内容は必ず見える。
- 演出の対象は初回公開の行群だけとし、通常スクロール・ライブ新着・ページ追加では発火させない
  （[`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md) 5章の制約を維持）。
- 空表示の 500ms 遅延も、状態の上書きではなく `fadeIn(tween(delayMillis = 500))` の入場遅延として表す。
  `Empty` を `Slow` に見せかける分岐は削除する。

`InitialEventReveal` は本段階で不要になり、削除する。

### 6.4 スクロール位置のリセット

- `feedGeneration` が変わったときに `scrollToItem(0)` を行う。表示の完了条件には含めない。
- 取り消されても、世代が変わった直後はリストがスピナー1行だけなので位置は実質先頭であり、実害はない。
- 将来 `LazyListState` を世代ごとに持たせる場合も、ボトムナビの「先頭へ戻る」と
  チロームの先頭判定（`followingListState` / `globalListState` を外から参照）を壊さないことを条件とする。

### 6.5 変更後の責務

| 層 | 責務 |
|---|---|
| `FeedController` | 受信の保持と公開タイミング、`initialFeedState`、`feedGeneration` |
| `NoteTimeline` | `events` が空ならスピナー／空表示／失敗表示、非空なら一覧。初回公開時に一度だけフェード。世代変更時に先頭へ |
| `FeedTimelinePane` | ViewModel の取得、購読の開始・停止、著者の受け渡し |

## 7. 移行手順

各段階は単独でコミット・リリースできる大きさにする。

### Phase A: fail-open なフェード

1. `NoteListItems` の `eventContentVisible` による `alpha` を、6.3 の一度きりの入場アニメーションに置き換える。
2. `NoteTimeline` の `isContentVisible` 参照を外す。「組み立て前はスピナー」の分岐は残す。
3. 空表示の遅延を入場遅延に置き換え、`Empty` → `Slow` の上書きを削除する。

**完了条件:** 表示処理がどこで中断しても、行が不可視のまま残る経路がコード上に存在しない。

### Phase B: コントローラの公開ゲートと世代番号

1. 6.1 のゲートを `FeedController` に実装する。
2. `UiState.feedGeneration` を追加する。
3. テストを追加する（8章）。

### Phase C: UI 側 staging の撤去

1. `NoteTimeline` の静穏・最大待ちの `LaunchedEffect`、`stageInitialEvents`、
   `initialEventQuietMillis`、`initialEventMaxWaitMillis`、`emptyStateDelayMillis` の状態分岐を削除する。
2. `FeedTimelinePane` の `shouldStageInitialEvents`、`resetToTopRequest` を削除し、`feedGeneration` に置き換える。
3. `InitialEventReveal` とそのテストを削除する。
4. [`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md) の `shouldStageInitialEvents`
   への言及を、本設計の「初回公開時の一度きりのフェード」に更新する。

## 8. テスト

`FeedControllerAsyncRegressionTest` の仮想時間で次を固定する。

- 初回履歴のページ境界で、それまでの受信がまとめて1回で公開される。
- ページ境界が来なくても、最後の受信から 200ms で公開される。
- 受信が途切れず続いても、最初の受信から 500ms で公開される。
- 公開前に `resetFeedState()` / `updateAuthors()` / `close()` すると、保留中の公開が取り消される。
- 公開は1世代につき1回だけ起きる（静穏と最大待ちが同時に満たされても二重公開しない）。
- `resetToLatest()` / `updateAuthors()` で `feedGeneration` が1増え、同じ著者での `updateAuthors()` では増えない。
- 公開後の受信は従来どおり `TIMELINE_BATCH_DELAY_MS` で反映される（既存テスト `liveEventBurstIsPublishedAsOneDelayedBatch` の前提を初回公開後に置き直す）。

UI は Compose UI テスト基盤がないため、次をシミュレータで確認する。

- コールドスタートで、スピナーから投稿一覧がフェードインする（6回以上）。
- 起動直後に画面をドラッグし続けても、投稿一覧が表示される。
- 長時間バックグラウンドから復帰すると、先頭からフェードインし直す。
- フォローを追加・解除すると、フォローフィードが先頭から取り直される。
- 通常スクロール、ライブ新着、過去ページ追加ではフェードが発火しない。

## 9. 未確定事項

- 公開ゲートの既定値（200ms / 500ms）は現行 UI の値を移しただけである。ページ境界を主条件にした後、
  最大待ちを短くできるかは実機計測で決める。
- 初回公開までの間にリレーの `Slow` 表示（2.5s）が出るケースで、ゲートの最大待ちとの関係
  （Slow 中に最初の投稿が来たら即公開するか）を決める。
- `LazyListState` を世代ごとに持たせるか（6.4）。チロームと「先頭へ戻る」への影響を見て判断する。
