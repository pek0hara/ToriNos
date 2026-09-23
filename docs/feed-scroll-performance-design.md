# フィードスクロール体験 改善設計

## 1. 目的

フィード画面を、演出ではなく描画経路の軽量化によって滑らかにする。

本設計が保証する利用者向けの性質は次のとおり。

- ドラッグ中は指の移動へ遅延なく追従する。
- フリングは Compose 標準の物理挙動を利用する。
- 画像ロード、プロフィール更新、リアクション更新で無関係な投稿を再構築しない。
- 画像ロード完了後もカード高を変えない。
- フィード途中の閲覧中に新着を受信しても、現在位置を移動させない。
- 高速スクロール中は、表示に必須でない処理を開始しない。

投稿カードのスクロール連動フェード、拡縮、移動、`animateItem()`、カードへの
`graphicsLayer` エフェクト、独自 `FlingBehavior` は導入しない。

## 2. 現状と課題

現在の実装には、すでに次の良い性質がある。

- `NoteTimeline` は `LazyColumn` と `LazyListState` を利用している。
- 投稿 ID が stable key に設定されている。
- Coil の `ImageRequest` を URL 単位で `remember` し、クロスフェードを無効化している。
- NIP-92 / NIP-94 に画像寸法がある場合は、ロード前から表示高を確保している。
- グリッド画像は固定アスペクト比を持つ。
- タイムライン画像はデコードサイズを制限し、GIF は先頭フレームのみ描画している。
- イベント一覧の重い集約の一部は `Dispatchers.Default` で処理している。

一方、改善対象は次のとおり。

1. `FeedViewModel.UiState` が投稿一覧、プロフィール、リアクション、返信、引用を一つの
   StateFlow に保持している。1件の更新でも `NoteTimeline` から全行のパラメータ組み立てを
   再評価する。
2. `noteListItems` と `NoteCard` 内で、参照 pubkey 抽出、引用モデル構築、本文・URL・画像・
   Nostr タグ解析、日時文字列生成などを行っている。
3. ライブイベントはバッチ後に表示一覧の先頭へ統合される。stable key による `LazyColumn` の
   スクロール位置保持のおかげで視覚的なジャンプは実害として確認されなかったが、途中閲覧中に
   気づかれないまま一覧が伸びる点は UX 上望ましくないため、6章のとおりバッジ通知を追加する
   （2026-09 実装済み）。
4. トップバー折りたたみ用 `NestedScrollConnection` がドラッグ差分を消費し、毎フレーム
   画面上位の State を更新している。標準スクロールへの追従を阻害しうる。
5. Coil キャッシュはライブラリ既定値へ依存し、フィード向けの単一ローダー、上限、
   プリフェッチ方針が明文化されていない。
6. リンクプレビューが X 投稿へ解決された行は `XPostEmbed` 経由で実 WebView（Android:
   `AndroidView` + `WebView`、iOS: `UIKitView` + `WKWebView`）を生成する。初回表示時に
   ネイティブ View 生成・ネットワーク取得・JS 実行のコストがそのままスクロール経路に乗り、
   生成開始はスクロール中かどうかを判定していない。`XPostSnapshotCache` により再訪時の
   再ロードは避けられているが、初回表示のタイミング抑制と、JS からの高さ報告で
   `contentHeight` が段階的に伸びる間のカード高変動（8.1 の原則への抵触）は未対応（9章）。

1・2 は部分的に緩和済みである。`FeedController.updateEvents` の走査は `Dispatchers.Default`
へ退避し、`noteListItems` は `NoteCard` へ渡す `profiles` をそのノートに関係する pubkey だけ
へ絞り込んでいる。ただし `FeedItemUiModel` / `FeedItemMapper` による構造化（4章）は未着手で
あり、本文解析は依然 Composable 内（`NoteCard` の `remember(event.content, event.tags,
event.kind) { parseNoteContent(event) }`）で行われている。

4 のトップバー折りたたみは、2026-09 の実装判断で製品要件として維持することが確定した
（5.1 章）。当初案の「Phase 1 で撤去」は取り下げ、要件を残したまま技術的な問題点だけを
解消する設計へ切り替える。

なお、コールドスタート/バックグラウンド復帰時の一括フェードイン演出
（`eventEnterFadeMillis`、`FeedScreen.kt`）はレビューの結果、通常スクロール中には発火せず
（`shouldStageInitialEvents` が真のときのみ）、5 章が禁止する「スクロール行の演出」には
該当しないことを確認した。この演出も製品要件として維持する。

## 3. 設計原則

### 3.1 単方向データフロー

```text
Nostr EVENT / profile / engagement
              |
              v
        FeedController
              |
              v  Default dispatcher
        FeedItemMapper
              |
              v
  FeedItemUiModel (immutable)
              |
              v
     LazyColumn / NoteCard
```

Composable は文字列やメディア情報を解析せず、表示用モデルを描画するだけにする。

### 3.2 スクロール状態をデータ状態へ混ぜない

`firstVisibleItemScrollOffset` のような高頻度値を ViewModel へ渡さない。
UI から Controller へ通知してよいのは `isAtTop` の値が変化した瞬間だけとし、`isScrolling`
(`isScrollInProgress`) はスクロール中処理の抑制（11章）にのみ使う UI ローカルな値として扱い、
Controller へは渡さない。

### 3.3 不変データを使う

Compose が未変更行を安全に skip できるよう、UI モデルは完全に不変とする。
通常の `List` / `Map` を `@Immutable` で偽装せず、`kotlinx.collections.immutable` の
`PersistentList` / `PersistentMap` を使う。

