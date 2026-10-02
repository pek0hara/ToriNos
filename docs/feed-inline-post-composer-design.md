# フィード簡易投稿UI設計

## 1. 目的

フィードの投稿導線を、現在の「＋を押すと全画面の `PostSheet` を開く」動作から、
ポスト詳細画面の返信欄に近い下部インライン入力へ変更する。

- 「フッターから投稿」がオンなら、フィードの＋でボトムナビゲーションと入れ替えて簡易投稿欄を表示する。
  表示中のFABから投稿シートへ展開し、簡易欄左端の▼で入力を保持したままフッターメニューへ戻る。
- 簡易投稿欄のFABから投稿シートへ展開できる。
- 展開ボタンを押すと、入力内容を保ったまま現行の `PostSheet` を開く。
- 簡易投稿と `PostSheet` で、署名、送信、エラー表示、投稿完了処理を共有する。
- ポスト詳細の返信欄とフィードの簡易投稿欄は、同じ入力コンポーネントを使う。
- 入力欄がフォーカスを失ったら、共通コンポーネントがソフトウェアキーボードを閉じる。

本書では上向き三角形のボタンを「展開ボタン」、フィード下部に出す入力欄を
「簡易コンポーザー」と呼ぶ。

## 2. 現状

### 2.1 フィードからの新規投稿

`AppSessionCoordinator` のフィード用FABは、＋押下時に秘密鍵の有無を確認し、
`ComposerCoordinator.showPostSheet = true` とする。`ComposerHost` はこれを受けて
`PostSheet` を表示する。

`PostSheet` は `post-composer` キーの `PostViewModel` を使い、本文、添付画像、
カスタム絵文字、Content Warning、投稿中状態、送信結果、下書きを管理する。

### 2.2 ポスト詳細の返信

`ThreadScreen` は `Scaffold.bottomBar` に `AppMessageComposer` を置く。構成は
1〜4行の入力欄と送信ボタンで、入力中もポスト一覧を確認できる。

返信欄の状態と送信処理は `ThreadViewModel` / `ThreadController` が所有しており、
フィードの新規投稿へそのまま流用はできない。見た目は `AppMessageComposer` を共有し、
投稿状態と送信処理は既存の `PostViewModel` を使う。

### 2.3 フィード下部クローム

フィードのボトムナビゲーションとFABは一覧に重ねて描画され、スクロール時は
`FeedChromeState.collapseFraction` に応じて下へ移動する。リスト末尾の余白は
`FeedScreen.bottomContentPadding` で確保している。

簡易コンポーザーを同じ下部領域へ追加するため、表示中の高さとクロームの収納動作を
明示的に扱う必要がある。

## 3. 対象範囲

### 3.1 対象

- フィードの＋から始める通常のkind 1新規投稿
- 簡易コンポーザーからのテキスト投稿
- 簡易コンポーザーから現行 `PostSheet` への展開
- 入力内容、投稿中状態、エラー、投稿結果の引き継ぎ
- キーボード、戻る操作、画面遷移、フィードクロームとの協調
- iOS、Android、Webで共通のCompose UI

### 3.2 非対象

- ポスト詳細の返信送信処理を `PostViewModel` へ統合すること
- 返信、引用、記事コメント、チャンネル返信の起動UI変更
- `PostSheet` 内の画像、下書き、絵文字、リレー設定、Content Warningの機能変更
- フィード以外のFAB変更
- 複数の簡易投稿下書きを保持すること

## 4. UX仕様

### 4.1 閉じた状態

現状どおりフィード右下に＋のFABを表示する。

＋押下時は先に秘密鍵の有無を確認する。秘密鍵が未設定なら既存の鍵設定画面を開き、
設定完了後に簡易コンポーザーを表示する。投稿UIが開く前に空の新規投稿状態へ初期化する。

秘密鍵が未設定の場合は新規投稿の再開要求を保存し、投稿UIの表示状態は `Hidden` のまま
鍵設定画面を開く。

鍵設定が完了するとアカウント状態が `Anonymous` から `Active` へ切り替わる。`App.kt` の分岐が変わるため、
`AppSessionCoordinator` と、その中で `remember` している `ComposerCoordinator`、ナビゲーション状態、
匿名セッションの `PostViewModel` はすべて作り直される。したがって `ComposerCoordinator.pendingKeyAction`
に保存した値は完了後に参照できない（現状も `onSetupComplete = {}` で、完了後には何も実行していない）。

新規投稿の再開要求は、アカウントセッションより外側の `App` 階層に置く `PendingComposerRequestHolder`
で保持する。`ComposerCoordinator` はコンストラクタでこのホルダーを受け取る。

- ＋押下時に秘密鍵がなければ、holderへ `NewPost` を書き込んでから鍵設定画面を開く。
- 鍵設定をキャンセルした場合（`dismissKeySetup`）は、holderも破棄する。簡易コンポーザーは開かない。
- `KeySetupScreen.onSetupComplete` では再開処理をしない。セッション切り替え後に作られた新しい
  `AppSessionCoordinator` が、`accountSession != null` の初回Compositionでholderから要求を取り出し、
  同時に消去する。
- 取り出した要求が `NewPost` なら、次の順序で簡易コンポーザーを開く。

1. 鍵設定画面が閉じていることを確認する（新しいCoordinatorでは `showKeySetup = false` が初期値）。
2. 返信、引用、ローカル下書きのコンテキストを消す。
3. 共有 `PostViewModel` を `reset()` する。
4. 現在のルートがフィードであることを確認し、`presentation = FeedInline` にする。

取り出した時点でholderを消去するため、再コンポーズやセッションの再生成で同じ要求を二度実行しない。
`NewPost` 以外のpending actionを完了後に再開することは本変更の対象外とし、従来どおり破棄する。

### 4.2 簡易コンポーザー

