# フィード再描画の削減設計

作成日: 2026-10-02。状態: 実装済み。

## 1. 目的

フィードで1件のリアクション・返信・プロフィールが更新されたとき、表示中の全NoteCardが再描画される問題を解消する。変化のあったカードだけが再描画され、他のカードはCompose（Strong skipping、Kotlin 2.4）がスキップできる状態にする。

対象は`NoteTimeline`と`noteListItems`（フィード、プロフィールの投稿一覧で共有）。`NoteCard`内部の分割や`UiState`の分割は対象外とする（§6）。

## 2. 現状

### 状態の流れ

`FeedViewModel.UiState`はフィード全体の1つのdata class。どの投稿のエンゲージメントやプロフィールが変わっても新しい`UiState`が発行される（即時、または150msでまとめて）。`NoteTimeline`はそれを受けて再実行され、`LazyColumn`の各表示項目のcontentも再実行される。

項目ごとに`NoteCard`へ渡す値は、多くが元の`UiState`内のインスタンスをそのまま参照している。

- 投稿ごとのMap値（`customReactions[id]`、`reactionEvents[id]`、`replies[id]`等）は`map + (id to …)`による差分更新で、他の投稿の値の同一性は保たれる。
- `parsedContents`は`FeedItemMapper`がevent ID単位でキャッシュし、同一インスタンスを返す。
- `profiles`は関係するpubkeyに絞った`relevantProfiles`を`remember`済み。

### スキップを妨げている箇所

Strong skippingでは、不安定な型の引数は同一性（`===`）で、ラムダは取り込んだ値の同一性で比較・再利用される。`NostrEvent`は`tags: List<List<String>>`を持つため不安定な型として扱われる。

| # | 箇所 | 原因 |
| --- | --- | --- |
| P1 | `NoteListItems.kt`の`onLike`・`onRepost` | 押下時の判定のために`state`（`UiState`全体）を取り込む。`UiState`は毎回新しいので、ラムダも毎回新しくなる |
| P2 | `NoteTimeline.kt`の`onRepost`・`onReport` | `state.events.find`のために`state`を取り込む。これを受け取る項目内のラムダも連鎖して毎回新しくなる |
| P3 | `NoteListItems.kt`の`replyParentForEvent`・`quotedEventsForEvent` | 毎回`QuotedEvent`と`List`を新しく作る。内容が同じでも同一性で比較されるため変化と判定される |
| P4 | 呼び出し元から渡る操作ラムダ | `FeedContent`は`UiState`ごとに再実行され、`viewModel::react`などの関数参照や呼び出し元のラムダが同一インスタンスで渡る保証がない |

P1〜P4のどれか1つでも新しい値になると、そのNoteCardは本体全体が再実行される。P1・P2は全カードに、P3は返信先・引用を持つカードに毎回該当する。

## 3. 方針

### 3.1 操作はState経由で呼ぶ（P2・P4）

`NoteTimeline`で操作をまとめた`NoteListActions`を毎回作り、`rememberUpdatedState`で保持する。項目内のラムダはこの`State`（同一インスタンス）だけを取り込み、押下時に`actions.value`の最新の操作を呼ぶ。

- 呼び出し元の関数参照・ラムダが再生成されても、NoteCardへ渡すラムダは変わらない。
- 押下時には常に最新の操作を使うため、古いコールバックを呼ぶことはない。
- `onRepost`・`onReport`は`noteListItems`の時点で対象の`NostrEvent`を受け取る形に変え、`state.events.find`をなくす。

### 3.2 押下時の判定は投稿単位の値で行う（P1）

いいね・リポストの取り消しか追加かの判定は、その投稿の`isLiked`・`isReposted`（Boolean）を取り込んで行う。`UiState`全体は取り込まない。Booleanが変わったときだけラムダが変わり、そのカードはどのみち表示が変わるため再描画してよい。

押下時点の最新状態ではなく描画時の値で判定するが、表示中のボタン状態と判定が一致するのでこれが正しい。従来も描画時の`state`で判定していた。

### 3.3 引用・返信先は内容が同じならインスタンスを使い回す（P3）

`QuotedEvent`の構築を純粋関数`noteReplyParent`・`noteQuotedEvents`へ切り出し、項目内では結果を`remember(結果)`で包む。`remember`のキーは`equals`で比較されるので、内容が同じなら前回のインスタンスを返す。既存の`relevantProfiles`と同じ方法。

`QuotedEvent`へ`@Immutable`を付ける案もあるが、`NostrEvent`の深い比較が毎回走り、引数に安定性を宣言する範囲が広がるため採用しない。

### 3.4 変えないもの

