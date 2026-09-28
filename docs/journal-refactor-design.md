# ジャーナル リファクタ設計

作成日: 2026-09-27
状態: S0〜S5、F1〜F7 を実装済み（2026-09-28）。実装で設計から変えた点は9章。

## 0. 仕様整理

ジャーナルの仕様は `app-requirements.md` に独立した節がなく、実装だけが仕様になっている。
リファクタの前提として、現行の実装から読み取れる仕様を確定仕様として書き出す。
本章と異なる挙動を変える場合は、6章の修正段階で明示的に扱う。

### 0.1 画面と利用者

| 入口 | 対象の公開鍵 | 秘密鍵 | 選べる種類 |
| --- | --- | --- | --- |
| ボトムナビの `journal` タブ | 自分 | 必須（`PendingKeyAction.Journal`） | 投稿・返信・リポスト・したいいね・もらったいいね |
| プロフィールの「ジャーナル」（`UserJournalRoute`） | 他人 | 不要 | 投稿・返信・リポスト・もらったいいね |

「したいいね」は自分のジャーナルだけで選べる。種類を1つも選んでいないときは、投稿・返信・リポストを表示する。

### 0.2 アクティビティの種類

1つのイベントが複数の種類に該当することがある（自分の投稿に自分でいいねした kind 7 は、
「したいいね」と「もらったいいね」の両方）。分類結果は集合とする。

| 種類 | 条件 | 取得フィルター | 取得先 |
| --- | --- | --- | --- |
| 投稿 | kind 1、作者が対象、返信先なし | `kinds=[1] authors=[対象]` | 選択中のリレー |
| 返信 | kind 1 で返信先あり、または `isSupportedTimelineComment()` を満たす kind 1111 | kind 1 は同上、kind 1111 は `#K=["1"]` 付き | 選択中のリレー |
| リポスト | kind 6、作者が対象 | `kinds=[6] authors=[対象]` | 選択中のリレー |
| したいいね | kind 7、作者が対象（自分のジャーナルのみ） | `kinds=[7] authors=[対象]` | 選択中のリレー |
| もらったいいね | kind 7、内容が `-` ではない、`ReactionEventStore.isAddressedTo(event, 対象)` | `kinds=[7] #p=[対象]` | 有効な全リレー |

「選択中のリレー」は `RelayStore.selectedMemoRelayUrl`（未設定・無効なら先頭のリレー）。

### 0.3 日付と表示

- イベントは `created_at` を端末のタイムゾーンで日付に変換して日ごとにまとめる。
- カレンダー表示では選択日のアクティビティを、非表示では選択月のアクティビティを表示する。
- カレンダーの各日には、選択中の種類に該当する件数を濃淡で示す。
- 前後の日付移動は、選択中の種類に該当するアクティビティがある日へ移る。当月内になければ月初・月末
  （当月なら今日）で止まり、次の操作で隣の月へ移る。今日より未来へは移動しない。
- kind 1 / 1111 は `NoteCard`、kind 6 / 7 は対象イベントのプレビュー付きの行で表示する。

### 0.4 取得

- 月を開くと、まず選択日を取得し、続けて月の全日を1日ずつ裏で取得する（バックフィル）。
- もらったいいねは月単位で一括取得する（上限 5,000件）。`ReactionEventStore` にあるものは通信前に即時表示する。
- 1日あたりの取得上限は 500件。
- 全リレーが EOSE を返した場合だけ「その日・その種類は取得済み」とし、再訪時に通信しない。
  タイムアウトや未応答があれば次回また取得する。
- エンゲージメント（リアクション・返信・リポスト・引用）は、画面に見えている kind 1 / 1111 と前後数件だけを
  取得する。部分結果は既存の件数を減らさない（最大値で合成）。全リレー完了時だけ正確な値で置き換える。

### 0.5 操作

- kind 1 / 1111 へのいいね・絵文字リアクションと取り消し（楽観的更新）。
- 自分の kind 1 / 1111 の削除（`NoteDeletionService`）。
- 返信の作成は画面の外（Composer）に委ねる。

### 0.6 下書き一覧（ジャーナルではない）

`DraftListSheet` は `JournalViewModel` を流用しているが、ジャーナルとは別の機能である。
kind 31234（NIP-37、NIP-44 で自己暗号化）を期間の制限なしで取得・復号し、一覧表示と削除（kind 5）を行う。
ジャーナル画面ではポストメモを一度も表示していない（0.1 のどの種類にも該当しない）。

### 0.7 決定事項

1. 下書き一覧は専用の ViewModel に分け、ジャーナルから下書き（ポストメモ）を取り除く。
2. 記事（kind 30023）の種類は、フィルターに出していないため到達できない。ジャーナルから取り除く（8章 U1）。
3. エンゲージメントは投稿ごとのデータクラスに集約する。Feed / Channel / Thread への展開は本設計の範囲外。
4. 構造の整理と挙動の変更は別の段階に分ける（6章）。