ボトムナビゲーションの位置に、ボトムナビゲーションと入れ替えて、ポスト詳細の返信欄と同じSurface、余白、
入力欄、送信ボタンを使って表示する。FABは＋から「…」（`MoreHoriz`、contentDescriptionは「メニューを表示」）に
変わる。

```text
閉じた状態                              簡易コンポーザー表示中
┌──────────────────────────┐          ┌──────────────────────────┐
│ フィード                [＋] │  ＋ →    │ フィード                […] │
├──────────────────────────┤  ← …     ├──────────────────────────┤
│ ボトムナビゲーション          │          │ △ │ 今何してる？   │ 送信 │
└──────────────────────────┘          │   エラーがある場合はこの行  │
                                        └──────────────────────────┘
```

「…」を押すと簡易コンポーザーを隠してボトムナビゲーションを戻し、FABは＋に戻る。入力（本文とエラー）は
保持し、次に＋を押すと同じ入力のまま簡易コンポーザーを開く（`reset()` しない）。送信中でも切り替えられ、
完了は `ComposerHost` が処理する。切り替え後に送信が失敗した場合は、簡易欄が見えないのでエラーを
Snackbarで知らせる。

| 要素 | 仕様 |
| --- | --- |
| 展開ボタン | 一番左に40dp以上のタップ領域で配置する。アイコンは上向き三角形または `KeyboardArrowUp`、contentDescriptionは「詳細な投稿画面を開く」とする。Web版（`isWebPlatform`）では表示せず、入力欄を左端から配置する |
| 入力欄 | プレースホルダーは「今何してる？」、1〜4行、表示直後にフォーカスしてキーボードを開く。ただしWebは既存方針どおり自動フォーカスしない。本文はシートと同じ最大800字（`MAX_POST_CHARS`）とし、超える入力は受け付けない |
| 送信ボタン | `PostState.canPost` がfalseの間は無効（本文が空、投稿中、アップロード中）。判定を画面側で再実装しない。contentDescriptionは「ポスト」 |
| 送信中 | 入力欄は `readOnly` にしてフォーカスとキーボードを維持する。`enabled = false` にはしない（フォーカスを失い、キーボードが閉じるため。6.5節） |
| エラー | 入力行の下に `PostState.error` を表示し、入力変更または再送で既存ロジックどおり解除する |
| フォーカス | 入力欄が一度フォーカスされた後にフォーカスを失ったら、フォーカスの移動先にかかわらずキーボードを閉じる |

簡易コンポーザーには画像、絵文字、Content Warning、下書き一覧、リレー選択を置かない。
これらが必要な場合は展開ボタンから `PostSheet` を使う。

Web版では展開ボタンを表示しないため、フィードからの新規投稿はテキストのみとなり、画像、絵文字ピッカー、
Content Warning、下書き一覧、リレー選択は利用できない。これは意図した仕様とする。Web版でも本文の
`:shortcode:` 手入力によるカスタム絵文字は4.6節どおり解決される。返信・引用など、既存導線から開く
`PostSheet` はWeb版でも従来どおり利用できる。

### 4.3 展開

展開ボタンを押すと、同じ `PostViewModel` を渡して `PostSheet` を開く。本節はWeb版以外に適用する。

- 本文を維持し、シート側のカーソルは本文末尾に置く。現状の `ComposerBodyEditor` は
  `TextFieldValue(state.text)` で初期化するためカーソルが先頭になる。初期選択位置を本文末尾にする修正を含める。
- `PostSheet` を開く際に `PostViewModel.reset()` を呼ばない。
- 簡易コンポーザーは背面に残さず、表示状態を `FullScreen` へ切り替える。
- `PostSheet` の画像、絵文字、下書き、リレー設定などは現行どおり利用できる。
- シートのキャンセル、下書き保存、破棄の確認は現行仕様を維持する。
- 展開後のシートで下書き一覧から別の下書きを選んだ場合は、現行どおり `restoreMemo()` する。
- 送信中に展開した場合、シートは同じ `isPosting` を表示し、完了は `ComposerHost` が処理する。

簡易コンポーザーから展開した後に `PostSheet` を閉じた場合は投稿UIを閉じた状態へ戻す。
簡易表示へ自動的には戻さない。本文がある場合は現行の下書き保存／破棄確認が働くため、
意図せず本文だけが消える経路を作らない。

### 4.4 簡易送信

送信ボタンは `PostViewModel.post` を次の引数で呼ぶ。

```kotlin
postViewModel.post(
    replyTarget = null,
    noteContext = NoteContext.Timeline,
    quoteReference = null,
    relayUrls = RelayStore.writableRelayUrlsSnapshot(),
)
```

成功時は入力状態をクリアして簡易コンポーザーを閉じ、既存と同じSnackbarを表示する。
一部リレー失敗時の失敗リレー表示も既存処理を使う。失敗時は簡易コンポーザーを開いたまま、
本文とエラーを保持する。

送信中（`isPosting == true`）は次のとおり扱う。

- Backでは簡易コンポーザーを閉じない。キーボード表示中なら、キーボードだけを閉じる。
- 別画面への遷移で閉じた場合も `reset()` はせず、送信結果を失わない。詳細は6.3節。

### 4.5 戻る操作と画面遷移

- キーボード表示中のシステムBackは、まずキーボードを閉じる。
- 入力欄以外へフォーカスが移った場合は、共通入力コンポーネントがキーボードを閉じる。
- 展開ボタンは、入力欄のフォーカスを明示的に解除してキーボードを閉じてから `PostSheet` を開く。
- キーボードが閉じた状態のBackは「…」と同じく、入力を保持してフッターメニューへ戻す（Androidのみ）。
- フィード以外へ遷移した場合は簡易コンポーザーを閉じ、新規投稿状態を破棄する。「…」で保持中の入力も破棄する。
  送信中は閉じるだけで `reset()` しない（4.4節）。
- アカウント切り替え時も閉じて破棄し、別アカウントへ本文を持ち越さない。
- フィードタブ（フォロー／グローバル）の切り替えでは閉じず、本文を維持する。