## 4. 状態モデル

> **スコープ注記（2026-09）**: `NoteCard` は `ChannelScreen` / `JournalScreen` /
> `SearchScreen` / `ThreadScreen` でも共用されており、フィード専用の
> `FeedItemUiModel` / `PersistentList` へ全面移行するには `NoteCard` の公開シグネチャ変更と
> 4画面の呼び出し側改修が伴う。これは「フィードのスクロール性能」という本設計のスコープを
> 超えるため、本節のモデルは目標形として残しつつ、実装（Phase 2）は 7 章 `FeedItemMapper` の
> フィード内部専用の事前変換に限定する。`NoteCard` の呼び出しは現行の個別パラメータのまま
> とし、事前変換した値をその場で渡す。`NoteCard(item, onAction)` への移行と
> `kotlinx.collections.immutable` の全面導入は、他画面の改修とあわせて別タスクで扱う。

`UiState` を、リストの構造状態と投稿単位の表示状態へ分ける（目標形）。

```kotlin
@Immutable
data class FeedUiState(
    val visibleItems: PersistentList<FeedItemUiModel> = persistentListOf(),
    val pendingNewPostCount: Int = 0,
    val loadState: FeedLoadState = FeedLoadState.Initial,
    val isRefreshing: Boolean = false,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val historyRequestGeneration: Int = 0,
    val message: FeedMessage? = null,
)

@Immutable
data class FeedItemUiModel(
    val id: String,
    val layoutType: FeedItemContentType,
    val author: ProfileUiModel,
    val repost: RepostUiModel?,
    val timestampText: String,
    val body: NoteBodyUiModel,
    val replyParent: QuoteUiModel?,
    val quotes: PersistentList<QuoteUiModel>,
    val engagement: EngagementUiModel,
    val isOwnPost: Boolean,
    val isMuted: Boolean,
)

enum class FeedItemContentType {
    Text,
    Media,
    Repost,
    ContentWarning,
}
```

`NostrEvent`、`NostrProfile` も Compose 安定性の対象になる。完全な不変性を保証できない型は、
表示に必要な値だけを不変 UI モデルへコピーする。投稿操作に原本が必要な場合は、UI モデルへ
原本を保持せず、`FeedItemUiModel.id`（イベント ID）で Controller の canonical event を参照する。

### 4.1 投稿単位の差分更新

Controller は `eventId -> FeedItemSource`（元の `NostrEvent` と、reposter pubkey・引用/返信先 ID
など UI モデル再生成に必要な参照情報の組）と `eventId -> FeedItemUiModel` を保持する。

- リアクション更新: 該当 ID の `EngagementUiModel` だけ再生成する。
- プロフィール更新: `pubkey -> Set<eventId>` の逆引きで影響カードだけ再生成する。
- 引用・返信解決: 参照先 ID の逆引きで影響カードだけ再生成する。
- 新着・削除: `visibleItems` の構造だけ変更する。

同値の UI モデルは既存インスタンスを再利用する。新しい `PersistentList` が発行されても、
未変更行には同一インスタンスが渡るようにする。

### 4.2 UI アクション

各行で多数の捕捉ラムダを組み立てない。安定した dispatcher を1つ渡す。

```kotlin
sealed interface FeedItemAction {
    data class Open(val eventId: String) : FeedItemAction
    data class Like(val eventId: String) : FeedItemAction
    data class Repost(val eventId: String) : FeedItemAction
    data class OpenProfile(val pubkey: String) : FeedItemAction
    // reply, delete, report, media click など
}
```

`NoteCard(item, onAction)` の形にし、イベント ID ごとのクロージャ生成を最小化する。

## 5. LazyColumn

```kotlin
LazyColumn(
    state = listState,
    modifier = modifier,
) {
    header()

    items(
        items = state.visibleItems,
        key = FeedItemUiModel::id,
        contentType = FeedItemUiModel::layoutType,
    ) { item ->
        FeedItem(
            item = item,
            onAction = onAction,
        )
    }
}
```

- `FlingBehavior` は指定しない。
- 通常スクロールに `animateItem()` を指定しない。
- stable key は Nostr event ID とする。
- `contentType` は実際に構造が近いレイアウトだけを同じ型にする。全タイプが同じ
  `NoteCard` 構造の間は `Note` 1種類でもよく、見かけだけの細分化はしない。
- `eventEnterFadeMillis` によるフェードは、コールドスタート/バックグラウンド復帰時の
  一括表示演出（`shouldStageInitialEvents`）にのみ発火する現状の範囲を維持する。通常の
  ページング追加・ライブ新着統合ではこのフラグを真にせず、行単位で毎回発火させない。

### 5.1 トップバー

折りたたみ演出（スクロールに連動してトップバー・ボトムナビゲーションが隠れる）は製品要件
として維持する（2026-09 判断）。「外して固定トップバーにする」という当初案は不採用とし、
演出を残したままスクロール品質を上げる制約の中で設計する。

現在の `chromeNestedScrollConnection`（`FeedScreen.kt`）は、折りたたみを残す場合に満たす
べき次の性質をまだ満たしていない。

- `onPreScroll` で `LazyColumn` が処理する前にドラッグ差分を先取りしている。
- ドラッグ中、`onChromeCollapseFractionChange` 経由で `AppSessionCoordinator` の
  `feedChromeCollapseFraction`（Compose State）を毎フレーム更新している。
- `onPostFling` でフリング速度を消費し、独自の `tween` アニメーションへ置き換えている。

