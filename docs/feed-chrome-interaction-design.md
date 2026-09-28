# フィードクローム分岐設計

## 1. 目的

フィードのトップバーとボトムナビゲーション（以下、クローム）について、次の要件を同時に
満たす。

1. スクロール中、リストの内容は常に指に追従し、クロームの開閉を理由に指と無関係に動かない。
2. 一つのスワイプと、それに続く慣性スクロールを同一セッションとして扱い、その中で先頭方向の
   入力が混ざっても、一度確定した「隠す意図」に反してクロームを再表示しない。
3. リストの先頭付近では、クロームの下に空白を出さない。先頭へ戻るとクロームは表示される。

本設計で「先頭方向」はリストのindex／offsetを小さくする入力、「末尾方向」は大きくする入力を
指す。指の上下や`available.y`の符号は、Composeアダプター内でこの用語へ変換する。

### 1.1 改訂の経緯（2026-09-28）

旧実装はScaffoldの`topBar`とボトムバーの高さを縮めてクロームを隠していた。このため、次の
問題があった。

- クロームのsettleアニメーションがレイアウトの高さを変え、リストの内容を指と無関係に最大で
  クロームの高さ分（約170dp）動かしていた。同一ジェスチャー内で先頭方向へ戻している最中に
  クロームが閉じ切り、内容が逆向きに持っていかれる「弾き返し」の原因だった。
- ドラッグ中でも60ms入力が途切れるとsettleが始まり、指の下で内容が動いた。
- 折りたたみの消費量を112dp基準で計算していた一方、実際のクロームは約170dpで、開閉中の内容が
  指の約1.5倍動いた。

これを解消するため、クロームをリストに重ねて描く構造へ変更した（第2章）。旧版にあった
「絶対先頭で小さく末尾方向へ操作し、入力がクロームの折りたたみだけに消費されてリストが
動かなかった場合は、クロームを戻さない」という要件は、クロームがスクロール量を消費しなくなった
ため前提ごと不要になった。

## 2. 表示構造

### 2.1 クロームを重ねて描く

- トップバー（ステータスバー領域、アプリバー、タブ）はリストの上に重ね、
  `graphicsLayer { translationY }`で上へずらして隠す。高さは変えない。
- ステータスバー領域もクロームに含めて畳む。隠した状態では、投稿がステータスバーの下まで
  表示される。
- ボトムバーは高さを固定し、`translationY`で下へずらして隠す。投稿ボタン（FAB）も同じ量だけ
  下へずらす。
- 透明度は従来どおり`1 - collapseFraction`とする。
- トップバーとボトムバーは同じ`collapseFraction`を使う。
- `collapseFraction`は`FeedChromeState`（`mutableFloatStateOf`）で持ち、トップバーとボトムバーで共有する。
  値はスクロール中に毎フレーム変わるため、コンポーズ中には読まず、`graphicsLayer {}`・`offset {}`の
  ラムダの中でだけ読む。`NoteTimeline`にも`() -> Float`で渡す。これにより開閉中に画面全体が
  再コンポーズされず、1フレームに複数のスクロール入力が届いても最新の値から計算できる。

### 2.2 リストの余白

- リストの`contentPadding`は、上側をトップバーの高さ、下側をボトムバーの高さとする。
  先頭では最初の投稿がトップバーの直下から始まり、末尾では最後の投稿がボトムバーに隠れない。
- フィード画面では、外側のレイアウトがボトムバー分の下余白を取らず、リストがボトムバーの裏まで
  描画される。
- プル更新インジケーターと「新しい投稿」ボタンは、現在見えているトップバーの下端
  （`上側余白 × (1 - collapseFraction)`）の下に表示する。

### 2.3 クロームはスクロール量を消費しない

クロームの開閉は`onPostScroll`で、リストが実際に動いた量（`consumed.y`）だけ行い、
スクロール量は消費しない。したがって、クロームは内容と同じ速さで動き、内容は常に指に追従する。
リストの端などで内容が動かない入力では、クロームも動かない。

折りたたみ距離は、実測したトップバーの高さ（`topBarHeightPx`）とする。

慣性スクロール（`NestedScrollSource.SideEffect`）でも、同じセッションの続きとして`consumed.y`だけ
クロームを動かす（`reduceFeedChromeFlingScroll`）。ラッチと`maxFraction`はドラッグと同じく効く。
慣性中にクロームを止めると、途中の透明度のまま内容の上に重なって薄く残るため。
セッション外（`Idle`）で届いた慣性は無視する。