Backや画面遷移で未送信本文を保存する機能は本変更には含めない。永続化したい場合は、展開して
既存の下書き保存を使う。

### 4.6 シート投稿との同一性

簡易送信と `PostSheet` からの新規投稿は、同じ入力なら同じNostrイベントと同じ完了処理になることを保証する。
差が出ないよう、次の規則は画面ごとに書かず共通の層で持つ。

| 項目 | 共通化の方法 |
| --- | --- |
| kind、本文の組み立てとtrim、カスタム絵文字／`q`／`p`／`imeta`／`client` タグ | 同じ `PostViewModel.post()` と `composeNoteContent()` を使う |
| 文字数上限（800字） | 入力欄は `ComposerBodyEditor` と `AppMessageComposer` の両方で `MAX_POST_CHARS` を超える入力を拒否する。`post()` でも超過時は送信せずエラーにし、最後の防御とする |
| 送信可否 | `PostState.canPost` だけで判定する |
| 送信先リレー | 新規投稿ではどちらも `RelayStore.writableRelayUrlsSnapshot()`。シートでリレーを選んだ場合だけ、その選択を使う |
| カスタム絵文字 | 手入力の `:shortcode:` は `updateText()` が登録済み絵文字から解決する。簡易欄は絵文字ピッカーを持たない。将来追加する場合は `onCustomEmojiInserted()` を経由する |
| 完了処理 | `ComposerHost` の単一の完了監視と `onPosted` を使う（6.3節） |

送信先リレーが0件のとき、`post()` は「送信先リレーを1つ以上選択してください」を返す。簡易欄にはリレー選択が
ないため、簡易送信で書き込み可能リレーが0件の場合は「書き込み可能なリレーがありません。リレー設定を確認して
ください」を表示する。送信しない点と本文を残す点はシートと同じとする。

## 5. 表示状態

`showPostSheet` と新しいBooleanを並べると「簡易とシートが同時に開く」不正状態を表現できるため、
投稿UIの表示状態を排他的にする。

```kotlin
internal enum class ComposerPresentation {
    Hidden,
    FeedInline,
    FullScreen,
}
```

`ComposerCoordinator` は `presentation` を唯一の表示状態として所有する。返信・引用など既存導線は
直接 `FullScreen` を選ぶ。

| 現在 | 操作 | 次の状態 | 投稿状態 |
| --- | --- | --- | --- |
| `Hidden` | フィードの＋（`localDraft` なし） | `FeedInline` | `reset()`して新規開始 |
| `Hidden` | フィードの＋（`localDraft` あり） | `FullScreen` | `RestoreMemo` で下書きを復元（現行の＋と同じ） |
| `FeedInline` | 本文変更 | `FeedInline` | 同じ `PostViewModel` を更新 |
| `FeedInline` | △（Web版以外） | `FullScreen` | 維持 |
| `FeedInline` | 送信成功 | `Hidden` | 投稿完了を消費後に空へ |
| `FeedInline` | 送信失敗 | `FeedInline` | 本文とエラーを維持 |
| `FeedInline` | FABの「…」／Back | `Hidden` | 維持（`hasHeldInlineDraft = true`）。送信中も可 |
| `Hidden`（保持中） | フィードの＋ | `FeedInline` | 維持。`reset()` しない |
| `Hidden`（保持中） | 返信／引用 | `FullScreen` | `Reset`。保持は終了し、入力は破棄 |
| `Hidden`（保持中） | 別画面へ遷移 | `Hidden` | 破棄（送信中は `reset()` しない） |
| `FeedInline` | 別画面へ遷移（送信中でない） | `Hidden` | 破棄 |
| `FeedInline` | 別画面へ遷移（送信中） | `Hidden` | `reset()` しない。完了はホストが処理 |
| `FullScreen` | キャンセル／保存／破棄 | `Hidden` | 現行 `PostSheet` の規則 |
| `Hidden` | 返信／引用 | `FullScreen` | 対象コンテキストで初期化 |
| `Hidden` | ＋、秘密鍵なし | `Hidden` | App階層のholderへ `NewPost` を保存して鍵設定を表示 |
| `Hidden` | 上記の鍵設定完了（セッション再生成後） | `FeedInline` | holderから一度だけ取り出し、`reset()`して新規開始 |
| `Hidden` | 上記の鍵設定キャンセル | `Hidden` | holderを破棄 |

`replyTarget`、`quoteToId` などのコンテキストは現在どおり `ComposerCoordinator` が所有する。
`FeedInline` へ入る前に必ず `clearPostContext(clearDraft = true)` を行い、簡易送信が以前の返信や
引用として送られないことを保証する。

ただし `localDraft` がある場合は `FeedInline` へ入らない。`localDraft` は、シートからカスタム絵文字設定へ
移動したときに退避した本文である。現行の＋は `localDraft` を消さずにシートで復元しているため、＋では
`FullScreen` + `RestoreMemo` を選び、この復元経路を維持する。簡易欄から展開したシートで絵文字設定へ
移動した場合も、同じ経路で本文が戻る。

## 6. 状態所有とコンポーネント構成

### 6.1 `PostViewModel` を一つだけ使う

`AppSessionCoordinator` の安定した階層で、アカウントスコープの `PostViewModel` を
`post-composer` キーにより一度取得する。簡易コンポーザーと `PostSheet` の両方へ同じインスタンスを
渡す。

```text
AppSessionCoordinator
├─ ComposerCoordinator             表示形態と返信／引用コンテキスト
├─ PostViewModel                   本文、添付、送信、エラー、送信結果
├─ AppScaffold.bottomBar
│  ├─ FeedInlinePostComposer       FeedInline時のみ
│  └─ NavigationBar
└─ ComposerHost
   └─ PostSheet                    FullScreen時のみ。同じPostViewModel
```