これらは11章の「スクロール中も実行してよい処理」の原則に反するため、次を満たすよう
再設計することを別タスクとする。

- リストの利用可能なスクロールを先取りしない。
- 画面ルートの StateFlow / Compose State を毎ピクセル更新せず、折りたたみの開始・完了の
  境界値だけを通知する（6章の `isAtTop` 通知と同じ設計）。
- フリング速度を消費・置換せず、フリング停止後の `LazyListState` の位置から折りたたみ状態を
  決定する。
- Macrobenchmark で固定トップバー相当のフレーム性能を確認する。

このタスクは Phase 5（計測整備）の後に着手する。それまで現行実装を維持する。

### 5.2 最上部へ戻ったときの強制表示

> **再設計予定**: 慣性スクロールを新しい直接操作で中断するケースを含む最終要件は
> [`feed-chrome-interaction-design.md`](./feed-chrome-interaction-design.md) を正とする。本節は現行の
> 暫定実装に至った判断記録であり、次のリファクタリングで置き換える。

フィードの絶対先頭（`firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`）へ戻った
ときは、直前までトップバー・ボトムナビゲーションが完全に隠れていた場合も、両方を表示状態へ
戻す。フォロー／グローバルのどちらのタブでも同じ規則とする。

ただし、先頭から上方向へドラッグしてクロームを隠し始める間もリスト位置は一時的に絶対先頭の
ままである。この間に「先頭なら常に表示」を毎フレーム適用すると、折りたたみ操作と強制表示が
競合する。そのため、絶対先頭は常時上書きする条件ではなく、**ジェスチャー開始時にクロームが
完全非表示だった場合だけ、ジェスチャー終了時の settle target を決める最優先条件**として扱う。

```text
スクロール入力
    |
    +-- 最初の入力 ----------> 開始時に完全非表示だったかを記録
    |
    +-- ドラッグ中 ----------> 現行どおり collapseFraction を更新
    |                           （先頭判定による割り込みはしない）
    |
    +-- isAtTop が true ------> ジェスチャー終了時の再評価対象にする
    |
    `-- ジェスチャー終了／idle settle
                                |
                                +-- 開始時に完全非表示
                                |   かつ現在 isAtTop -> target = 0（表示）
                                `-- それ以外 --------> 最後の方向で 0 / 1
```

満たすべき不変条件は次のとおり。

1. ジェスチャー開始時に `collapseFraction == 1f` であり、終了時にアクティブなフィードが
   絶対先頭なら `collapseFraction == 0f` へ収束する。開始時に少しでも表示されていた場合は、
   先頭到達を理由とする再表示要求やアニメーションを発生させない。
2. settle target の決定では `isAtTop` が最後のスクロール方向より優先される。
3. 60ms の idle settle と `onPostFling` が別々の target を書かない。settle 要求を一つの経路へ
   集約し、新しい要求が古いアニメーションを取り消す。
4. `isAtTop` の `false -> true` 通知をジェスチャー中に抑止した場合も、ジェスチャー終了時に
   `LazyListState` の現在値を読み直す。通知が再発火することには依存しない。
5. タブ切り替え、フィードタブの再タップ、新着ボタン、長時間バックグラウンド復帰などの
   programmatic scroll でも同じ判定経路を使う。

実装では `hideChromeForCurrentGesture` を単なる表示抑止フラグとして使わず、最低限、次の状態を
`FeedScreen` 内の小さな chrome coordinator にまとめる。

```kotlin
data class FeedChromeGestureState(
    val isCollapseGestureInProgress: Boolean = false,
    val wasChromeHiddenAtGestureStart: Boolean = false,
    val lastScrollDelta: Float = 0f,
)

internal fun feedChromeSettleTarget(
    isAtTop: Boolean,
    forceRevealAtTop: Boolean,
    lastScrollDelta: Float,
): Float = when {
    isAtTop && forceRevealAtTop -> 0f
    lastScrollDelta >= 0f -> 1f
    else -> 0f
}
```

`wasChromeHiddenAtGestureStart` は最初の `UserInput` で一度だけ決め、ジェスチャー終了まで変更
しない。最上部へ一度到達した事実はラッチせず、終了時の位置がすでに先頭でなければ強制表示
しない。これにより、途中表示のクロームを先頭到達だけで全表示へ寄せたり、新着挿入や
同一ジェスチャー内の再移動で古くなった先頭判定を適用したりしない。

`snapshotFlow` は `isAtTop` を coordinator へ渡す。`onPostFling` と idle timeout はアニメーションを
直接開始せず、同じ `requestSettle()` を呼ぶ。`requestSettle()` はその時点の
`LazyListState` を読み、上の純粋関数で target を一度だけ決める。これにより、先頭到達後に古い
idle settle がクロームを再び隠す競合を防ぐ。

この変更はフィードデータ、ViewModel、ナビゲーション状態を増やさず、UI 内の一時状態だけで
完結させる。`collapseFraction` はトップバーとボトムナビゲーションの同期に引き続き共有するが、
共有値へ書くアニメーション経路は一つにする。

回帰テストは少なくとも次を含める。

- 途中位置でクロームが完全非表示の状態から絶対先頭へ戻り、終了後に target が `0f` になる。
- 開始時にクロームが途中表示または完全表示なら、先頭到達だけでは強制表示しない。
- 完全非表示で開始し、一つのジェスチャー内で方向を反転して先頭へ戻っても表示される。
- 絶対先頭から上方向へドラッグすると、ジェスチャー中は従来どおりクロームを隠せる。
- 先頭到達直前の idle settle が残っていても、表示後に再び隠れない。
- 非先頭では従来どおり最後の方向に応じて表示／非表示へ settle する。
- フォロー／グローバルのタブ切り替え先が絶対先頭なら表示される。