## 1. 目的

1. 下書きとジャーナルが同じ状態を共有している状態を解消する。
2. 「イベントがどの種類か」の判定を1か所にし、取得・表示・日付移動・件数表示の食い違いをなくす。
3. 取得計画、取得済み範囲、日付索引、エンゲージメント集計を純粋処理として切り出し、common test で固定する。
4. `JournalController`（1,838行）を責務ごとのクラスに分け、1ファイル 400行程度を目安にする。

## 2. 対象範囲

### 2.1 対象

- `ui/post/JournalController.kt`、`JournalViewModel.kt`、`JournalCalendarReducer.kt`、`JournalScreen.kt`、`DraftListSheet.kt`
- 関連テスト: `JournalCalendarReducerTest`、`JournalEngagementFetchTest`、`JournalFilterTest`、
  `JournalViewModelTest`、`ControllerLifecycleRegressionTest`（ジャーナル部分）

### 2.2 非目標

- Feed / Channel / Thread のエンゲージメント状態の統合。
- ボトムナビから届くカレンダー開閉要求（`ComposerCoordinator` のカウンタ）の表現の変更。
- 月のバックフィルを日単位から月単位の一括取得に変える通信最適化（8章 U3）。
- 下書き保存・復元の仕様変更。

## 3. 現状と課題

### 3.1 下書きとジャーナルが1つの状態を共有している

`JournalState` は `memos`（下書き）と `deleteDialog`（下書き削除）を持つが、どちらも `DraftListSheet` だけが使う。
ジャーナル側では `JournalLoadKind.Memo` を要求する経路がなく、`decodeMemo`、`mergeJournalMemos`、
`loadAllMemos` はジャーナル画面にとって到達不能なコードになっている。
下書き一覧は別キー（`post-draft-list`）の別インスタンスなので実行時に干渉はしないが、
`loadAllMemos` が `monthLoadGeneration` を進めるなど、同じクラスの中で2つの用途の状態遷移が絡み合っている。

### 3.2 種類の判定が4か所にある

| 場所 | 用途 |
| --- | --- |
| `JournalController.matchesLoadKinds` / `noteKindsForLoadKinds` | 取得フィルターの組み立てと取得結果の選別 |
| `JournalScreen.journalFilter` / `matchesAny` | 表示の絞り込み |
| `JournalScreen.filteredEntryCountsByDate` | カレンダーの件数 |
| `JournalController.journalEntryDatesInMonth` | 前後の日付移動 |

種類の定義も `JournalEntryFilter`（UI）と `JournalLoadKind`（取得）の2つがある。
日付移動は「もらったいいね」選択時に取得済みの kind 7 をすべて対象にする。取得済みのイベントは種類を切り替えても
残るため、以前「したいいね」を表示していた場合、自分がしたいいねだけがある日へ移動し、移動先で何も表示されないことがある。「したいいね」を他人のジャーナルで除外する規則も画面とコントローラーの両方にある。

### 3.3 取得済み範囲の表現が重複している

`loadedDates` と `loadedKindsByDate` があり、`loadedDates` は書き込むだけで読まれていない。
取得済みの判定は `missingLoadKinds`、`hasLoadedMonth`、`markLoadedKinds` と、各 `dispatch` 内の手書きの更新に散っている。

### 3.4 エンゲージメントが11個の Map に分かれている

`JournalState` と `JournalEngagementSnapshot` に同じ11個の Map があり、合成関数も Map ごとに書かれている。
項目を1つ追加すると、状態・スナップショット・部分合成・完了置換・リセットの5か所を直す必要がある。
その結果、リレー切り替え時のリセット（`resetCache`）で `reactionEvents`、`repostPubkeys`、
`pendingEngagementOperations` が消えずに残る（件数は0になるのに、誰がリアクションしたかの一覧が前のリレーのまま残る）。

### 3.5 取得処理が3通りある

`loadMonth` の初回取得、`backfillMonthEntries`、`loadDate` が、ほぼ同じ「取得→復号→合成→取得済み記録」を
それぞれ書いている。状態の更新方法も `dispatch` と `_state.value =` が混在し、
`loadDate` だけ世代（`monthLoadGeneration`）を確認しない。

### 3.6 更新の挙動が日と月で異なる

月の更新（`refreshMonth`）はその月のイベントを消してから取り直すが、日の更新（`refreshToday()`、
カレンダー表示中の `refresh()`）は取得結果を足すだけで、削除済みの投稿が残る。

### 3.7 参照先とプロフィールを独自に取得している