別の `PostViewModel` や簡易投稿専用Publisherは追加しない。Nostrイベント生成、カスタム絵文字タグ、
`client` タグ、送信先、投稿完了結果が導線によってずれるのを防ぐためである。

### 6.2 `PostSheet` の初期化契約

現状の `PostSheet` は表示時の `LaunchedEffect` で `reset()` または `restoreMemo()` を実行する。
そのままでは簡易入力から展開した本文が消えるため、初期化方法を引数で明示する。

```kotlin
enum class PostSheetInitialState {
    Reset,
    KeepCurrent,
    RestoreMemo,
}
```

- 通常の返信・引用・新規シート起動は `Reset`。
- 簡易コンポーザーの△から開く場合は `KeepCurrent`。
- ローカル下書きまたは下書き一覧から開く場合は `RestoreMemo`。

`Reset` と `RestoreMemo` は、どちらも `initialMemo` の有無で復元か初期化かが決まる（現行の
`PostSheet` の規則）。違いは呼び出し側の意図を表すことだけで、`KeepCurrent` だけが初期化を飛ばす。

`KeepCurrent` では `reset()` も `restoreMemo()` も行わない。状態初期化を
Composableの再コンポーズ回数へ依存させず、状態遷移時に一度だけ行う。

`initialState` が決めるのはシートを開いたときの初期化だけである。シート内で下書き一覧から `selectedDraft` を
選んだ場合は、`initialState` にかかわらず現行どおり `restoreMemo()` する。

`KeepCurrent` で開いたときは、`ComposerBodyEditor` の入力欄のカーソルを本文末尾に置く（4.3節）。

### 6.3 投稿完了の監視

現在は `PostSheet` 内の `LaunchedEffect` が `PostState.posted` を監視している。簡易送信でも同じ結果を
扱えるよう、監視と `clearPosted()` はシートの外側にある `ComposerHost` へ移す。

これにより、簡易／全画面のどちらから投稿しても `onPosted` は一度だけ呼ばれる。
投稿完了イベントをUIごとに監視して二重Snackbarや二重ナビゲーションを起こさない。

ただし、下書き一覧から選んだ返信先と `NoteContext` は `PostSheet` のローカル状態であり、
`ComposerHost` から復元してはならない。`PostViewModel.post()` が実際に受け取った送信時コンテキストを、
投稿結果と同じオブジェクトへ確定値として保存する。

```kotlin
data class PostCompletion(
    val eventId: String,
    val replyToId: String?,
    val noteContext: NoteContext,
    val publishResult: RelayPublishResult,
    val warning: String?,
    /** 送信を始めた入力状態が完了時点でも現在の入力状態か。falseなら投稿UIを閉じない。 */
    val fromCurrentDraft: Boolean = true,
)

data class PostState(
    // 本文や処理中状態は省略
    val completion: PostCompletion? = null,
    /** 送信中に入力状態が破棄された後の失敗通知。 */
    val staleFailure: String? = null,
)
```

`PostViewModel.post()` は署名・送信に成功した時点で、引数の `replyTarget?.parent?.id` と
`noteContext` を `PostCompletion` に格納する。このため、通常投稿、返信、チャンネル返信、
下書き一覧から復元した返信のいずれでも、完了後の遷移先をホストが正しく判断できる。

`ComposerHost` は `completion != null` を監視し、次の順序で一度だけ処理する。

1. 完了結果をローカル変数へ取り出す。
2. `PostViewModel.consumeCompletion()` で `completion` をnullにする。
3. 投稿UIを `Hidden` にする。
4. `PostCompletion` 全体を `onPosted` へ渡す。

手順3は `fromCurrentDraft == true` のときだけ行う。送信中に入力状態が破棄され、いまの投稿UIが別の入力である
場合（簡易欄を閉じて＋を押し直した等）は、いまのUIを閉じず、通知と遷移だけを行う。
`reset()` と `restoreMemo()` は、未消費の `completion` と `staleFailure` を保持する。

既存の `posted`、`postedEventId`、`publishResult`、`postWarning`、`draftDeleted` は、移行後に
`PostCompletion` と重複して保持しない（`draftDeleted` は現状どこからも参照されていない）。複数フィールドの更新途中を監視する競合と、
シートのローカル状態をホストが推測する構造をなくす。

#### 送信中に閉じた場合

現状の `post()` は、完了時に `_state.value = PostState(...)` で状態を丸ごと置き換える。送信中に閉じて
`reset()` し、新しい本文を入力していると、古い送信の結果でその本文が消える。失敗時は新しい状態に古い
エラーが載る。簡易欄は画面遷移で閉じやすく、シートも送信中にキャンセルできるため、共通で次のようにする。

- `post()` は開始時の `draftGeneration` を記録する。
- 完了時に世代が変わっていなければ、現行どおり入力状態をクリアして `completion` を設定する。
- 世代が変わっている場合の成功: 入力状態は変更せず `completion` だけを設定する。ホストは通常どおり
  Snackbarを表示する。返信の場合のスレッド遷移も同じ規則で行う。
- 世代が変わっている場合の失敗: 入力状態へ `error` を書き込まない。一回限りの失敗通知
  （`PostState.staleFailure`）を公開し、ホストがSnackbarで表示して消費する。
- `isPosting` は、記録した世代が現在の世代と一致するときだけfalseへ戻す。`reset()` 後の新しい状態は
  `isPosting = false` から始まる。

### 6.4 返信と投稿で共有する `AppMessageComposer`

ポスト詳細の返信欄とフィードの簡易投稿欄は、どちらも `AppMessageComposer` を直接使用する。
テキストフィールド、Surface、内外余白、エラー、送信ボタン、フォーカス処理を画面ごとに
再実装しない。

画面固有の責務は次に限定する。

| 呼び出し側 | 固有の設定・処理 |
| --- | --- |
| ポスト詳細 | 「返信を追加…」のプレースホルダー、返信ViewModelへの本文変更と送信 |
| フィード | 「今何してる？」のプレースホルダー、先頭の展開ボタン（Web版以外）、`PostViewModel`への本文変更と送信 |