純粋関数の common test に加え、方向反転と古い settle の競合は Compose UI テストまたは実機操作で
確認する。これら二つは最終位置だけを入力する単体テストでは再現できないためである。

## 6. 新着投稿と位置保持

> **設計変更（2026-09、実装時レビュー）**: 当初案は新着投稿を `pendingLiveEvents` として
> 表示一覧の外に保持し、ボタン押下時にまとめて統合するものだった。実装時に、`items(key = ...)`
> で stable key を渡している `LazyColumn` は、リストの先頭に新しい要素が挿入されても
> 既存の可視 key の相対順序が変わらない限り、スクロール位置（先頭可視 key / offset）を
> 自動的に保持することを確認した。同じ前提のもとで `ChannelHistory`
> （`ui/channel/ChannelHistory.kt`）がすでに「新着は即座に統合し、先頭にいないときは
> バッジ件数だけ表示する」という単純な形で本番稼働している。これに倣い、新着投稿を
> 表示一覧の外へ保持する設計は不採用とし、次の単純な形へ変更する。

ライブ購読から到着した投稿は、これまでどおり `state.events` へ即座に統合する（`isAtTop` の
真偽に関わらず、バッチ後にマージする現行の `flushPendingTimelineEvents` の挙動を維持）。
先頭にいないときだけ、件数バッジを増やす。

```text
新着受信 → state.events へ統合（常時）
              |
              v
      isAtTop == false のときだけ newPostCount を加算
              |
              v
      「新しい投稿 N件」ボタン（タップで scrollToItem(0) のみ）
```

UI は次の境界値だけを Controller へ通知する。

```kotlin
LaunchedEffect(listState) {
    snapshotFlow {
        listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0
    }
        .distinctUntilChanged()
        .collect(viewModel::setAtTop)
}
```

「新しい投稿 N件」を押したときの処理はスクロール移動のみでよい（データは既に統合済みのため）。

1. `listState.scrollToItem(0)`（非アニメーション、長距離のアニメーション付き移動は使わない）を
   実行する。
2. 上記の境界値通知が `isAtTop = true` を検知し、Controller 側でバッジ件数を 0 に戻す。

新着投稿を一覧の外に保持するバッファがなくなったため、「内部バッファの上限」という概念は
不要になった。投稿データは常にキャンオニカルストアと `state.events` の双方にあり、可視件数の
上限は既存の `MAX_TIMELINE_EVENTS` によるトリミングがそのまま適用される。

## 7. 事前変換

`FeedItemMapper` は `Dispatchers.Default` 上で次を行う。

- `imeta` / NIP-94 と画像 URL の解析
- Web URL、引用 event ID、npub、メンション、ハッシュタグの解析
- 本文から画像 URL と Nostr URI を除いた表示文字列の生成
- content warning 判定
- リンクプレビュー対象 URL の選択
- 画像プレビュー URL、アスペクト比、blurhash の決定
- 日時表示文字列の生成
- 関連 pubkey の集合生成
- 引用、返信、リアクション表示モデルの生成

変換結果は event ID と、表示へ影響する入力の revision でキャッシュする。

```kotlin
data class FeedItemRevision(
    val eventId: String,
    val profileRevision: Long,
    val engagementRevision: Long,
    val referenceRevision: Long,
)
```

時刻表示は現在のような絶対日時または当日の時刻とし、1分ごとの全件更新を導入しない。
日付境界をまたいだ表示補正が必要なら、午前0時に可視行だけ更新する。

## 8. 画像

### 8.1 レイアウト予約

- 寸法ありの単一画像は `width / height` からロード前に高さを確定する。
- 寸法なしの単一画像は現在と同じ `16:9` の既定領域を使い、ロード後も変更しない。
- 複数画像は固定 `16:9` グリッドを維持する。
- 不正な幅・高さ、極端な比率は安全な範囲へ clamp する。
- blurhash は予約済み領域内だけで置換し、成功・失敗で親レイアウトを変えない。
- OGP カードも画像領域の固定高または既知アスペクト比を持つ。

サーバーが寸法を提供しない画像について、デコード後の実寸でカード高を変更しないことを
優先する。`ContentScale.Fit` による余白は許容する。

### 8.2 キャッシュ

アプリスコープで Coil `ImageLoader` を1つ生成し、プロフィール、投稿、OGP、サムネイルで共有する。

- memory cache と disk cache を明示的に有効化する。
- URL と要求サイズが同じリクエストは同じ cache key になるよう正規化する。
- 一覧では 360px / 720px 程度の縮小要求を使い、原寸画像は詳細表示だけで要求する。
- crossfade は一覧では無効のままにする。
- GIF / animated WebP は一覧では先頭フレーム、詳細画面だけ再生可能とする。
- キャッシュ容量は端末メモリに対する割合とディスク上限を platform ごとに定義し、固定の
  巨大値にしない。

**レビュー済み・未着手（2026-09）**: 現状は `coil3.SingletonImageLoader` の既定 `ImageLoader`
（`NetworkImage.kt` が `LocalPlatformContext` から暗黙に解決するもの）に依存しており、
memory/disk cache 自体はライブラリ既定値で有効になっている。明示的な `ImageLoader` を
アプリスコープで1つ生成する変更は、Android の `Application` / iOS のアプリ起動経路への
配線が必要で、フィード単体の変更にしては影響範囲が広い。現状の既定値で 8.1 のレイアウト
予約規律（ロード前後でカード高を変えない）は既に満たされているため、優先度を下げて
別タスクとする。