- `noteListItems`の公開する振る舞い（表示条件、null時に操作を出さない条件、ミュート・削除・通報の対象）は変えない。
- `NoteCard`の引数は変えない。

## 4. 検証

- 純粋関数（`noteReplyParent`・`noteQuotedEvents`）の単体テスト: 返信先の除外、プロフィールの対応付け、取得前の引用の除外。
- 再描画回数の実測: Web版をヘッドレスで起動し、一時的なカウンタでNoteCard本体の実行回数を数える。修正前後で、フィード表示後の一定時間内の回数を比べる（カウンタは計測後に削除）。実リレーへの送信は遮断する。
- 既存テスト（iOS・Web）とAndroidのコンパイル。

## 5. 設計レビュー

- **押下時に古い判定を使わないか（3.2）**: 判定に使う値は、そのカードが表示しているボタン状態と同じ描画で決まる。値が変わればラムダが変わってカードも再描画されるため、表示と判定がずれる期間はない。
- **`rememberUpdatedState`の更新タイミング（3.1）**: 値は描画中に更新され、クリックは描画後に処理されるため、押下時には常に最新の操作になる。
- **LazyColumnの項目内`remember`（3.3）**: 項目は`key = event.id`で識別されるため、`remember`は同じ投稿の中でだけ使い回される。画面外に出て破棄された場合は作り直すだけで、誤った投稿の値を返すことはない。
- **P4を3.1でまとめて扱う妥当性**: 関数参照のメモ化はComposeコンパイラの版によって挙動が異なりうる。`State`経由にすれば版に依存しない。
- **計測の限界**: Web版の計測で、iOS・Androidでの描画時間の改善までは示せない。回数の削減を示すにとどめる。

## 6. 対象外・後続

- NoteCardの分割（リアクション行などを独立したComposableにする）。本設計の後、変化のあったカード内でも再描画範囲を狭めたい場合に検討する。
- `UiState`を投稿単位の状態へ分割する案。影響範囲が大きく、本設計で全カードの再描画が解消すれば不要になる見込み。
- `NoteCard`を直接使う他の画面（スレッド・チャンネル等）。同様の問題があれば個別に対応する。

## 7. 実装・レビュー・計測結果

### 実装

- `NoteListItems.kt`: `NoteListActions`と純粋関数`noteReplyParent`・`noteQuotedEvents`を追加。`noteListItems`は操作を`State<NoteListActions>`で受け取り（呼び出し元は`NoteTimeline`のみのため`internal`に変更）、項目内のラムダは`actions`・その投稿の`event`・`isLiked`/`isReposted`だけを取り込む。どの操作を出すか（null判定）は項目の外で一度だけ判定する。
- `NoteTimeline.kt`: 操作を`rememberUpdatedState(NoteListActions(...))`で保持。`state.events.find`による検索をやめ、項目の`event`を直接渡す。

### 実装レビュー

- 項目の描画中に`actions.value`を読まない（読むと`NoteTimeline`の再描画ごとに全項目が無効化される）。読むのは押下時と、項目の外での操作の有無の判定だけ。
- 操作を出す条件（ログイン状態、kind 1のみリポスト等）、削除・ミュート・通報の対象は従来と同じ。リポスト・通報は従来も同じIDの投稿を検索して渡しており、項目の`event`を直接渡しても対象は変わらない。

### 計測（Web版、開発ビルド、ヘッドレスChrome、グローバルフィード、操作なし）

NoteCard本体とタイムラインの描画回数を一時的なログで数えた（計測後に削除）。実リレーへのEVENT送信は遮断した（送信0件）。

| 版 | 計測時間 | タイムラインの再描画 | NoteCardの再描画 | 1回あたり |
| --- | --- | --- | --- | --- |
| 修正前 | 40秒 | 8回 | 48回 | 6.0枚 |
| 修正前 | 90秒 | 9回 | 45回 | 5.0枚 |
| 修正後 | 40秒 | 3回 | 0回 | 0 |
| 修正後 | 90秒 | 9回 | 4回 | 0.44枚 |

修正前は表示中のカード（5〜6枚）が毎回すべて再描画されていた。修正後は、データが変わったカードだけが再描画されている。iOS・Androidでの描画時間の計測は未実施。

### テスト

`NoteListItemsQuoteTest`（3件）: 返信先の取得済み判定とプロフィール、引用の順序・未取得の除外・返信先の除外、同じ入力から等しい値が作られること。iOS全978件・Web全953件が成功。Androidのコンパイルと`verifyNoDirectProfileSubscriptions`も成功。Web版でカードのタップから詳細画面が開くことを確認した。
