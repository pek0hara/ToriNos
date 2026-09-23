# フィードクローム分岐設計

## 1. 目的

フィードのトップバーとボトムナビゲーション（以下、クローム）について、次の3要件を同時に
満たす。

1. 一つのスワイプと、それに続く慣性スクロールを同一セッションとして扱い、その中で先頭方向の
   入力が混ざっても、一度確定した「隠す意図」に反してクロームを再表示しない。
2. 通常の操作でリストの絶対先頭まで戻ったときはクロームを表示する。
3. 絶対先頭から小さく末尾方向へ操作し、入力がクロームの折りたたみだけに消費されてリストが
   動かなかった場合は、絶対先頭であることを理由にクロームを戻さない。

本設計で「先頭方向」はリストのindex／offsetを小さくする入力、「末尾方向」は大きくする入力を
指す。指の上下や`available.y`の符号は、Composeアダプター内でこの用語へ変換する。

## 2. 中心となる規則

### 2.1 隠す意図をセッション中ラッチする

同一セッション内で有効な末尾方向入力を一度でも受けたら、`collapseLocked = true` とする。
この値はドラッグ終了では消さず、そのドラッグから発生したフリングの終了まで維持する。

`collapseLocked` が真の間は次を行わない。

- 先頭方向入力によるクローム展開
- 絶対先頭到達による強制表示
- idle settleによる表示側への反転

これにより、同一スワイプ中の方向揺れや、フリング境界付近の逆方向差分でクロームが出ることを
防ぐ。

### 2.2 新しい直接入力は新しいセッション

フリング中に`NestedScrollSource.UserInput`が届いた場合は、古いセッションを終了扱いにして、
新しいセッションを開始する。新セッションでは`collapseLocked`をリセットする。

このため、慣性スクロールを新しいスワイプで止めて先頭方向へ動かした場合はクロームを表示できる。
タップして慣性を止めただけで`UserInput`が発生しなければ、表示状態を変更しない。

pointer downの独自監視は導入しない。クリック、長押し、横スワイプ、pull-to-refreshへの干渉を
避け、実際に縦スクロールとして認識された入力だけを新セッション境界に使う。

### 2.3 絶対先頭の優先順位

絶対先頭は常に最優先ではない。settle時は次の順序でtargetを決める。

1. 明示的な先頭移動要求: `Visible`
2. 現セッションが`collapseLocked`: `Hidden`
3. リストが絶対先頭: `Visible`
4. 最後の方向が先頭方向: `Visible`
5. それ以外: `Hidden`

規則2が規則3より先にあることが、先頭での小スクロールが跳ね戻らないための要点である。

## 3. 状態

```kotlin
internal enum class FeedChromeMotion {
    Idle,
    Dragging,
    Flinging,
}

internal enum class FeedScrollDirection {
    TowardTop,
    TowardEnd,
}

internal data class FeedChromeInteractionState(
    val sessionId: Long = 0,
    val motion: FeedChromeMotion = FeedChromeMotion.Idle,
    val collapseLocked: Boolean = false,
    val lastDirection: FeedScrollDirection? = null,
    val settleGeneration: Long = 0,
)
```

開始時のクローム表示量、`isUserScrollGestureInProgress`、`forceRevealAtTop`などの独立Booleanは
持たない。必要な分岐はセッション、`collapseLocked`、現在位置、最後の方向だけで表現する。

## 4. 状態遷移

```text
Idle -- UserInput --------------------> 新session / Dragging
Dragging -- 同じUserInput -----------> 同session / Dragging
Dragging -- onPreFling --------------> 同session / Flinging
Flinging -- onPostFling -------------> settle後 Idle
Flinging -- 新しいUserInput ---------> 新session / Dragging
```

入力処理は次のとおり。

```kotlin
fun onUserScroll(direction: FeedScrollDirection) {
    if (motion == Idle || motion == Flinging) {
        startNewSession()
    }

    motion = Dragging
    lastDirection = direction
    if (direction == FeedScrollDirection.TowardEnd) {
        collapseLocked = true
    }
}
```

`collapseLocked`が真になった後の先頭方向入力は、リストへは通常どおり渡すが、クロームの展開には
使わない。したがってリスト自体は先頭へ戻れても、同一セッション内ではクロームを表示しない。

## 5. フリングの世代管理