X 投稿の埋め込みは他メディアと異なり、実ブラウザエンジン（`WebView` / `WKWebView`）を
1行につき1インスタンス生成する。画像のデコードコストとは桁が違うため、画像と同じ規律
（先読みしない、スクロール中に開始しない、ロード前後でカード高を変えない）を明文で
別掛けする。

### 9.1 生成タイミング

- 初回の WebView 生成は、その行が可視になり、かつ `isScrollInProgress == false`
  （11章の debounce 後）になるまで遅延する。フリング中に複数の X 投稿を通過しても
  WebView を生成しない。
- `XPostSnapshotCache` にヒットする場合はこの限りではなく、保存済みビットマップを
  即座に描画してよい（ネイティブ View も JS 実行も発生しないため）。
- 同時に存在してよい生存中 WebView インスタンス数に上限を設け、超過分は画面内でも
  優先度の低いもの（中心から遠いもの）から破棄してスナップショット表示に切り替える。
  複数動画を同時再生しない（12章）のと同じ理由。

### 9.2 レイアウト予約

- `XPostSnapshotCache` にヒットする場合は、キャッシュ画像のアスペクト比から表示高を
  ロード前に確定する（8.1 と同じ規律）。
- ヒットしない初回表示は、JS 側の `ResizeObserver` 報告に依存せざるを得ない。この間の
  段階的な高さ変化は許容するが、既に確定した表示範囲より外側（画面外）でのみ広げ、
  可視領域内で読んでいる最中のカード高がジャンプしないよう、高さ確定前は最小領域を
  スクロール方向の下側にのみ拡張する。
- 高さ確定後にスナップショットを撮影し、以後の再訪はキャッシュ経由の固定高表示へ
  切り替える。

**実装済み（2026-09、要デバイス実機確認）**: `NoteTimeline` に `LazyListState.isScrollInProgress`
を `collectLatest` で監視し 200ms debounce する `isScrollSettled` を追加し、
`noteListItems(deferWebViewLoad = !isScrollSettled)` → `NoteCard` → `LinkPreviewCard` →
`XPostEmbed(deferLoad = ...)` まで配線した。`XPostEmbed.android.kt` /
`XPostEmbed.ios.kt` は `deferLoad == true` かつスナップショット未ヒットのとき、
`AndroidView` / `UIKitView`（実 WebView 生成）の代わりに `contentHeight` 分の空 `Box` を
描画するよう分岐した。あわせて、`deferLoad` 中（未生成）と WebView 生成後で
`assetsReady == false` の間（JS の ready シグナル未受信）のどちらも `CircularProgressIndicator`
を重ねて表示し、「予約領域が空白のまま無反応に見える」状態をなくした（ユーザーからの
指摘を受けて追加）。`assetsReady == true` になった時点でスピナーは消える。

**コードレビューで検出・修正（2026-09）**: `deferLoad` による WebView / WKWebView の破棄・
再生成時、`assetsReady`（`remember(postId, darkTheme)`）がリセットされておらず、再生成後の
未読み込みインスタンスに対してもスピナーが表示されず、かつスナップショット撮影の
`LaunchedEffect` が古い `assetsReady == true` を引き継いで未完成のスナップショットを
`XPostSnapshotCache` へ書き込みうる不具合があった。`factory` ブロックの先頭で
`assetsReady = false` を明示的に設定するよう修正。あわせて Android 版で
`embedUrl` の `remember` 化漏れ（iOS 版には既にあった）も修正した。

**コードレビューで検出・修正（2026-09、2回目）**: `deferLoad == true` の間に表示する
プレースホルダー `Box` に `onSizeChanged` が無く、`contentWidthPx` が 0 のまま固定されていた。
`XPostSnapshotCacheKey` は幅を含むため、`snapshot` の再取得判定（
`if (contentWidthPx > 0) XPostSnapshotCache[cacheKey] else null`）が遅延中は常に
`null` を返し、9.2 で「キャッシュヒット時は即座にビットマップを描画してよい」としていた
経路が機能していなかった（スクロールで往復するたびにキャッシュがあっても素通りしていた）。
プレースホルダー側にも `onSizeChanged { contentWidthPx = it.width }` を追加して修正。

Android・iOS シミュレータ双方のコンパイルは確認したが、この
セッションには実機・シミュレータでの目視確認手段がないため、実際のスクロール挙動
（WebView 生成タイミング、レイアウトジャンプの有無）は未検証。次にビルドを実機/
シミュレータで動かす際に確認すること。9.2 のレイアウトジャンプ緩和（画面外方向にのみ
拡張する等）は未着手。

## 10. プリフェッチ

プリフェッチは `LazyListState.layoutInfo` を `snapshotFlow` で監視し、可視範囲が変化したときだけ
候補を更新する。対象は最後の可視投稿の直後 2〜3件とする。

優先順位は次のとおり。

1. 未取得プロフィール情報
2. プロフィール画像
3. 小さい投稿サムネイル1枚目
4. リアクション情報

原寸画像、複数画像の全件、動画本体、GIF アニメーションは先読みしない。方向が変わった場合や
対象が遠ざかった場合は未開始ジョブをキャンセルする。通信中の同一 URL は Coil に集約させる。

