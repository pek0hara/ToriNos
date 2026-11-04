# デフォルトのリアクション設定の設計

## 目的

ハートボタンをタップしたときに送るリアクションを、アカウントごとに選べるようにする。初期値は今と同じ「いいね（`+`）」で、Unicode 絵文字やカスタム絵文字にも変えられる。

## 決定事項

| ID | 内容 |
| --- | --- |
| R1 | 設定は端末内だけに保存し、リレーへは同期しない（「最近使った」と同じ扱い） |
| R2 | ハートボタンのアイコンは、デフォルトに関係なくハートのままにする |
| R3 | 長押しメニューの「絵文字を選ぶ」で開くピッカーから、デフォルトを選べるようにする。設定画面からも選べる |
| R4 | デフォルトで送ったリアクションは「最近使った」に記録しない |

## 値の定義

`EmojiPreferences` に `defaultReaction: RecentReaction?` を加える。`null` は「いいね（`+`）」を表す。「最近使った」と同じ型にし、`emoji` パッケージを `ReactionOption`（model）に依存させない。UI では `defaultReactionOption()` で `ReactionOption` に変える。

- `RecentReaction.Unicode`：絵文字ピッカーで選んだ Unicode 絵文字
- `RecentReaction.Custom`：カスタム絵文字（shortcode と画像 URL）

選ぶ手段はピッカーだけにし、自由入力の文字列は受け付けない。

## 保存

- 形式 v2 の `StoredEmojiPreferences` に、任意項目として `defaultReaction: StoredRecentReaction? = null` を加える。種類・値・画像 URL を持つ構造は「最近使った」と同じなので、型もそれを使う。
- 項目がない、または読めないときは `null`（いいね）にする。移行処理はいらない。
- 同期の対象ではないので `revision` は進めない（R1）。kind 10030 を取り込むときも、この値には触れない。
- セットのアドレスは保存しない。送るときに `setAddressOf` で引き、見つからなければ4要素目なしで送る。登録を外したセットの絵文字も、画像 URL があるのでそのまま送れる。

reducer に `setDefaultReaction(reaction: RecentReaction?)` を加える。正規化できない値（空の shortcode や URL）は `null` として扱う。`CustomEmojiRepository.setDefaultReaction` と、UI 用の `AccountSession?.setDefaultReaction(option: ReactionOption?)` から呼び、表示しているアカウントの設定を書き換える。

## ハートボタンの動き

どの操作をするかは純粋関数 `heartTapAction` で決める。

```kotlin
sealed interface HeartTapAction {
    data object Like : HeartTapAction                        // AddLike
    data class React(val option: ReactionOption) : HeartTapAction   // AddEmoji
    data object Unlike : HeartTapAction                      // いいねを取り消す
    data class Unreact(val option: ReactionOption) : HeartTapAction // 絵文字リアクションを取り消す
    data object None : HeartTapAction
}

fun heartTapAction(
    isLiked: Boolean,
    ownEmojiReaction: ReactionOption?,
    defaultReaction: ReactionOption?,
): HeartTapAction
```

| 状態 | タップ | 長押し |
| --- | --- | --- |
| 自分のリアクションなし、デフォルトがいいね | `Like` | クイックメニュー（今と同じ） |
| 自分のリアクションなし、デフォルトが絵文字 | `React(default)` | クイックメニュー（今と同じ） |
| いいね済み | `Unlike` | なし |
| 絵文字リアクション済み | `Unreact(その絵文字)` | なし |
| 絵文字リアクション済みで、どの絵文字か分からない | `None` | なし |

- デフォルトが絵文字のときは、既存の `onEmojiReact(option)`（`EngagementRequest.AddEmoji`）の経路で送る。送信中の表示も楽観的な更新も、今の仕組みでそのまま動く。
- 分岐を持つのはボタン側の2か所だけにする。
  - `NoteCardContent` の `onHeartClick`（フィード、スレッド、チャンネル、ジャーナル、検索、通知）
  - `ArticleEngagementBar` のハート（記事）