この共通化のため、`AppMessageComposer` に次の省略可能な引数を追加する。

```kotlin
leadingContent: (@Composable () -> Unit)? = null
applyNavigationBarsPadding: Boolean = true
sendContentDescription: String = "送信"
onFocusChanged: ((Boolean) -> Unit)? = null
dismissKeyboardOnFocusLoss: Boolean = false
canSend: Boolean? = null
maxLength: Int? = null
autoFocus: Boolean = false
```

- `autoFocus = true` のとき、表示の一フレーム後に入力欄へフォーカスしてキーボードを開く。フィードはWeb以外で指定する。
- フィードはWeb版以外で `leadingContent` に展開ボタンを渡す。Web版では `null` を渡し、ポスト詳細の返信欄と同じ並びにする。
- ポスト詳細は先頭コンテンツと下部Insetの既定値を維持し、現行の見た目を変えない。
- フィードでは下にボトムナビゲーションがあるため `applyNavigationBarsPadding = false` とする。
- フィードは `onInsertEmoji` を渡さない。絵文字は展開後のシートで使う。
- 将来 `onInsertEmoji` と `leadingContent` を同時に渡す呼び出し側ができた場合は、`leadingContent`、絵文字、入力欄、送信の順とする。
- `canSend` を渡した場合は、送信ボタンの有効判定に `text.isNotBlank()` ではなく `canSend` を使う。フィードは `state.canPost` を渡す。
- `maxLength` を渡した場合は、超える入力を `onValueChange` で拒否する。フィードは `MAX_POST_CHARS` を渡す。
  拒否は入力欄のローカル状態で行う。ViewModel側だけで拒否すると、`LaunchedEffect(text)` が再同期せず、
  入力欄に超過分が残るため。
- `dismissKeyboardOnFocusLoss = true` の場合、送信中は入力欄を `enabled = false` ではなく `readOnly = true` にする
  （6.5節）。既定値falseのチャンネル投稿欄は、現行どおり送信中に無効化する。
- フィード簡易投稿欄とポスト詳細の返信欄は `dismissKeyboardOnFocusLoss = true` とする。
- チャンネル投稿欄は本変更の対象外なので既定値falseを維持し、現行のフォーカス挙動を変えない。

`FeedInlinePostComposer` を作る場合も、状態とコールバックを `AppMessageComposer` へ渡すだけの薄い
バインディングにする。独自の `OutlinedTextField` や送信ボタンは持たせない。

### 6.5 フォーカス喪失時のキーボード制御

フォーカス制御も `AppMessageComposer` に集約する。入力欄の `Modifier.onFocusChanged` で状態を監視し、
`dismissKeyboardOnFocusLoss == true` かつ、一度 `isFocused == true` になった後に `false` へ
変わった場合だけキーボードを閉じる。

キーボードを閉じる処理は `LocalSoftwareKeyboardController.current?.hide()` だけにしない。
既存の `rememberDismissKeyboard()` が使用する `dismissPlatformKeyboard()` はiOSの入力セッションを
確実に終えるために必要なので、`Keyboard.kt` にフォーカスを変更しない共通処理を追加する。

```kotlin
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(keyboardController) {
        {
            keyboardController?.hide()
            dismissPlatformKeyboard()
        }
    }
}
```

`rememberDismissKeyboard()` は、フォーカス解除後にこのキーボード終了処理と同等の処理を行う
既存契約を維持する。

初回Composition時にも未フォーカス状態が通知される可能性があるため、単に
`isFocused == false` だけで `hide()` を呼ばず、直前まで入力欄がフォーカスされていたことを条件にする。

```kotlin
var wasTextFocused by remember { mutableStateOf(false) }
val hideKeyboard = rememberHideKeyboard()

Modifier.onFocusChanged { state ->
    if (dismissKeyboardOnFocusLoss && wasTextFocused && !state.isFocused) {
        hideKeyboard()
    }
    wasTextFocused = state.isFocused
    onFocusChanged?.invoke(state.isFocused)
}
```

展開ボタンでは `FocusManager.clearFocus(force = true)` を先に呼ぶ。これによりフォーカス喪失処理を
通してキーボードを閉じ、その後に全画面UIへ切り替える。画面破棄時の
`DismissKeyboardOnLeave` はフォールバックとして残す。

送信ボタンのタップだけでは入力欄のフォーカスを強制解除しない。送信失敗時にそのまま修正できる
ようキーボードを維持し、送信成功時は簡易コンポーザーが閉じることでキーボードも閉じる。

現行の `AppMessageComposer` は送信中に `enabled = false` で入力欄を無効化する。無効化された入力欄は
フォーカスを失うため、そのままでは上のフォーカス喪失処理が働き、送信のたびにキーボードが閉じる。
このため `dismissKeyboardOnFocusLoss = true` の場合は、送信中は `readOnly` で編集だけを止め、
フォーカスは維持する。ポスト詳細の返信欄も同じ扱いにする。

## 7. フィードクロームとレイアウト

### 7.1 簡易入力中は下部クロームを固定する

簡易コンポーザー表示中は `collapseFraction = 0f` に戻し、フィードクロームの折りたたみ更新を
無効にする。入力欄がスクロールで画面外へ移動したり、半透明のまま残ったりしないようにする。

展開、送信成功、Back、画面遷移で簡易コンポーザーが閉じた後は、通常のクローム動作を再開する。
再開時は表示状態から始め、直前の収納率は復元しない。

`FeedScreen` へは `chromeCollapseEnabled: Boolean` 相当の値を渡し、falseへの変更時に実行中の
settle jobをキャンセルしてジェスチャー状態をIdleへ戻す。見た目だけfractionを0にして、背後の
状態機械を動かし続けない。

### 7.2 下側余白