`fetchReferencedContentNow` だけが旧 API（`subscribe` + `events` + `awaitSubscriptionEnd`）を使う。
取得結果の検証がなく、完了状態も捨てている。プロフィールは `state.profiles` に蓄積するだけで、
`ProfileRepository` 側の更新が反映されない。`quotedEvents` という名前だが、返信の親やリポスト・いいねの対象も入っている。

### 3.8 時刻とタイムゾーンが固定されている

`currentDate()`、`dateOfEpochSeconds()` が `Clock.System` と `TimeZone.currentSystemDefault()` を直接使うため、
日付移動や月境界のテストで「今日」を固定できない（`hasLoadedMonth` だけ `today` 引数を持つ）。

### 3.9 使われていないコード

`currentFourWeekStart()`、`JournalState.entryCountsByDate`（画面は独自に絞り込んだ件数を使う）、
`JournalLoadKind.Article` とその取得・表示（フィルターに出していない）。

## 4. 設計原則

1. 種類の判定は `JournalActivityClassifier` だけが行い、取得・表示・件数・日付移動はすべてこれを使う。
2. 取得計画・取得済み範囲・日付索引・日付移動・エンゲージメント集計は副作用のない純粋処理にする。
3. 通信は `openSubscription` の `Fetch` を使い、「全リレー完了か」を結果型で返す。
4. 「今日」とタイムゾーンは注入する。既定値は現行と同じ。
5. プロフィールは `ProfileRepository` を唯一の情報源にする。
6. 構造整理の段階では挙動を変えない。挙動を変える修正は6章で段階を分ける。

## 5. リファクタ後の構造

```text
JournalScreen ── JournalViewModel ── JournalController（ジョブと世代の管理だけ）
                                        ├─ JournalLoader            取得計画の実行
                                        ├─ JournalEngagementLoader  可視範囲のエンゲージメント取得
                                        ├─ JournalReferenceLoader   参照先（EventByIdFetcher を使う）
                                        ├─ NoteEngagementCoordinator / NoteDeletionService（既存）
                                        └─ ProfileRepository（既存）
純粋処理（com.nostr.torinos.journal）
   JournalActivityKind / JournalActivityClassifier / JournalCoverage / JournalTimeline
   JournalFetchPlanner / JournalDateNavigator / JournalEngagementAggregator / JournalClock

DraftListSheet ── DraftListViewModel ── DraftMemoRepository（取得・復号・合成・削除）
```

### 5.1 種類と分類 `JournalActivityKind.kt`

```kotlin
enum class JournalActivityKind { Post, Reply, Repost, Like, ReceivedLike }

data class JournalOwner(val pubkey: String, val isSelf: Boolean) {
    val availableKinds: Set<JournalActivityKind>   // isSelf でなければ Like を除く
}
// isSelf は「targetPubkey == null（タブから開いた）」で決める。自分のプロフィールからはジャーナルへの導線を出していない。
// 公開鍵は署名者の解決後に決まるため、JournalState.owner は解決前 null。

object JournalActivityClassifier {
    fun classify(event: NostrEvent, owner: JournalOwner): Set<JournalActivityKind>
    fun matches(event: NostrEvent, owner: JournalOwner, kinds: Set<JournalActivityKind>): Boolean
}

fun effectiveJournalKinds(selected: Set<JournalActivityKind>, owner: JournalOwner): Set<JournalActivityKind>
```

- `JournalLoadKind` と `JournalEntryFilter` を `JournalActivityKind` に統合する。
  画面のラベルとアイコンは UI 側の拡張プロパティに置く。
- `effectiveJournalKinds` は「未選択なら投稿・返信・リポスト」「`availableKinds` 外を除外」をまとめる。
  現行の `effectiveJournalEntryFilters` と `availableFilters` の計算を置き換える。
- `rememberSaveable` に保存する値は enum 名のままとし、既存の保存値（`Post` など）と互換にする。
  旧 `Article` の保存値は読み込み時に捨てる。

### 5.2 参照 `JournalEventRefs.kt`

`activityTargetId()`、`embeddedRepostTarget()`、返信の親・引用・アクティビティ対象の ID 集合を1か所に置く。
画面とコントローラーの重複定義は削除する。ID は `isFullEventId` で検証してから返す
（`EventByIdFetcher.fetch` は不正な ID があると `require` で例外になるため）。

### 5.3 取得済み範囲 `JournalCoverage`

```kotlin
@JvmInline value class JournalCoverage(val byDate: Map<LocalDate, Set<JournalActivityKind>>) {
    fun missing(date: LocalDate, kinds: Set<JournalActivityKind>): Set<JournalActivityKind>
    fun hasLoadedMonth(monthStart: LocalDate, kinds: Set<JournalActivityKind>, today: LocalDate): Boolean
    fun markLoaded(dates: Collection<LocalDate>, kinds: Set<JournalActivityKind>): JournalCoverage
    fun forgetDate(date: LocalDate): JournalCoverage
    fun forgetMonth(monthStart: LocalDate): JournalCoverage
}
```

