# フィードクローム 動作維持リファクタ設計

## 1. 目的

フィードのトップバーとボトムナビゲーション（以下、クローム）について、現行の見た目と操作結果を
一切変更せず、`FeedScreen`内に分散している状態と分岐をテスト可能な構造へ移す。

このリファクタ完了後に、別変更として
[`feed-chrome-interaction-design.md`](./feed-chrome-interaction-design.md) の新仕様を実装する。
構造整理と仕様変更を同じ差分へ含めない。

## 2. 非目標

本リファクタでは次を変更しない。

- 同一スワイプとフリングをセッションとして識別する方法
- 慣性中の新しい逆方向操作に対する表示規則
- 先頭到達時の表示条件
- ドラッグ差分の消費順序と消費量
- `60ms`のidle settleと`140ms`のアニメーション時間
- `onPostFling`での確定タイミング
- トップバーとボトムナビゲーションが同じ`collapseFraction`を使う構造
- フィードデータ、ViewModel、LazyColumn、ページング、新着位置保持

pointer監視、`onPreFling`、sessionId、新しいタッチスロップ、独自フリング処理は追加しない。

## 3. 現行動作の固定

現行の`FeedScreen`を正として、次の動作をcharacterization testで固定する。

### 3.1 UserInput

`delta = -available.y`として、現行動作は次のとおり。

| 条件 | 現行結果 |
| --- | --- |
| `delta == 0` | クロームも状態も変更せず、消費しない |
| ジェスチャー最初の非ゼロ入力 | 開始時に完全非表示だったかを記録する |
| `delta > 0` | `isCollapseGestureInProgress = true`にして折りたたむ |
| collapse中に`delta < 0` | クロームを展開せず、クロームとしては消費しない |
| 上記以外の`delta < 0` | クロームを展開する |
| fractionが変化した | 最後の消費差分を保存し、60ms settleを再要求する |
| fractionが境界で変化しない | 最後の差分とsettle要求を更新しない |

スクロールへ返す消費量は、fractionの変化量に`chromeCollapseDistancePx`を掛けた現行値を維持する。

### 3.2 idle settle

- fractionが`0f`または`1f`のときはアニメーションしない。
- 最後のクローム消費差分が正なら`1f`、負なら`0f`へsettleする。
- UserInputでfractionが変化するたびに60msを数え直す。
- 新しいsettle要求は以前の`LaunchedEffect`をキャンセルする現行挙動を維持する。

### 3.3 onPostFling

- `isCollapseGestureInProgress`と`isUserScrollGestureInProgress`をfalseへ戻す。
- ジェスチャー開始時に完全非表示だった場合だけ、絶対先頭での強制表示を許可する。
- 強制表示条件に該当しなければ、最後のクローム消費差分でtargetを決める。
- 現在fractionとtargetが同じならsettleを要求しない。
- 異なる場合は遅延なしのsettleを要求する。

### 3.4 最上部通知

- `isAtTop == true`
- UserInputジェスチャー中ではない
- 現在fractionが完全非表示（`>= 1f`）

以上をすべて満たす場合だけ、遅延なしで表示settleを要求する。途中表示や完全表示では要求しない。

### 3.5 コンテキスト変更

- アクティブなフォロー／グローバルの`LazyListState`切り替え時にジェスチャー中フラグを解除する。
- `authorPubkey != null`またはアクティブリストなしの場合はfractionを`0f`へ戻す。
- フィードタブ再タップ時の既存の先頭移動とfractionリセットを維持する。

## 4. リファクタ後の構造

### 4.1 純粋な単一状態

現在のBooleanをそのまま束ねるのではなく、排他的な組み合わせをenumへ変換し、一つの状態として
保持する。

```kotlin
internal enum class FeedChromeGesturePhase {
    Idle,
    RevealAllowed,
    CollapseLocked,
}

internal enum class FeedChromeStartVisibility {
    Unknown,
    FullyHidden,
    VisibleOrPartial,
}

internal enum class FeedChromeSettleBias {
    Unknown,
    TowardVisible,
    TowardHidden,
}

internal enum class FeedChromeTopRevealPolicy {
    DirectionOnly,
    ForceVisibleAtTop,
}

internal data class FeedChromeBehaviorState(
    val gesturePhase: FeedChromeGesturePhase = FeedChromeGesturePhase.Idle,
    val startVisibility: FeedChromeStartVisibility = FeedChromeStartVisibility.Unknown,
    val settleBias: FeedChromeSettleBias = FeedChromeSettleBias.Unknown,
    val topRevealPolicy: FeedChromeTopRevealPolicy = FeedChromeTopRevealPolicy.DirectionOnly,
)
```

現行値との対応は次のとおり。

| 現行状態 | 新しい表現 |
| --- | --- |
| 両ジェスチャーBooleanがfalse | `gesturePhase = Idle` |
| UserInput中・collapse前 | `gesturePhase = RevealAllowed` |
| UserInput中・collapse開始後 | `gesturePhase = CollapseLocked` |
| `wasChromeHiddenAtGestureStart` | `startVisibility` |
| `lastChromeScrollDelta`の符号 | `settleBias` |
| `forceRevealAtTopForSettle` | `topRevealPolicy` |

`isAtTop`、現在fraction、入力deltaはイベント入力であり、状態へ保存しない。sessionIdなど新仕様だけに
必要な値も、この段階では追加しない。