**レビュー結果（2026-09）**: 優先順位1・2・4（プロフィール情報・プロフィール画像・
リアクション情報）は、`FeedController` がイベント受信のたびに `scheduleProfileFetch` /
`scheduleEngagementFetch` を可視範囲と無関係に呼んでおり、実質的に本章の意図より先行して
取得済みだった（新規実装は不要と判断）。優先順位3（小さい投稿サムネイル1枚目）だけが
未対応だったため、これを実装した。

**実装済み（2026-09）**: `NoteTimeline` に `LaunchedEffect(timelineListState, state.events)` を
追加し、`snapshotFlow` で最後の可視投稿の key を監視、直後 `ImagePrefetchAheadCount`（3）件の
`ParsedNoteContent.images.firstOrNull()` を `SingletonImageLoader.get(context).enqueue(...)`
で先読みする。表示側と同じキャッシュキーになるよう、`NoteCard.kt` の
`firstImagePreviewRequest()`（単一画像時は 720px、複数画像時は 360px + `ContentScale.Crop`
など、`ImagePreviewGrid` の実リクエストと同じ分岐）を表示・プリフェッチ双方から呼ぶ形に
共通化した（`buildNetworkImageRequest` を `NetworkImage.kt` に切り出し）。方向転換時の
明示的キャンセルは行わない簡略実装（Coil のリクエスト重複排除に委ねる）。

## 11. スクロール中の処理抑制

`isScrollInProgress` は UI 内だけで `distinctUntilChanged()` し、開始・停止イベントとして扱う。

- スクロール中も実行する: 既にある UI モデルの描画、低解像度キャッシュ画像の表示、
  `XPostSnapshotCache` にヒットした埋め込みのスナップショット表示。
- 停止まで遅延する: 動画自動再生、OGP 新規取得、高解像度画像要求、インプレッション送信、
  非表示内容の詳細解析、X 投稿埋め込みの初回 WebView 生成（9.1）。
- 常にバックグラウンドで継続する: Nostr 購読受信、重複排除、canonical store への保存。

停止判定には 150〜250ms の debounce を使い、短い指の離し直しで処理を反復開始しない。
ただし、`isAtTop` の境界通知（6章）には debounce を使わない。

**実装済み（2026-09）**: `NoteTimeline` の `isScrollSettled`
（`snapshotFlow { timelineListState.isScrollInProgress }.collectLatest { ... }` +
200ms `delay`）。スクロール再開時は `collectLatest` が保留中の `delay` を即座に打ち切って
`false` へ戻すため、停止判定だけが遅延し開始判定は遅延しない。現時点では X 投稿埋め込みの
初回 WebView 生成（9.1）だけがこの信号を使う。動画自動再生・OGP 新規取得・高解像度画像
要求への適用は未着手（該当機能が本セッション時点で未実装のため対象なし）。

## 12. 動画・GIF

現行一覧の GIF 先頭フレーム方針を維持する。将来インライン動画を追加する場合は、再生所有者を
カードではなくタイムラインに置く。

```kotlin
data class PlaybackCandidate(
    val eventId: String,
    val visibleFraction: Float,
    val distanceFromViewportCenter: Int,
)
```

- `isScrollInProgress == false`
- 可視率が閾値以上
- 中央に最も近い1件

を満たす投稿だけを再生する。条件から外れたプレイヤーは即座に pause し、画面外のデコードと
描画を停止する。複数同時再生はしない。

## 13. エラー・競合

- Mapper の失敗は投稿全体を落とさず、プレーンテキストだけの fallback UI モデルにする。
- プロフィールやリアクション更新と Mapper 完了が競合した場合、revision が古い結果を破棄する。
- 新着はバッチ後に即座に統合するため（6章）、統合待ちのスナップショット競合は発生しない。
  `newPostCount` の加算と `state.events` への統合は同じ `flushPendingTimelineEvents` 呼び出し
  内で順に行う。
- 削除イベントは `state.events` から即座に除去する。`newPostCount` は件数の目安であり、
  削除により実際の新着件数と多少ずれても許容する（バッジは「概ねの件数」を示す用途）。
- アカウント、フィード種別、リレー条件変更時は UI モデルキャッシュを世代単位で破棄し、
  `isAtTop` は既定値（真）へ戻す。別フィードのデータを混ぜない。

## 14. 実装単位

### Phase 1: スクロール経路の単純化

- stable key と標準 fling を維持する。
- `contentType` を UI モデルから供給する。
- デバッグ `println` をスクロールコールバックから除去する。

上記3点はレビュー時点（2026-09）ですでに満たされている。初期行フェードとトップバー
折りたたみは製品要件として維持することが決定したため（2章・5.1章）、Phase 1 で新たに
実装する項目はない。トップバー折りたたみの技術的な再設計は Phase 5 後の別タスクとする。

### Phase 2: 事前変換（フィード内部限定）

4 章のスコープ注記に従い、`NoteCard` のシグネチャは変更しない。

- `FeedItemMapper`（7章）を追加し、`Dispatchers.Default` 上で `parseNoteContent` 相当の解析・
  参照 pubkey 抽出・日時文字列生成を行う。
- 変換結果を event ID と revision（`FeedItemRevision`）でキャッシュし、`FeedController` の
  `UiState` に保持する。
- `noteListItems` はキャッシュ済みの結果を読み出し、`NoteCard` の既存パラメータへそのまま渡す
  （`NoteCard` 内の `remember(event.content, ...) { parseNoteContent(event) }` を置き換える）。