`loadedDates` は削除する。

### 5.4 日付索引 `JournalTimeline`

`JournalContent` を置き換える。分類結果を挿入時に一度だけ計算して保持する。

```kotlin
data class JournalActivity(val event: NostrEvent, val date: LocalDate, val kinds: Set<JournalActivityKind>)

class JournalTimeline private constructor(
    private val byId: Map<String, JournalActivity>,
    private val idsByDate: Map<LocalDate, List<String>>,   // created_at 昇順
) {
    fun upsert(events: Collection<NostrEvent>, owner: JournalOwner, zone: TimeZone): JournalTimeline
    fun remove(eventId: String): JournalTimeline
    fun removeDate(date: LocalDate): JournalTimeline
    fun removeMonth(monthStart: LocalDate): JournalTimeline
    fun entries(date: LocalDate, kinds: Set<JournalActivityKind>): List<JournalActivity>
    fun entries(monthStart: LocalDate, kinds: Set<JournalActivityKind>): List<JournalActivity>
    fun countsByDate(monthStart: LocalDate, kinds: Set<JournalActivityKind>): Map<LocalDate, Int>
    fun datesWithEntries(monthStart: LocalDate, kinds: Set<JournalActivityKind>): List<LocalDate>
    fun isEmpty(): Boolean
}
```

- `upsert` は変更のあった日付の索引だけを作り直す。バックフィルで1日分届くたびに全体を作り直している現行の処理をなくす。
- 分類は挿入時点の結果を保持する。「もらったいいね」の判定は `ReactionEventStore` が後から知る対象作者によって変わり得るが、
  もらったいいねは取得時点で `isReceivedLikeForJournal` を通したものだけを入れているため、実質的な差は出ない。
  分類器は `isAddressedTo` を引数で受け取り、テストでは固定の関数を渡す。
- 表示用の絞り込みはここで行い、画面の `matchesAny` と `filteredEntryCountsByDate` を削除する。
- `JournalActivity` は `@Immutable` 相当の不変値とし、`LazyColumn` の key は現行どおり `note-<eventId>`。

### 5.5 取得計画 `JournalFetchPlanner`

```kotlin
sealed interface JournalFetchRequest {
    val dates: List<LocalDate>
    val kinds: Set<JournalActivityKind>
    val filters: List<NostrFilter>
    val target: RelayTarget

    data class Authored(...) : JournalFetchRequest    // 日単位。選択中のリレー
    data class ReceivedLikes(...) : JournalFetchRequest  // 日単位または月単位。全リレー
}

object JournalFetchPlanner {
    fun forDate(date: LocalDate, kinds: Set<JournalActivityKind>, coverage: JournalCoverage,
                owner: JournalOwner, relayUrl: String?, zone: TimeZone, force: Boolean): List<JournalFetchRequest>
    fun forMonthOpen(monthStart: LocalDate, selectedDate: LocalDate, today: LocalDate,
                     kinds: Set<JournalActivityKind>, coverage: JournalCoverage, owner: JournalOwner,
                     relayUrl: String?, zone: TimeZone, refresh: Boolean): JournalMonthPlan
}
```

- `forDate` は現行 `fetchEvents` のフィルター組み立て（kind 1111 を別フィルターにする規則を含む）を移す。
- 月を開くときは `forMonthOpen(...)` が `JournalMonthPlan(initial, backfill)` を返す。
  `initial` は選択日の取得、`backfill` は「もらったいいねを月単位で1件、残りの種類を日ごとに1件ずつ」。
  月単位のもらったいいねを取得する場合は `initial` から選択日のもらったいいねを省く（現行の `shouldBackfillReceivedLikes`）。
- 取得結果は `JournalFetchResult(request, events, complete)` とし、`complete` のときだけ
  `coverage.markLoaded(request.dates, request.kinds)` する。現行の `completedKinds` の計算と同じ意味になる。

### 5.6 日付移動 `JournalDateNavigator`

`previousJournalDate`、`nextJournalDate`、`firstJournalDateInMonthOrStart`、`lastJournalDateInMonthOrEnd` を移す。
入力は `timeline.datesWithEntries(...)` と `today` だけにする。3.2 の食い違いは分類器の統一で解消する（6章 F4）。

### 5.7 時刻 `JournalClock`

```kotlin
class JournalClock(val zone: TimeZone = TimeZone.currentSystemDefault(), val now: () -> Instant = Clock.System::now) {
    fun today(): LocalDate
    fun dateOf(epochSeconds: Long): LocalDate
    fun startOfDay(date: LocalDate): Long
}
```