`onPreFling`時点の`sessionId`をそのフリングのIDとして保持する。`onPostFling`では、そのIDが現在の
`sessionId`と一致するときだけsettleして`Idle`へ戻す。

フリング中に新しい`UserInput`が来ると`sessionId`が進むため、キャンセルされた旧フリングの
`onPostFling`が遅れて届いても、新しいドラッグを終了させない。

クロームのsettleも`settleGeneration`を持ち、新しい入力ごとに以前のsettleジョブをキャンセルする。
アニメーションの書き手は一つに限定する。

## 6. Composeとの境界

`FeedScreen`は次だけを担当する。

- `onPreScroll(UserInput)`の縦方向を`TowardTop / TowardEnd`へ変換する。
- reducerがクローム更新を許可した場合だけ`collapseFraction`をドラッグ量で更新する。
- `onPreFling / onPostFling`を状態遷移へ渡す。
- `LazyListState`から絶対先頭を読み、settle targetの入力にする。
- 単一のsettleジョブを開始・キャンセルする。

状態判断は純粋な`FeedChromeInteractionReducer`へ分離し、Composeコールバック内に分岐を重複させない。
操作状態は描画対象ではないため、Composeの監視Stateにせず、スクロールごとの余計な再コンポーズを
発生させない。

## 7. 分岐表

| セッション状態 | 入力／終了条件 | target／動作 |
| --- | --- | --- |
| Idle | 末尾方向の新入力 | 新session、`collapseLocked = true`、折りたたむ |
| Dragging・lock済み | 先頭方向入力が混ざる | リストへ渡すがクロームは展開しない |
| Flinging・lock済み | 同セッションのフリング終了時に絶対先頭 | `Hidden` |
| Flinging | 新しい先頭方向`UserInput` | 新session、lock解除、クローム展開を許可 |
| Idle/Dragging・lockなし | 絶対先頭でsettle | `Visible` |
| 先頭・表示中 | 小さな末尾方向入力でリスト位置不変 | `collapseLocked`が優先され`Hidden` |
| 任意 | 明示的な先頭移動 | `Visible` |

## 8. 受け入れ条件

### 同一セッション

- 末尾方向へスワイプしてクロームを隠し、同じ指のまま先頭方向の差分が混ざっても表示されない。
- 上記スワイプから発生した慣性の終了時にリストが絶対先頭でも表示されない。
- 絶対先頭で小さく末尾方向へ操作し、リストが1pxも動かなくてもクロームが戻らない。

### 新しいセッション

- 慣性中に新しく先頭方向へスワイプすると、絶対先頭未到達でもクロームを表示できる。
- 慣性をタップで止めるだけではクロームを強制表示しない。
- 新セッション開始後に旧`onPostFling`が届いても、新セッションの状態を変更しない。

### 先頭

- `collapseLocked`でない操作で絶対先頭へ到達するとクロームが表示される。
- フィードタブ再タップ等の明示的な先頭移動では常に表示される。
- フォロー／グローバルのタブ切り替えでも、各タブのセッションを混同しない。

## 9. テスト要件

common testでreducerを表形式に検証する。

- `collapseLocked`は同一ドラッグ内の方向反転で解除されない。
- `collapseLocked`は同一ドラッグ由来のフリング終了まで維持される。
- lock済みかつ絶対先頭では`Hidden`を返す。
- lockなし・絶対先頭では`Visible`を返す。
- Flinging中の新`UserInput`がsessionIdを更新しlockをリセットする。
- 古いsessionIdの`onPostFling`を無視する。
- 明示的な先頭移動がlockより優先される。
- 新入力が古いsettle世代を無効化する。

実機ではiOS/Androidの両方で、短いドラッグ、長いフリング、方向が混ざるドラッグ、慣性を新しい
スワイプで中断する操作を確認する。

## 10. 実装範囲

次回実装では、現行の次の一時状態をreducerへ置き換える。

- `isCollapseGestureInProgress`
- `isUserScrollGestureInProgress`
- `wasChromeHiddenAtGestureStart`
- `forceRevealAtTopForSettle`
- `lastChromeScrollDelta`による分散したtarget判定

フィードデータ、ViewModel、購読、ページング、投稿カード、クロームの表示時間と移動距離は変更しない。

**状態: 未実装。**