状態全体の更新はreducerだけが行い、`FeedScreen`から個別フィールドを書き換えない。

### 4.2 純粋な判定結果

```kotlin
internal data class FeedChromeScrollDecision(
    val state: FeedChromeBehaviorState,
    val nextFraction: Float,
    val consumedY: Float,
    val requestSettleAfterMillis: Long?,
)

internal data class FeedChromeSettleDecision(
    val state: FeedChromeBehaviorState,
    val targetFraction: Float?,
    val delayMillis: Long,
)
```

`targetFraction == null`は、現行どおりsettle要求なしを表す。

### 4.3 純粋関数

```kotlin
internal fun reduceFeedChromeUserScroll(
    state: FeedChromeBehaviorState,
    delta: Float,
    currentFraction: Float,
    collapseDistancePx: Int,
): FeedChromeScrollDecision

internal fun reduceFeedChromePostFling(
    state: FeedChromeBehaviorState,
    currentFraction: Float,
    isAtTop: Boolean,
): FeedChromeSettleDecision

internal fun reduceFeedChromeAtTopChanged(
    state: FeedChromeBehaviorState,
    currentFraction: Float,
    isAtTop: Boolean,
): FeedChromeSettleDecision

internal fun reduceFeedChromeContextChanged(
    state: FeedChromeBehaviorState,
): FeedChromeBehaviorState
```

`FeedScreen`はComposeイベントを純粋関数へ渡し、返されたfraction、消費量、settle要求を適用するだけに
する。

## 5. 非同期処理の扱い

動作差を避けるため、最初のリファクタではアニメーション所有構造を変更しない。

- `Animatable`を維持する。
- `chromeSettleRequest`による`LaunchedEffect`再起動を維持する。
- `chromeSettleDelayMillis`を維持する。
- reducerは「何ms後にどのtargetへsettleするか」だけを返す。
- coroutine、Compose State、`rememberUpdatedState`の置き換えは行わない。

単一settleジョブや世代管理への変更は、動作維持リファクタの検証後に別差分で行う。

## 6. 実装手順

### Step 1: characterization test

コードを動かす前に、3章の分岐を現在のヘルパーと新しい純粋関数のテストとして追加する。

### Step 2: target判定の抽出

既存の`feedChromeSettleTarget`を維持し、idle、post-fling、at-topのtargetをテストする。
この時点ではCompose配線を変更しない。

### Step 3: UserInput判定の抽出

`onPreScroll`内の計算を`reduceFeedChromeUserScroll`へ移す。戻り値の`consumedY`が変更前と完全一致する
ことをテストする。

### Step 4: post-fling／at-top判定の抽出

イベントごとに一つずつ純粋関数へ移し、各段階でiOS/Androidのコンパイルと全common testを通す。

### Step 5: 状態の集約

個別の`remember`変数をenumベースの`FeedChromeBehaviorState`一つへ置き換える。現行Booleanの各有効な
組み合わせを上の対応表どおり移し、値の更新タイミングは変えない。

### Step 6: 旧分岐削除

新旧の分岐が並存しないことを確認して、`FeedScreen`内の重複条件を削除する。

## 7. Characterization test一覧

最低限、次をcommon testへ追加する。

### ドラッグ

- 最初の非ゼロ入力で`startVisibility`を一度だけ確定する。
- 末尾方向入力でfractionと消費量が現行式どおり増える。
- `CollapseLocked`での先頭方向入力はfractionを変えず、クロームとして消費しない。
- `RevealAllowed`での先頭方向入力はfractionを減らす。
- fractionが`0f / 1f`境界の場合はsettleを再要求しない。

### settle

- `settleBias`が`TowardHidden`ならhidden、`TowardVisible`ならvisibleを返す。
- 開始時完全非表示かつ終了時絶対先頭ならvisibleを返す。
- 開始時が途中表示／完全表示なら、絶対先頭だけではtargetを上書きしない。
- 現在fractionとtargetが同じならpost-fling settleを要求しない。

### 最上部

- ジェスチャー外・完全非表示・絶対先頭の組み合わせだけvisibleを要求する。
- ジェスチャー中、途中表示、非先頭では要求しない。

### コンテキスト

- タブ切り替え時にジェスチャー中フラグだけを解除する。
- プロフィール用フィードではfractionをvisibleへ戻す。

## 8. 等価性の確認

リファクタ完了条件は次のすべて。

- 全characterization testが成功する。
- iOS Simulator向け全common testが成功する。
- Android mainがコンパイルできる。
- `onPreScroll`が返す消費量がテストケースごとに変更前と一致する。
- settleのtarget、遅延、アニメーション時間が変更前と一致する。
- トップバーとボトムナビゲーションのfractionが常に一致する。
- 実機で短いドラッグ、方向反転、フリング、先頭の小スクロールを比較し、見た目の差がない。

この完了条件を満たすまで、新しい`collapseLocked`、sessionId、慣性中断仕様を導入しない。

## 9. 次段階

動作維持リファクタを独立して完了した後、次の差分で
[`feed-chrome-interaction-design.md`](./feed-chrome-interaction-design.md) を適用する。

その段階では純粋関数の入力と期待targetだけを変更し、Composeイベント配線とアニメーション処理は
原則として触らない。これにより、仕様変更による差分をテスト上でもコードレビュー上でも明確にする。

**状態: 実装済み・自動テスト確認済み（実機比較は未実施）。**