`monthStart()`、`nextMonth()`、`previousMonth()` などタイムゾーンに依存しない関数は `LocalDate` の拡張のまま残す。
`plusDays` は現行の「その日の0時に 86,400秒を足して日付に戻す」実装をやめ、`kotlinx.datetime` の `plus(DatePeriod)` にする。
現行の実装は、夏時間が終わる25時間の日に `plusDays(1)` が同じ日を返す。月の全日を列挙する
`generateSequence(monthStart) { it.plusDays(1) }.takeWhile { it <= lastDay }` が終わらなくなり、
該当地域では夏時間終了日を含む月を開くとバックフィルと `hasLoadedMonth` が無限ループする。
挙動の修正なので 6章 F5 で扱うが、優先度は高い。

### 5.8 エンゲージメント

```kotlin
data class JournalNoteEngagement(
    val summary: NoteEngagementState = NoteEngagementState(),  // 件数・自分のリアクション・保留中の操作
    val replyCount: Int = 0,
    val replies: List<NostrEvent> = emptyList(),
    val reactionEvents: List<NostrEvent> = emptyList(),
    val repostPubkeys: List<String> = emptyList(),
)

internal class JournalEngagementAggregator(noteIds: Set<String>, ownPubkey: String?) {
    fun add(event: NostrEvent): Set<String>          // 変化した noteId
    fun snapshot(ids: Set<String>): Map<String, JournalNoteEngagement>
}

internal fun Map<String, JournalNoteEngagement>.mergeProgressive(update: Map<String, JournalNoteEngagement>): Map<...>
internal fun Map<String, JournalNoteEngagement>.replaceCompleted(noteIds: Set<String>, update: Map<...>): Map<...>
```

- `JournalState` の11個の Map と `pendingEngagementOperations` を `engagement: Map<String, JournalNoteEngagement>` の
  1つにする。`JournalEngagementSnapshot` は削除する。
- 楽観的更新は `summary` を `NoteEngagementCoordinator` にそのまま渡す（現行の `noteEngagement()` / `withEngagement()` の
  組み立て・分解が不要になる）。
- `mergeProgressive` は現行 `withProgressiveJournalEngagement` と同じく件数を最大値で合成する。
  `replaceCompleted` は `pendingOperations` を保持したまま置き換える（完了した取得が送信中の楽観的更新を消さないため）。
  S4 では現行どおり件数は取得結果で置き換える。送信中の操作の差分を件数へ再適用する修正は F7 で扱う。
- 集計のイベント振り分け（現行 `fetchEngagement` の `collect` 内）は `JournalEngagementAggregator.add` に移し、
  通信は `JournalEngagementLoader` に分ける。
- 取得済みの noteId（`loadedEngagementNoteIds`）はローダーが持ち、月の更新時とリセット時に消す。

### 5.9 参照先とプロフィール

- 参照先は `EventByIdFetcher` で取得する。`quotedEvents` を `referencedEvents` に改名する。
  `EventByIdFetcher` は署名を検証するため、1回に渡す ID 数は可視範囲とその日の分に限る（現行も同じ範囲）。
- プロフィールはコントローラーが「表示に必要な公開鍵」の集合を持ち、`ProfileRepository.observe(pubkeys)` を
  購読して `state.profiles` に流す。取得の要求は `ProfileRepository.ensureProfiles` に委ねる。

### 5.10 画面状態

```kotlin
data class JournalState(
    val owner: JournalOwner? = null,
    val kinds: Set<JournalActivityKind> = defaultJournalKinds(),
    val selectedMonth: LocalDate,
    val selectedDate: LocalDate,
    val showCalendar: Boolean = true,
    val timeline: JournalTimeline = JournalTimeline.Empty,
    val coverage: JournalCoverage = JournalCoverage.Empty,
    val referencedEvents: Map<String, NostrEvent> = emptyMap(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val engagement: Map<String, JournalNoteEngagement> = emptyMap(),
    val visibleEntries: List<JournalActivity> = emptyList(),   // showCalendar に応じて日または月
    val entryCountsByDate: Map<LocalDate, Int> = emptyMap(),   // 選択中の種類で絞った選択月の件数
    val isLoading: Boolean = false,          // 選択日の取得中（現行と同じ意味）
    val error: String? = null,
    val engagementError: String? = null,
    val noteDeleteDialog: JournalNoteDeleteDialogState? = null,
) {
    val canGoNextMonth: Boolean
    val canGoNextDate: Boolean
}
```