**実装済み（2026-09）**: `FeedItemMapper`（event ID 単位の LRU キャッシュ、`FeedController` の
`updateEventsMutex` 配下から直列に呼ばれるため排他制御なし）、`UiState.parsedContents`、
`NoteCard(precomputedContent = ...)` を追加。`ParsedNoteContent` / `parseNoteContent` は
`NoteCard` の公開シグネチャに現れるため `internal` にできず `public` とした。他4画面
（`ChannelScreen` 等）は `precomputedContent` を渡さないため、デフォルトの
Composable 内 `remember` 計算のまま動作は変わらない。日時文字列生成・引用/返信プレビューの
`parseNoteContent` 呼び出しは本 Phase の対象外（未着手）。

**コードレビューで検出・修正（2026-09）**: `FeedItemMapper.map()` が `events`（新しい順）を
そのままの順で `cache.remove`→再挿入していたため、`LinkedHashMap` の挿入順（＝eviction時に
`keys.first()` で先頭から捨てる順）が新しい投稿ほど先頭に来る「逆LRU」になっており、可視件数が
`maxEntries` を超えるたびに直近見ている投稿ばかり再パースされていた。`events.asReversed()`
（古い順に触れる）へ修正。あわせて `maxEntries` の既定値（600）が
`FeedController.MAX_TIMELINE_EVENTS`（800）を下回っており、通常の「もっと読み込む」操作だけで
キャッシュが機能しなくなる状態だったため、既定値を 1,000 へ引き上げた。

### Phase 2b（保留・別タスク）: UI モデル全面分離

- `FeedItemUiModel` / `FeedUiState` と `kotlinx.collections.immutable` を導入する。
- `NoteCard(item, onAction)` へ移行する。
- `ChannelScreen` / `JournalScreen` / `SearchScreen` / `ThreadScreen` の呼び出し側を追随させる。

### Phase 3: 新着バッジ通知

6章の設計変更により `pendingLiveEvents` は不採用。

- `UiState.newPostCount` を追加する。
- `FeedController.setAtTop(Boolean)` と `isAtTop` の境界通知を実装する。
- `NoteTimeline` に「新しい投稿 N件」ボタンを追加し、タップで `scrollToItem(0)` する。

**実装済み（2026-09）**: `FeedViewModel.UiState.newPostCount`、`FeedController.setAtTop` /
`isAtTop`（`flushPendingTimelineEvents` で非在頂時に加算、`resetToLatest` でリセット）、
`NoteTimeline` の `onAtTopChanged` パラメータと `NewPostsButton` を追加。`FeedTimelinePane`
（フィード本体）だけに配線し、`UserProfileScreen` / `MyProfileScreen` の `NoteTimeline` 呼び出し
はデフォルト（`onAtTopChanged = {}`）のまま未配線（バッジは出ない。回帰なし）。
自動テストは未着手（下記 15 章に追加予定）。

**コードレビューで検出・修正（2026-09）**: `NoteTimeline` の `onAtTopChanged` 購読用
`LaunchedEffect(timelineListState)` が `listState` インスタンスだけをキーにしていたため、
`FeedScreen` がタブ内でフィード種別（フォロー/ミュートフィード切替など）を変えて
`viewModelKey` だけ変わる場合、`listState` は使い回されて effect が再起動せず、
古い `viewModel`（＝古い `FeedController.setAtTop`）を束縛したクロージャが動き続けていた。
新しい `FeedController` の `isAtTop` が既定値のまま通知を受け取れず、新着バッジが
永久に発火しない不具合があった。`rememberUpdatedState(onAtTopChanged)` で常に最新の
コールバックを読むよう修正（`effect自体はtimelineListStateにのみ依存させたまま`）。

### Phase 4: 画像・プリフェッチ

- [見送り・別タスク] アプリスコープ ImageLoader とキャッシュ設定を追加する（8.2）。
- [完了・レビュー済み] 寸法のない画像を含む全メディア領域を固定する（8.1 は着手前から
  既に満たされていた）。
- [完了] 2〜3件先の画像サムネイル1枚目プリフェッチを追加する（プロフィール/リアクションは
  既存の即時取得で対応済みのため対象外、10章）。
- [完了・実機未検証] X 投稿埋め込みの初回 WebView 生成を `isScrollInProgress == false` まで
  遅延させる配線を追加した（9章）。同時生存インスタンス数の上限（9.1 の3点目）は未着手。

### Phase 5: 計測と調整

- Android Macrobenchmark と実機 60Hz / 120Hz 計測を追加する。
- iOS は Instruments の Core Animation / Time Profiler で同じシナリオを計測する。
- 独自 fling は、標準挙動の距離に明確な問題が実機比較で確認された場合だけ別設計にする。

**未着手・環境上の制約（2026-09）**: 本 Phase はコード変更ではなく、実機/エミュレータでの
計測実行が本体である。この作業セッションには次がないため着手できなかった。

- 接続済みの Android 実機/エミュレータ（`adb devices` で見える対象）と Android Studio
  Profiler。
- Xcode Instruments を操作できる GUI 環境。
- Macrobenchmark 用の `:benchmark` Gradle モジュール（本リポジトリに未整備。AGP/Kotlin
  バージョンとの整合を実機実行なしに確認する手段がないため、動作未検証のまま雛形だけを
  追加することは避けた）。

次にこの Phase へ着手する際のチェックリスト。

1. `:benchmark` モジュールを追加し、`androidx.benchmark.macro` プラグインを導入する
   （プロジェクトの AGP/Kotlin バージョンとの互換性を要確認）。