`AppScaffold.bottomBar` は、簡易表示中は `FeedInlinePostComposer`、それ以外は `NavigationBar` を表示する。
簡易コンポーザーはボトムナビゲーションと入れ替わるため、下端のナビゲーションバーInsetは簡易コンポーザー自身が
取る（`applyNavigationBarsPadding` は既定値のtrue）。キーボード表示中も、簡易欄とキーボードの間に
ボトムナビゲーションは残らない。

Scaffoldが返す `padding.calculateBottomPadding()` をそのまま
`FeedScreen.bottomContentPadding` に渡す。これにより、簡易コンポーザーの高さが本文の折り返しや
エラー表示で変わっても、リスト末尾が下部UIに隠れない。

キーボード表示時のIME回避は、ポスト詳細の返信欄と同じくScaffold側の既存方針に合わせる。

### 7.3 FAB

フィードのFABは `Hidden` で＋、`FeedInline` で「…」、`FullScreen` では非表示とする。
簡易表示中に＋を出さないので、同じ投稿状態を再初期化する二重タップ経路はない。

## 8. 変更対象

| ファイル | 変更内容 |
| --- | --- |
| `App.kt` | セッション再生成をまたいで新規投稿の再開要求を保持する `PendingComposerRequestHolder` を追加 |
| `ComposerCoordinator.kt` | 排他的な表示状態と新規／展開／終了の遷移を追加し、Boolean表示状態を置換。＋押下時の `localDraft` による分岐 |
| `AppSessionCoordinator.kt` | `PostViewModel` の共有、＋と鍵設定完了後の遷移、下部Column、クローム固定、簡易送信を接続 |
| `ComposerHost.kt` | 共有ViewModelを `PostSheet` へ渡し、送信時コンテキストを含む共通の投稿完了監視を所有 |
| `ui/components/AppControls.kt` | 返信／投稿共通の `AppMessageComposer` に先頭コンテンツ、下部Inset、送信説明、任意のフォーカス喪失時キーボード制御、`canSend`、`maxLength`、送信中の `readOnly` を追加 |
| `ui/components/Keyboard.kt` | フォーカスを変えずプラットフォーム入力セッションまで終了する `rememberHideKeyboard()` を追加 |
| `ui/post/PostSheet.kt` | `KeepCurrent` 初期化を追加し、投稿完了監視をホストへ移動 |
| `ui/post/ComposerBodyEditor.kt` | 初期カーソル位置を本文末尾にする |
| `ui/post/PostViewModel.kt` | 送信時の返信先・`NoteContext`を含む単一の `PostCompletion` を公開。送信開始時の世代記録、世代が変わった後の失敗通知、`post()` での文字数上限チェック |
| `ui/thread/ThreadScreen.kt` | 返信欄で `dismissKeyboardOnFocusLoss = true` を指定 |
| `ui/post/FeedInlinePostComposer.kt`（新規） | `AppMessageComposer` への薄いバインディングと、簡易送信 `postFromFeedInline()` |
| `ui/post/FeedInlineComposerBackHandler.kt` と各プラットフォームの actual（新規） | 簡易欄が開いている間のBack。Androidのみ有効、iOSとWebは何もしない |
| `ui/feed/FeedScreen.kt` | 簡易表示中のクローム更新停止を受け付ける |
| `ComposerCoordinatorTest.kt` | 表示状態遷移とコンテキスト初期化の単体テストを追加 |

簡易コンポーザーは責務が明確になる場合、`ui/post/FeedInlinePostComposer.kt` として分離するが、
UI本体は必ず `AppMessageComposer` を使い、フィード固有状態を接続するだけにする。

## 9. 実装順序

1. `ComposerPresentation` と状態遷移の純粋テストを追加する。
2. `PostCompletion` を導入し、通常投稿と各種返信の完了コンテキストを固定する。送信中の世代チェックも入れる。
3. `PostViewModel` を `AppSessionCoordinator` で共有し、既存 `PostSheet` の挙動が変わらないことを確認する。
4. `PostSheetInitialState.KeepCurrent` と共通の投稿完了監視を導入する。
5. App階層のholderと、鍵設定完了後のセッション再生成時に `FeedInline` へ復帰する処理を実装する。
6. `rememberHideKeyboard()` と、後方互換な `AppMessageComposer` の拡張を実装する。
7. 簡易コンポーザーと下部Columnを追加する。
8. フィードクロームの固定と動的な下側余白を接続する。
9. Android、iOSでキーボード、Back、展開、送信を、Webでキーボード、Back、送信と展開ボタン非表示を確認する。

既存の返信／引用／下書き導線を壊した場合に原因を限定できるよう、状態共有とUI追加を分けて進める。

## 10. テスト計画

### 10.1 単体テスト

- `Hidden -> FeedInline -> FullScreen -> Hidden` の遷移
- ＋押下時に返信／引用コンテキストが消えること
- `localDraft` がある状態の＋では `FullScreen` + `RestoreMemo` になり、下書きが復元されること
- △では `PostState.text` が維持されること
- 簡易送信がkind 1、返信タグなし、引用なしで送信されること
- 同じ本文と同じ登録絵文字を簡易欄とシートから送信したとき、kind、content、tags（`client` を含む）、送信先リレーが一致すること
- 800字を超える本文は、簡易欄でもシートでも入力できず、`post()` も送信しないこと
- 送信中に `reset()` した後の成功／失敗で、新しい入力状態が上書きされず、完了または失敗の通知が一度だけ届くこと
- 送信成功時に投稿完了コールバックが一度だけ呼ばれること
- 下書き一覧から復元したタイムライン返信の完了結果に、正しい `replyToId` と `NoteContext.Timeline` が入ること
- 下書き一覧から復元したチャンネル返信の完了結果に、正しい `replyToId` と `NoteContext.Channel` が入ること
- 送信失敗時に本文とエラーが残ること
- 別画面への遷移とアカウント切り替えで状態が破棄されること
- 簡易表示の開始／終了でクローム状態がIdle、fraction 0になること
- 秘密鍵なしの＋ではApp階層のholderにだけ要求が保存され、鍵設定完了後のセッション再生成で一度だけ `FeedInline` へ移ること
- 鍵設定キャンセル時は簡易コンポーザーが開かないこと