- `kinds` を状態に持ち、画面は `viewModel.setKinds()` を呼ぶだけにする。表示の絞り込みと件数は状態から導出する。
- `visibleEntries` と `entryCountsByDate` は getter にせず、`timeline`、`kinds`、`selectedDate`、`selectedMonth`、
  `showCalendar` のいずれかを変える更新のときだけ `JournalStateReducer.withDerived()` で計算して保持する。
  getter にすると Compose が読むたびに再計算され、不変クラス内のメモ化は同期の問題を持ち込むため採らない。
  エンゲージメントやプロフィールの更新では再計算しない（現行の `JournalContent` と同じ方針）。
- `currentMonth()` / `currentDate()` の既定値はコンストラクタで `JournalClock` から与える。

### 5.11 コントローラーの分割

| クラス | 責務 | 主な移動元 |
| --- | --- | --- |
| `JournalController` | 公開操作、ジョブの取り消し、世代の確認、状態の更新 | 現行の公開メソッド、`loadMonth` / `loadDate` の骨格 |
| `JournalLoader` | `JournalFetchRequest` の実行と結果の返却 | `fetchEvents`、`fetchJournalEventGroup`、`backfillMonthEntries` の通信部分 |
| `JournalEngagementLoader` | 可視範囲の取得、進捗の間引き、完了判定 | `fetchEngagement` |
| `JournalReferenceLoader` | 参照先の取得とプロフィール対象の収集 | `fetchReferencedContent*`、`fetchProfile` |

- 世代の確認は `LoadToken(generation, monthStart)` にまとめ、すべての状態更新を
  `updateIfCurrent(token) { ... }` 経由にする。`loadDate` も同じ仕組みに載せる。
- `loadMonth` の初回取得・バックフィル・`loadDate` は、どれも「`JournalFetchPlanner` で計画→`JournalLoader` で実行→
  `timeline.upsert` と `coverage.markLoaded`」の同じ関数 `applyFetchResult` を通す。
- `JournalCalendarReducer` は `JournalDateNavigator` と合わせて `journal` パッケージへ移す。

### 5.12 下書き一覧

- `DraftListViewModel`（`accountSessionViewModel(key = "post-draft-list")`）と `DraftMemoRepository` を新設する。
- 移すもの: `JournalItem`（`DraftMemo` に改名）、`decodeMemo`、`memoIdentifier`、`mergeJournalMemos`
  （`mergeDraftMemos` に改名）、`addressTagValue`、`loadAllMemos`、`deleteDialog`、`deleteSelectedMemo`。
- 状態は `drafts`、`isLoading`、`error`、`deleteDialog` だけにする。並び順（`updatedAt` 優先の降順）は現行どおり。

### 5.13 画面ファイルの分割

| ファイル | 内容 |
| --- | --- |
| `JournalScreen.kt` | Scaffold、一覧、ダイアログ、`LaunchedEffect` |
| `JournalCalendar.kt` | `MemoCalendarHeader`、`MemoCalendarGrid`、`MemoCalendarDay`（`Journal*` に改名） |
| `JournalRows.kt` | `JournalActivityRow`、`JournalReactionIcon`、プレビュー文字列 |
| `JournalFilterHeader.kt` | フィルターのチップ、`JournalActivityKind` のラベルとアイコン |

`daysInMonth` / `isLeapYear` は `kotlinx.datetime` の月の長さで置き換える。

## 6. 移行手順

各段階は1コミット以上とし、段階ごとに `allTests`、`compileAndroidMain`、`compileKotlinIosSimulatorArm64` を通す。
S はふるまいを変えない構造整理、F は挙動の修正。

### S0: 現行の挙動を固定するテスト

- 分類: 0.2 の表の各行と、自分へのいいねが2種類に該当するケース。
- 取得フィルター: 種類の組み合わせごとの `fetchEvents` のフィルター（kind 1111 の分離、もらったいいねの取得先）。
- 取得済み判定: タイムアウト・未応答・全 EOSE。
- この時点では現行関数に対するテストとし、S2 以降で新しい関数へ付け替える。

### S1: 下書き一覧の分離（5.12）

`JournalState` から `memos`、`deleteDialog` を、`JournalLoadKind` から `Memo` を削除する。
`JournalViewModelTest` の下書き合成テストは `DraftMemoRepositoryTest` へ移す。

### S2: 種類と分類の統一（5.1、5.2）

`JournalActivityKind`、`JournalActivityClassifier`、`JournalEventRefs` を追加し、取得・表示・件数・日付移動を
付け替える。日付移動の食い違い（3.2）はこの段階では維持し、分類器に「日付移動用の旧規則」を一時的に残す。
`Article`、`loadedDates`、`currentFourWeekStart`、`JournalState.entryCountsByDate` を削除する。

### S3: 取得済み範囲・日付索引・取得計画（5.3〜5.7）