2. 14章の実機シナリオ1〜6を固定データセットで再現するテストコードを書く。
3. Phase 1 開始前のベースライン計測が残っていない場合、現行 `main` ブランチ（本設計の
   変更前）でも同シナリオを一度計測し、比較対象として保存する。
4. Android は実機 2 台以上（60Hz・120Hz 各1台）で計測する。
5. iOS は Instruments の Core Animation / Time Profiler で同シナリオを手動計測する。
6. 結果を 15.3 の指標（frame time, janky frame, main-thread CPU, 画像デコード回数, GC,
   ネットワーク再取得回数, 投稿単位の recomposition count）で記録し、17章の完了条件と
   突き合わせる。

## 15. 検証

### 15.1 自動テスト

- stable key がイベント ID で一意になる。
- 同じ入力 revision は同じ UI モデルインスタンスを再利用する。
- 1件のリアクション更新でほかの UI モデルが変わらない。
- プロフィール更新は逆引き対象の投稿だけを変更する。
- `isAtTop == false` のときの新着受信で `newPostCount` が加算件数分だけ増える。
- `setAtTop(true)` で `newPostCount` が 0 に戻る。
- `resetToLatest` 後は `isAtTop` が既定値（真）に戻る。
- 画像寸法あり・なし・不正値で、ロード前後のコンテナ寸法が同じになる。
- `XPostSnapshotCacheKey` がキャッシュヒット判定に必要な条件（投稿ID・テーマ・表示幅）で
  一意になる。

### 15.2 Compose テスト

- リアクション更新時、対象 ID の semantics だけが更新される。
- 新着受信中も先頭可視 item key と offset が変わらない（stable key による自動保持を確認）。
- 「新しい投稿 N件」ボタンをタップすると先頭へ移動し、ボタンが消える。
- 高速スクロール中は動画・OGP・高解像度要求を開始しない。
- 高速スクロール中は X 投稿埋め込みの初回 WebView 生成を開始しない。

### 15.3 実機シナリオ

同じ固定データセットで次を計測する。

1. テキスト投稿100件を一定速度で往復フリング。
2. 画像投稿を50%以上含む100件を、cold cache / warm cache で往復フリング。
3. スクロール中に毎秒複数のプロフィール・リアクション更新を投入。
4. 一覧中央で新着20件を投入し、位置が変わらないことを確認。
5. 60Hz と120Hz端末で同じ操作を実施。
6. X 投稿埋め込みを含む投稿を複数含む100件を、cold cache（未生成 WebView）/ warm cache
   （`XPostSnapshotCache` 温まり済み）で往復フリング。

指標は frame time、janky frame、main-thread CPU、画像デコード回数、GC、ネットワーク再取得回数、
投稿単位の recomposition count とする。合否値は対象端末の Phase 1 前ベースラインを保存した上で、
少なくとも次を満たすこととする。

- 代表シナリオで janky frame を悪化させない。
- warm cache の同一画像再取得を発生させない。
- 無関係なプロフィール・リアクション更新で全可視カードを再 compose しない。
- 画像ロード前後の先頭可視 key / offset を変えない。
- 新着受信だけでは先頭可視 key / offset を変えない。

## 16. 対象外

- カードのスクロール連動フェード、拡縮、移動
- ページ単位スナップ
- 独自物理による大幅な慣性変更
- 一覧内の複数動画同時再生
- 無制限の画像プリフェッチ
- フィード更新のための周期的な全カード再描画
- X 投稿埋め込みの先読み（未可視行での事前 WebView 生成）

## 17. 完了条件

実装は次のすべてを満たした時点で完了とする。

- フィードのドラッグとフリングが Compose 標準経路で動作する（5.1 章で維持を決定した
  トップバー折りたたみの再設計を除く。これは別タスクの完了条件に従う）。
- カード描画中に Nostr / URL / メディア解析を行わない。
- 1件の状態更新が無関係な投稿の UI モデルを変更しない。
- すべての一覧画像がロード前から確定領域を持つ。
- 途中閲覧中の新着はバッジ件数のみで通知され、明示的な操作なしに現在位置を変えない。
- スクロール中の遅延可能処理が停止される。
- X 投稿埋め込みの初回 WebView 生成がスクロール中に開始されず、キャッシュヒット時は
  ロード前から表示高が確定している。
- 自動テストと実機計測結果を変更前ベースラインとともに記録する。

## 18. 実装状況サマリ（2026-09）

| Phase | 状態 |
| --- | --- |
| Phase 1: スクロール経路の単純化 | 完了（対象項目はすべて既存実装で充足済み。初期フェード・トップバー折りたたみは製品要件として維持） |
| Phase 2: 事前変換（フィード内部限定） | 完了 |
| Phase 2b: UI モデル全面分離 | 保留（別タスク。`NoteCard` 共用4画面への影響が本設計のスコープ外） |
| Phase 3: 新着バッジ通知 | 完了（設計を `pendingLiveEvents` から単純化） |
| Phase 4: 画像・プリフェッチ | 一部完了（サムネイルプリフェッチ・X投稿埋め込みの遅延生成は実装済みだが実機未検証。ImageLoader統合は見送り） |
| Phase 5: 計測と調整 | 未着手（実機/エミュレータ・Instruments へのアクセスがこの作業環境にないため） |

このドキュメントの作業（設計レビュー・実装・再レビュー）は Phase 4 の範囲までコード変更を
伴って完了した。Phase 5 は次に実機・エミュレータへアクセスできるセッションで、本章直前の
チェックリストに従って着手すること。