### 10.2 Compose UIテスト

- ＋押下後に「今何してる？」、展開ボタン、送信ボタンが表示され、FABが消えること（Web版以外）
- Web版では＋押下後に展開ボタンが表示されず、入力欄と送信ボタンだけが表示されること
- 展開ボタンが入力欄より左にあり、contentDescriptionで操作できること
- 空本文では送信不可、入力後は送信可能になること
- 4行を超えても下部UI全体が無制限に伸びないこと
- エラー表示で増えた高さがフィード末尾余白へ反映されること
- 簡易表示中のスクロールで入力欄とボトムナビが収納されないこと
- △押下後の `PostSheet` に同じ本文が表示され、カーソルが本文末尾にあること
- 800字ちょうどの本文を展開したシートで、削除と編集ができること
- ポスト詳細の返信欄の見た目と動作が変わっていないこと
- 返信欄と投稿欄が同じ `AppMessageComposer` を使い、画面固有の入力欄実装が増えていないこと
- 入力欄から別要素へフォーカスを移すとキーボードが閉じること
- 初回の未フォーカス通知だけでは不要なキーボード制御を行わないこと
- 送信中も入力フォーカスとキーボードが維持されること（返信欄も同じ）
- 送信失敗時は入力フォーカスとキーボードが維持され、本文を修正できること
- 送信中のBackで簡易コンポーザーが閉じないこと
- チャンネル投稿欄はフォーカス喪失時キーボード終了を有効化せず、現行動作を維持すること

### 10.3 実機確認

- iOS／Android: 自動フォーカス、IME表示、Back、複数行、回転またはサイズ変更
- Web: ユーザー操作なしにキーボードを要求せず、入力欄タップで正常にフォーカスできること。展開ボタンが表示されないこと
- 低速回線: 投稿中の二重送信防止、成功／全失敗／一部失敗
- リレー未設定: 送信せず、簡易欄ではリレー設定を促すエラーを表示し、本文を維持すること

## 11. 受け入れ条件

1. フィードの＋で、ポスト詳細の返信欄と同系統の簡易投稿UIが下部に表示される。
2. 簡易投稿UIのFABから現行 `PostSheet` を開ける。
3. 展開前に入力した本文が `PostSheet` で失われない。
4. 簡易欄からテキスト投稿でき、成功、失敗、一部リレー失敗の扱いが現行シートと一致する。同じ入力なら、送信されるイベント（kind、本文、タグ、送信先）と文字数上限もシートと同一である。
5. Web版以外では、画像、絵文字、下書き、Content Warning、リレー選択を展開後に従来どおり使える。
6. 簡易入力中にフィードをスクロールしても入力欄が隠れず、リスト末尾も入力欄の裏に隠れない。
7. 返信、引用、チャンネル返信、ポスト詳細の返信欄に回帰がない。
8. 返信欄とフィード投稿欄が同じ入力コンポーネントを使用している。
9. フッター投稿欄からフォーカスを外すとソフトウェアキーボードが閉じる。送信中はキーボードが閉じない。
10. シートからカスタム絵文字設定へ移動した後の＋で、現行どおり下書きが復元される。
11. 送信中に閉じても、その後の入力が古い送信結果で上書きされない。

## 12. 今回の設計判断

- 「△ボタンみたいなの」は、詳細な投稿画面を上へ展開する意味の上向きアイコンとして扱う。
- Web版では展開ボタンを表示せず、フィードからの新規投稿をテキストのみとする。画像などの付加機能がWeb版のフィードから使えなくなることは許容する。
- 簡易欄は文字入力と送信に絞り、付加機能は現行シートへ集約する。
- 簡易欄とシートは同じ `PostViewModel` を共有し、本文のコピーによる同期は行わない。
- 返信欄と簡易投稿欄は同じ `AppMessageComposer` を使い、フォーカスとキーボードの挙動も共通化する。
- 入力中の操作安定性を優先し、簡易欄表示中はフィードクロームを収納しない。
- 簡易欄とシートの投稿差異をなくすため、文字数上限、送信可否、送信先の決定は画面ではなく共通の層で持つ。
- 鍵設定完了でセッションが作り直されるため、新規投稿の再開要求はセッションより外側に置く。

## 13. 決定事項（旧未決事項）

- フッター投稿は設定で切り替える（設定 → 表示 →「フッターから投稿」、端末単位、`DisplayPreferencesStore.useFooterComposer`）。
  既定は全プラットフォームでオフで、＋は全画面の `PostSheet` を開く。フッター投稿をオンにした場合は＋で簡易投稿欄を開く。
  オフへ切り替えたときは、開いている簡易投稿欄と「…」で保持中の入力を破棄する。
- 簡易欄のボタン配置: FABを△（`KeyboardArrowUp`、「詳細な投稿画面を開く」）にして投稿シートへ展開し、
  簡易欄左端を▼（`KeyboardArrowDown`、「投稿欄を閉じる」）にして入力を保持したままフッターメニューへ戻す。
  4.2節の左端△と「…」FABは、この配置に置き換える。Webは従来どおり、FABの「…」で戻し、左端のボタンは出さない。

- 簡易欄を閉じる手段: 全プラットフォーム共通で、簡易欄表示中のFABを「…」にし、押すとフッターメニューへ
  切り替える。入力は保持し、＋で再表示する（4.2節）。AndroidのBackも同じ動作とする。
- 簡易欄に本文があるままフィードの返信・引用を押した場合: 現状どおり、返信用のシートを `Reset` で開き、
  簡易欄の本文は破棄する。