`JournalCoverage`、`JournalTimeline`、`JournalFetchPlanner`、`JournalDateNavigator`、`JournalClock` を追加し、
`JournalLoader` と `applyFetchResult` に取得処理をまとめる。`plusDays` の実装はこの段階では変えない。

### S4: エンゲージメントの集約（5.8）

`JournalNoteEngagement`、`JournalEngagementAggregator`、`JournalEngagementLoader` を導入する。
`JournalEngagementFetchTest` を新しい型へ移す。

### S5: 画面状態とファイル分割（5.10、5.13）

絞り込みを状態へ移し、画面ファイルを分ける。

### F5 の先行

F5（夏時間の無限ループ）は構造整理と独立している。日本時間の利用者には影響しないが、S0 の前に単独で入れてよい。

### F1: リセット時の取り残し

リレー切り替え時に `engagement`、`referencedEvents` を含む取得由来の状態をすべて空にする。
S4 で Map が1つになるため、この修正は S4 の直後に入れる。

### F2: 日の更新で削除済みの投稿を消す

`force` の日付取得が全リレー完了した場合に限り、`timeline.removeDate(date)` してから結果を反映する。
未完了なら既存を残す（取得失敗で一覧が消えないようにする）。月の更新も同じ規則にそろえる
（現行は取得前に消すため、失敗するとその月が空になる）。

### F3: 参照先とプロフィールの取得方法（5.9）

`EventByIdFetcher` に置き換える。取得先が「選択中のリレー」から「有効な全リレー」に変わる（8章 U2）。
プロフィールを `ProfileRepository.observe` に切り替える。

### F4: 日付移動の食い違い

S2 で残した日付移動用の旧規則を削除し、表示と同じ分類で移動先を決める。

### F5: 日付計算

`plusDays` / `minusDays` を `DatePeriod` による計算にする。

### F7: 送信中の楽観的更新と完了結果の競合

送信中（`pendingOperations` あり）に完了結果で件数を置き換えると、楽観的に足した +1 が消える。
その後に送信が失敗すると、`EngagementReducer.rollback` が差分を引くため、実際より1少ない件数になる
（0 で下限を切るだけで補正されない）。`replaceCompleted` で、取得結果に送信中の操作の `appliedDelta` を再適用する。
ただし、送信済みのリアクションが取得結果に既に含まれている場合は二重に数えるため、
自分の `ownLikeEventId` / `ownEmojiReactionEventIds` と照合してから適用する。Feed でも同じ問題があるかは別途確認する。

### F6: kind 1 の返信先の判定（任意）

エンゲージメント集計で kind 1 の返信先を「最後の `e` タグ」から `replyTargetId()` に変える。
Feed / Thread の返信数と一致するかを確認してから入れる（8章 U4）。

## 7. テスト方針

| 対象 | テスト |
| --- | --- |
| `JournalActivityClassifier` | 0.2 の各行、kind 1111 の非対応ルート、`-` のいいね、自分へのいいね、他人のジャーナルの Like |
| `effectiveJournalKinds` | 未選択の既定、`availableKinds` 外の除外、保存値の互換（`Article` を捨てる） |
| `JournalFetchPlanner` | 種類ごとのフィルターと取得先、取得済みの種類を除く、`force`、バックフィルの月単位もらったいいね |
| `JournalCoverage` | 完了時だけ記録、`hasLoadedMonth`（今日で打ち切る）、`forgetMonth` |
| `JournalTimeline` | 同一 ID の重複排除、日付境界（23:59:59 / 0:00:00）、`removeDate`、差分更新と全件構築の一致 |
| `JournalDateNavigator` | 月初・月末・今日での停止、隣の月への移動、種類で絞った移動先 |
| `JournalEngagementAggregator` | 種類ごとの振り分け、`#q` の引用、自分のいいねと絵文字、対象外の noteId を無視 |
| 合成関数 | 部分結果は減らさない、完了結果で置換、`pendingOperations` を保持 |
| `JournalController` | 世代が変わった後の結果を捨てる、`close()` 後に状態を更新しない（既存の回帰テスト） |
| `DraftMemoRepository` | 既存の `mergeJournalMemos` テストの移設、削除タグ（`e`、`a`、`k`、`client`） |

時刻に依存するテストはすべて `JournalClock` に固定の `now` と `TimeZone.UTC` を渡す。

## 8. 未確定事項

- **U1** 【決定】記事（kind 30023）はジャーナルから削除した。出す場合は`JournalActivityKind`・分類器・取得計画に1種類足し、
  行の表示を戻す。
- **U2** 【決定】参照先は`EventByIdFetcher`で有効な全リレーから取る。取得後に署名を検証する。
- **U3** 月のバックフィルは日ごとに最大31回の購読を行う。月単位の一括取得にすると1日500件の上限の意味が変わるため、
  本設計では変えない。