## 3. 中心となる規則

### 3.1 隠す意図をセッション中ラッチする

同一セッション内で有効な末尾方向入力を一度でも受けたら、`CollapseLocked`とする。
この値はドラッグ終了では消さず、そのドラッグから発生したフリングの終了（`onPostFling`）まで
維持する。

`CollapseLocked`の間は次を行わない。

- 先頭方向入力によるクローム展開
- idle settleによる表示側への反転

ただし、3.3の上限はラッチより優先する。先頭付近で先頭方向へ戻った場合、クロームはリストに
押し下げられて表示される。

### 3.2 指が触れている間はsettleしない

settle（表示／非表示のどちらかへ寄せるアニメーション）は`onPostFling`でのみ開始する。
ドラッグ中に入力が途切れてもsettleしない。新しいスクロール入力が届いたら、実行中のsettleは
直ちにキャンセルする。

`onPostFling`は、速度0で指を離した場合にも呼ばれることをiOSで確認している。

### 3.3 先頭付近の上限

先頭の項目が見えている間は、先頭からのスクロール量`scrolledFromTopPx`（先頭項目の`offset`の
符号反転）から、折りたたみ量の上限を次のとおり決める。

```kotlin
maxFraction = (scrolledFromTopPx / collapseDistancePx).coerceIn(0f, 1f)
```

先頭の項目が見えていなければ上限は`1`、項目がなければ`0`とする。

クロームの`collapseFraction`は常に`maxFraction`以下に保つ。これにより、クロームの下端は
先頭項目より下へ下がらず、先頭付近でも空白が出ない。この制約は次のすべてに適用する。

- ドラッグ中の開閉
- `onPostFling`でのsettle target
- 慣性スクロール、先頭移動、データ差し替えなど、指以外でリストが動いた場合
  （`snapshotFlow`で監視し、上限を超えたら実行中のsettleを止めて上限まで戻す）

絶対先頭では`maxFraction = 0`となり、クロームは必ず表示される。

### 3.4 settle targetの優先順位

`onPostFling`のsettle targetは次の順序で決め、最後に`maxFraction`で上限を掛ける。

1. ジェスチャー開始時に完全非表示で、終了時に絶対先頭: `Visible`
2. 最後にクロームが動いた方向が先頭方向: `Visible`
3. それ以外: `Hidden`

ただし、縦スクロールが一度も起きていないフリング（`gesturePhase == Idle`。タブの横スワイプなど）では
settleしない。前回のセッションの`settleBias`が残っていても、クロームを動かさない。

## 4. 状態

実装済みの状態は次のとおり（`FeedChromeBehavior.kt`）。

```kotlin
internal enum class FeedChromeGesturePhase {
    Idle,
    RevealAllowed,
    CollapseLocked,
}

internal data class FeedChromeBehaviorState(
    val gesturePhase: FeedChromeGesturePhase = FeedChromeGesturePhase.Idle,
    val startVisibility: FeedChromeStartVisibility = FeedChromeStartVisibility.Unknown,
    val settleBias: FeedChromeSettleBias = FeedChromeSettleBias.Unknown,
    val topRevealPolicy: FeedChromeTopRevealPolicy = FeedChromeTopRevealPolicy.DirectionOnly,
)
```

判定は純粋関数で行い、Composeコールバックに分岐を重複させない。

- `reduceFeedChromeUserScroll(state, delta, currentFraction, collapseDistancePx, maxFraction)`
- `reduceFeedChromeFlingScroll(state, delta, currentFraction, collapseDistancePx, maxFraction)`
- `reduceFeedChromePostFling(state, currentFraction, isAtTop, maxFraction)`
- `reduceFeedChromeAtTopChanged(state, currentFraction, isAtTop)`
- `reduceFeedChromeContextChanged(state)`
- `feedChromeMaxFraction(scrolledFromTopPx, collapseDistancePx)`

## 5. 未実装: セッションと世代管理

次は旧版から引き継ぐ設計で、まだ実装していない。現状は`onPostFling`で`Idle`へ戻す方式のため、
慣性中に新しいドラッグを始めた場合の扱いが本章と異なる。

### 5.1 新しい直接入力は新しいセッション

フリング中に`NestedScrollSource.UserInput`が届いた場合は、古いセッションを終了扱いにして、
新しいセッションを開始する。新セッションではラッチをリセットする。

このため、慣性スクロールを新しいスワイプで止めて先頭方向へ動かした場合はクロームを表示できる。
タップして慣性を止めただけで`UserInput`が発生しなければ、表示状態を変更しない。