- 各 Controller と `NoteEngagementService` は変えない。
- アイコン、色、件数の表示は変えない（R2）。投稿のハートの読み上げ用の説明だけ、デフォルトが絵文字のときは「🎉でリアクション」や「:shortcode: でリアクション」にする。記事のボタンは表示の「いいね」の文字が読まれるので変えない。
- デフォルトは `rememberDefaultReaction()`（`derivedStateOf`）で読む。最近使った等、絵文字設定のほかの変更では投稿カードを再描画しない。送る値はタップした時点で読む。
- 記事は従来どおり、自分の絵文字リアクションの取り消しを、いいねの取り消しより優先する。

## 絵文字ピッカーからデフォルトを選ぶ

`StandardEmojiPickerSheet` に任意の引数 `defaultReactionMode: DefaultReactionPickerMode? = null` を加える。今のデフォルトの読み出しと書き換えは、ピッカーが `LocalAccountSession` から行う。リアクション用に開くとき（`NoteCardContent`、`ArticleEngagementBar`、`ArticleEngagementContent`）は `Offer` を渡し、設定画面からは `SelectOnly` を渡す。投稿欄から開くとき（`AppControls`、`ComposerBodyEditor`）は渡さず、今のままにする。未ログインのときは行を出さない。

### 通常モード（タップで送る）

検索欄の上に1行足す。

```
デフォルト  [❤️]                       [変更]
```

- 今のデフォルトのアイコンを出す。いいねのときは ❤️ にする。
- 「変更」を押すと、デフォルトを選ぶモードに切り替わる。
- 右端には、これまでどおり「絵文字セット」ボタンを出す。
- 絵文字タイルをタップしたときの動きは今と同じ（送って閉じる）。

### デフォルトを選ぶモード

- 上の行を「デフォルトにする絵文字を選ぶ」と「キャンセル」に置き換える。
- グリッドの先頭に「いいね（＋）」のタイル（❤️）を1つ足す。ピッカーには `+` がないため。
- 今のデフォルトのタイルには、選択中の背景を付ける。
- タイルをタップすると `onChange` を呼び、通常モードに戻る。リアクションは送らず、シートも閉じない。デフォルトを決めてから、そのまま送ることもできる。
- `SelectOnly` で開いたとき（設定画面）は、選んだらシートを閉じる。キャンセルでも閉じる。
- 検索とカテゴリは、どちらのモードでも同じように使える。「絵文字セット」ボタンは、行に収まらないため選ぶモードでは出さない。
- 選んだ絵文字は「最近使った」に記録しない。

## 設定画面

`SettingsScreen` の「絵文字」の上に「リアクション」の項目を置く。アカウントでログインしているときだけ出す。

```
リアクション
  ハートをタップしたときに送る      [🎉]  >
```

- 行を押すと、`SelectOnly` でピッカーを開く。
- いいねに戻すには、ピッカーの「いいね（＋）」タイルを選ぶ。

## アカウント

- 値は `LocalAccountSession` が持つ `EmojiPreferences` から読む。アカウントを切り替えると、そのアカウントの値になる。
- 切り替えている途中にタップしたときは、送信に使うアカウントの値を使う。今の `onEmojiReact` と同じ範囲で扱えばよい。

## テスト

先に書く。

- `EmojiPreferencesStorage`
  - デフォルトが Unicode のとき、カスタム絵文字のとき、いいね（`null`）のときに、それぞれ書いて読んで同じ値に戻る
  - 項目のない既存の v2 JSON を読むと `null` になる
  - デフォルトを変えても `revision` が変わらない
- reducer
  - `setDefaultReaction` で、正規化できない値は `null` になる
  - kind 10030 を取り込んでもデフォルトは残る
- `heartTapAction`：上の表の5行すべて
- 送るタグ：登録を外したセットのカスタム絵文字がデフォルトのとき、`emoji` タグが4要素目なしで組み立てられる

## 範囲外

- リレーとの同期（NIP-78 など）
- ハートボタンのアイコンを変えること
- 投稿欄から開いたピッカーでデフォルトを選ぶこと