- **U4** 【決定】フィード（`FeedController.handleReplyEvent`）が`replyTargetId()`を使っていることを確認し、そろえた。
- **U5** 1日の取得上限（500件）を超えた日も「取得済み」と記録される。上限到達を検知して未完了扱いにするかは別途判断する。

## 9. 導入状況

### 9.1 実装結果（2026-09-28）

| 段階 | 結果 |
| --- | --- |
| S0 | 旧関数へのテストは書かず、0章の仕様を新しい純粋処理のテストとして書いた（7章）。旧実装と新実装を同じテストで比べる期間を置く利点が小さく、構造がほぼ全面的に変わるため |
| S1 | `DraftListViewModel`、`DraftMemoRepository`、`DraftMemo`（旧`JournalItem`）。削除ダイアログの文言を「下書き」にそろえた |
| S2〜S5 | `com.nostr.torinos.journal`に純粋処理と通信の境界を置き、`JournalController`は約1,840行から約630行になった。画面は4ファイルに分けた |
| F1〜F7 | すべて実装。F5 は`kotlinx.datetime`の`plus(1, DAY)`、F7 は`EngagementReducer.rebase`として共通化した |

### 9.2 設計から変えた点

- **通信の境界**: `JournalLoader` / `JournalEngagementLoader`をクラスとして置く代わりに、`JournalEventSource`（1回の有限取得）と
  `JournalEngagementSource`（投稿ID集合のエンゲージメント取得）の関数インターフェースにした。実装は`JournalLoaders.kt`の
  `NostrJournalEventSource` / `NostrJournalEngagementSource`。進捗の間引き・集計・完了判定はコントローラーと
  `JournalEngagementAggregator`に残し、テストで偽の取得元を差し込めるようにした。
- **参照先**: `JournalReferenceLoader`は作らず、`TargetEventFetcher`（既定は`EventByIdFetcher`）をコントローラーが直接使う。
- **プロフィール**: `JournalProfileSource`を挟んだ。既定は`ProfileRepository`で、監視する公開鍵の集合を広げるたびに
  `observe`を張り直す（`UserProfileViewModel`と同じ方式）。
- **取得計画**: `forMonthOpen`は作らず、`forDate`、`monthReceivedLikes`、`willFetchMonthReceivedLikes`の3つにした。
  バックフィルは各日の直前に取得済み範囲を読み直すため、事前に全日の計画を作ると初回取得や日付選択と重複するため。
- **`JournalCalendarReducer`**は`ui/post`に残した。`JournalState`（画面状態）だけを扱う2行の処理で、移す利点がないため。
- **`LoadToken`**は日付の取得でも確認するが、世代は進めない。進めると同じ月のバックフィルが止まるため。
- **リレー確定前は取得しない**: 保存済みのフィルターがあると、リレー確定前の`setKinds`で全リレーから取得し、
  直後のリレー確定で捨てていた。`setRelayUrl`が呼ばれるまで取得しない。
- **バックフィルの開始**: 旧実装は初回取得の中からバックフィルを起動していたため、初回取得中に別の日を選ぶと月のバックフィルが
  始まらなかった。バックフィルを別ジョブで起動し、初回取得（取り消された場合も）の終了を待ってから始める。
- **導出値の再利用**: `withDerived`は、中身が同じなら前の`visibleEntries` / `entryCountsByDate`をそのまま使う。
  バックフィルで別の日の結果が届くたびに一覧と可視範囲の監視が作り直されるのを防ぐ。
- **未使用の公開操作を削除**: `refreshToday`、`selectMonth`、`loadAllMemos`（下書きへ移動）。

### 9.3 自動検証

- `:composeApp:iosSimulatorArm64Test`（全件）と`:composeApp:compileAndroidMain`が成功。
- `JournalControllerTest`は偽の取得元で、初回取得→バックフィル、取得済み月の再取得なし、日の更新での削除反映（F2）、
  月の更新で失敗した日の保持、リレー切り替え時の破棄（F1）、エンゲージメントの重複取得なし、離れた月の結果の破棄、
  もらったいいねの月単位取得、署名者なしのエラー、種類で絞った日付移動を確かめる。
- テストでは`backgroundScope`の処理を`testScheduler.runCurrent()`で進める。`advanceUntilIdle()`は前景の処理が
  なくなると止まり、`backgroundScope`で起動したコントローラーのジョブを実行しない。

### 9.4 残課題

- U3（バックフィルの購読回数）、U5（1日500件の上限到達の検知）。
- 取得済みのアクティビティは月をまたいで保持し続け、上限がない（旧実装と同じ）。
- Feed / Channel / Thread のエンゲージメント状態を`JournalNoteEngagement`と同じ形へそろえるかは範囲外。