- キーボード表示中の下部レイアウト: 簡易欄をボトムナビと入れ替えて表示するため、キーボード表示中も
  ボトムナビは出ない（7.2節）。

## 14. 実装状況

最終更新: 2026-09-29

### 14.1 実装済み（コンパイル・単体テスト済み）

Android、iOS Simulator（arm64）、wasmJsのコンパイルが通り、`allTests`（iOS Simulator 848件、wasmJs 827件）
がすべて成功している（「…」によるフッター切り替えを追加した後は iOS Simulator 854件、wasmJs 833件）。コードレビューで、送信成功時に未消費の `staleFailure` が消える不具合を見つけて修正し、
回帰テストを追加した。

Web版はヘッドレスChrome（430x900、送信EVENTはリレーへ転送せず捕捉）で次を確認した。iOS・Androidの実機確認はまだ。

- ＋で簡易欄がボトムナビと入れ替わって表示され、△は出ず、FABが「…」になる。空本文では送信ボタンが無効
- 入力後の「…」でボトムナビとFAB＋に戻り、＋で同じ本文のまま簡易欄が再表示される
- 送信するとkind 1、`client` タグのみ、本文どおりのイベントが書き込み可能な3リレーへ送られ、成功Snackbarの後に簡易欄が閉じる
- 「…」で保持したままジャーナルへ移動して戻ると、＋で空の簡易欄が開く

| 領域 | 内容 | 主なファイル |
| --- | --- | --- |
| 表示状態 | `ComposerPresentation`、＋・展開・閉じる・下書き復元の遷移、`PendingComposerRequestHolder` | `ComposerCoordinator.kt`、`App.kt` |
| 送信と完了 | `PostCompletion`、`consumeCompletion()`、送信中の世代チェック、`staleFailure`、文字数上限、`showError()` | `PostViewModel.kt` |
| ホスト | 共有 `PostViewModel`、完了監視の一本化、`fromCurrentDraft` による閉じる／閉じない | `ComposerHost.kt` |
| シート | `PostSheetInitialState`（`KeepCurrent`）、投稿完了監視の削除、初期カーソルを末尾へ | `PostSheet.kt`、`ComposerBodyEditor.kt` |
| 共通入力欄 | `leadingContent`、`applyNavigationBarsPadding`、`sendContentDescription`、`onFocusChanged`、`dismissKeyboardOnFocusLoss`、`canSend`、`maxLength`、`autoFocus`、送信中の `readOnly` | `AppControls.kt`、`Keyboard.kt` |
| 簡易欄 | `FeedInlinePostComposer`、`postFromFeedInline()`、Web版は△なし | `ui/post/FeedInlinePostComposer.kt` |
| フッター切り替え | FABの「…」とBackで入力を保持したままフッターメニューへ戻す（`switchInlineToMenu`、`hasHeldInlineDraft`）。切り替え後の送信失敗はSnackbar | `ComposerCoordinator.kt`、`AppSessionCoordinator.kt` |
| Back | 簡易欄用のBack（Androidのみ有効。iOS・Webは何もしない） | `FeedInlineComposerBackHandler`（common、Android、iOS、wasmJs） |
| 画面の接続 | FABの＋／…切り替え、ボトムナビとの入れ替え、遷移時の破棄、クローム固定、鍵設定完了後の再開 | `AppSessionCoordinator.kt`、`FeedScreen.kt` |
| 返信欄 | `dismissKeyboardOnFocusLoss = true` | `ThreadScreen.kt` |

ポスト詳細の返信欄は、送信後もキーボードが開いたままになる（送信中に入力欄を無効化しなくなったため）。

### 14.2 テスト

追加した単体テスト。

- `ComposerCoordinatorTest`: 表示状態の遷移、コンテキストの初期化、下書き復元、閉じたときのリセット、再開要求の一度きりの消費、
  「…」での入力保持と＋での再表示、保持中の遷移・返信・投稿完了
- `PostViewModelTest`: 文字数上限、リレー0件、`showError()`、送信中に入力が破棄された場合の成功・失敗の扱い
- `AppMessageComposerLogicTest`（新規）: フォーカス喪失時のキーボード制御、文字数上限の判定

未作成:

- 設計書10.2のCompose UIテスト（リポジトリにUIテスト基盤がない）
- 設計書10.1のうち、実際のイベント生成を伴うもの（簡易送信とシート送信のイベント一致、送信成功時のコールバック回数）。
  `NostrRepository` がグローバルでネットワークに出るため、差し替えられる形への分離が必要
- 10.3の実機確認

### 14.3 次にやること

1. Android、iOSの実機で「…」による切り替え、キーボード表示中のレイアウト、Back、展開、送信、鍵設定後の再開を確認する。


### 14.4 ジャーナルの新規投稿との共通化（2026-09-30）

- 自分のジャーナルの＋を `AppSessionCoordinator` のフィードと共通のFABへ移し、
  `openNewPost(useFooterComposer, resetPost)` で設定を適用する。
- オンでは両画面とも同じフッター投稿欄を使い、△で本文を保持してシートへ展開、▼で入力を保持してメニューへ戻す。
  オフでは両画面とも投稿シートを開く。他ユーザーのジャーナルに新規投稿FABは出さない。
- 鍵設定、送信、エラー、完了処理も共通の経路を使う。
- フィードとジャーナルを含む画面間の移動では、表示中・保持中の簡易入力を破棄する。
  同一画面内のフィードタブ切り替えやジャーナルの日付変更では保持する。
- カスタム絵文字設定から戻ったローカル下書きは、フッター設定にかかわらずシートで復元する。
- `FeedInline` / `FeedInlinePostComposer` などの既存名は変更範囲を抑えるため維持し、両画面で共有する。
- 検証: iOS Simulator の `ComposerCoordinatorTest` 25件成功、`compileKotlinWasmJs` 成功。
  ジャーナルでのキーボード・FAB・リスト下端の実機操作確認は未実施。