pointer downの独自監視は導入しない。クリック、長押し、横スワイプ、pull-to-refreshへの干渉を
避け、実際に縦スクロールとして認識された入力だけを新セッション境界に使う。

### 5.2 状態案

```kotlin
internal enum class FeedChromeMotion {
    Idle,
    Dragging,
    Flinging,
}

internal data class FeedChromeInteractionState(
    val sessionId: Long = 0,
    val motion: FeedChromeMotion = FeedChromeMotion.Idle,
    val collapseLocked: Boolean = false,
    val lastDirection: FeedScrollDirection? = null,
)
```

```text
Idle -- UserInput --------------------> 新session / Dragging
Dragging -- 同じUserInput -----------> 同session / Dragging
Dragging -- onPreFling --------------> 同session / Flinging
Flinging -- onPostFling -------------> settle後 Idle
Flinging -- 新しいUserInput ---------> 新session / Dragging
```

### 5.3 フリングの世代管理

`onPreFling`時点の`sessionId`をそのフリングのIDとして保持する。`onPostFling`では、そのIDが現在の
`sessionId`と一致するときだけsettleして`Idle`へ戻す。フリング中に新しい`UserInput`が来ると
`sessionId`が進むため、キャンセルされた旧フリングの`onPostFling`が遅れて届いても、新しい
ドラッグを終了させない。

横スワイプ（タブ切り替え）の`onPostFling`も、縦方向のセッションと区別して無視する。

## 6. 分岐表

| 状態 | 入力／終了条件 | 動作 |
| --- | --- | --- |
| 任意 | 末尾方向にリストが動く | 同じ量だけクロームを隠す。ラッチする |
| ラッチなし | 先頭方向にリストが動く | 同じ量だけクロームを表示する |
| ラッチ済み | 先頭方向にリストが動く | クロームは表示しない。ただし`maxFraction`まで押し下げる |
| 任意 | 指を置いたまま止まる | 何もしない |
| 任意 | `onPostFling` | 3.4のtargetへsettleする。内容は動かない |
| settle中 | 新しいスクロール入力 | settleを止める |
| 任意 | 指以外で先頭付近へ移動 | `maxFraction`まで表示する |
| 任意 | 絶対先頭 | 表示する |
| 任意 | フィードタブ再タップ等の明示的な先頭移動 | 表示する |

## 7. 受け入れ条件

### 内容の追従

- どの操作でも、指を置いている間と離した後に、内容が指と無関係に動かない。
- ドラッグ中はクロームと内容が同じ速さで動く。
- 指を置いたまま止まっても、クロームも内容も動かない。

### 同一セッション

- 末尾方向へスワイプしてクロームを隠し、同じ指のまま先頭方向へ戻しても、先頭付近でない限り
  クロームは表示されない。内容は指に付いて戻る。
- 上記の後に指を離しても、内容は動かない。

### 先頭

- 先頭付近では、クロームの下に空白が出ない。
- 絶対先頭ではクロームが表示される。
- 先頭で小さく末尾方向へ操作して離しても、内容は動かない。

### 未実装（第5章）

- 慣性中に新しく先頭方向へスワイプすると、先頭付近でなくてもクロームを表示できる。
- 新セッション開始後に旧`onPostFling`が届いても、新セッションの状態を変更しない。

## 8. テスト要件

common testでreducerを表形式に検証する（`FeedChromeBehaviorTest`）。

- 末尾方向入力でfractionが増え、ラッチされる。
- ラッチ中の先頭方向入力ではfractionを減らさない。ただし`maxFraction`を超えていれば下げる。
- ラッチなしの先頭方向入力でfractionが減る。
- ドラッグ中の開閉とsettle targetが`maxFraction`を超えない。
- `feedChromeMaxFraction`が先頭からの距離に比例し、先頭が見えなければ`1`になる。
- 開始時完全非表示かつ絶対先頭のpost-flingでは表示へsettleする。

実機ではiOS/Androidの両方で、短いドラッグ、方向が混ざるドラッグ、先頭付近での操作、
長いフリング、慣性を新しいスワイプで中断する操作を確認する。

iOSシミュレータをcliclickで操作する場合、ドラッグ終了（mouse-up）がiOSへ届かないことがある。
「指を離したあとの処理が呼ばれない」ように見えたら、UIKitレベルのtouch endedの有無を
先に確認する。

## 9. 状態

- 第2章（重ねて描く構造）、第3章（規則）: 実装済み。
- 第5章（セッションと世代管理）: 未実装。
