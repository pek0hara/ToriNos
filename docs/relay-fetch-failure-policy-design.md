# 取り終えなかったリレーの扱いの共通化（ジャーナルのもらったいいね） 設計

作成日: 2026-10-14
状態: 実装・自動テスト済み（未コミット）。1〜13章は 2026-10-14 に Android 実機で確認。14章（遅いリレーを待たない）は Android 実機で確認。15章（もらったいいねも選択中のリレーから取る、2026-10-15）は実機の確認待ち。
**15章で、2章の目的1と4章（リレーを切り替えてももらったいいねを残す）は取りやめた。**
関連: `relay-unavailable-design.md`（5.3、11章）、`journal-month-fetch-design.md`（2章、4.3）、`feed-relay-merge-design.md`、`subscription-architecture-design.md`（8章）

## 1. 背景

ジャーナルで「もらったいいね」を表示すると、リレーを切り替えるたびに、月全体のもらったいいねを取り直していた。
Android 実機（ASUS_AI2302、有効6リレー）のログで、原因が2つ重なっていることを確かめた。

1. **リレーの切り替えで、もらったいいねも捨てていた。** `setRelayUrl` → `loadMonth(reset = true)` が、タイムライン・取得済みの記録（`JournalCoverage`）・参照先をすべて捨てる。
   もらったいいねは選択中のリレーではなく有効な全リレーから取る（`JournalFetchPlanner.receivedLikes`、`RelayTarget.AllEnabled`）ので、切り替えても結果は変わらない。
2. **もらったいいねが一度も「取得済み」にならない。** 取得済みは全リレーが EOSE を返したときだけ記録する（`shouldCommitJournalFetch`）。
   r.bitcoinhold.net が DNS で引けず `Unavailable` になるため、毎回 `complete=false` になる。`relay-unavailable-design.md` 11章で残る課題として挙げていたもの。

```
incomplete … wss://r.bitcoinhold.net=Unavailable(UnknownHostException …), ほか4つは Eose, r.ydg.works=TimedOut
fetch dates=2026-10-01..2026-10-14(14d) kinds=[ReceivedLike] target=AllEnabled events=37 complete=false
```

1 だけを直しても、2 のせいで取り直しは止まらない。

### 1.1 結果の受け取り方がばらばら

接続できないリレーを待たない処理と、リレーごとの結果（`FetchCompleted.outcomes`）は通信の層で共通になっている（`relay-unavailable-design.md`）。
しかし、その結果を「確定してよいか」「取り直すか」と読む規則は受け手ごとに別に書かれている。

| 受け手 | 確定の条件 | 失敗したリレー |
| --- | --- | --- |
| フィードの履歴（`RelayFeedHistoryCoordinator`） | リレーごとに `Eose` | CLOSED の理由で分岐し、2秒から倍・上限30秒で取り直す。`maxFailures` で諦める |
| フィードのリアクション数（`EngagementFetcher`） | リレーごとに `Eose` | 同じ分岐を別に実装。1秒から倍・上限30秒、4回まで取り直す |
| ジャーナル | 全リレーが `Eose` | 取り直さない（次に開いたとき全部取り直す） |
| プロフィール、下書きメモ、投稿のリレー探し 等 | 全リレーが `Eose` | 取り直さない |

CLOSED の再試行の判断（`RetryDisposition`）は `SubscriptionSignal.Closed` にしか載っておらず、フィードとリアクション数はシグナルを見て自分で覚えている。

## 2. 目的

1. リレーを切り替えても、リレーの選択によらない結果（もらったいいね）を取り直さない。
2. ジャーナルの取得を、フィードと同じ規則にする。取り終えたリレーの分は確定し、取り終えなかったリレーだけを間を空けて取り直し、決まった回数で諦める。
3. 結果を読む規則を通信の層の1か所にまとめ、フィード・リアクション数・ジャーナルが同じものを使う。

## 3. 非目標

- プロフィール、下書きメモ、投稿のリレー探し、ID での取得、チャンネル一覧の判定は変えない。共通の規則に乗せ替えられる形にだけしておく。
- フィードの動き（待ち時間、諦める回数、CLOSED の扱い）は変えない。書き方だけを置き換える。
- 諦めたリレーの復旧を検知して取り直すこと（`relay-unavailable-design.md` 11章で挙げた案）。フィードも回数で諦め、次の復帰か手動の再試行で取り直すので、それにそろえる（10章）。
- 応答しないリレー（`TimedOut`）を待つ時間（ジャーナルは8秒）を縮めること。
- 接続できないリレーを設定から外すこと。

## 4. リレーの切り替え（`loadMonth(reset = true)`）

> 2026-10-15: 15章でもらったいいねも選択中のリレーから取ることにしたため、この章の「もらったいいねを残す」は取りやめた。
> リレーを切り替えたら、参照先以外はすべて捨てて取り直す。参照先を残す点だけは続ける。

選択中のリレーから取った状態だけを捨てる。

| 状態 | 切り替え時 | 理由 |
| --- | --- | --- |
| `coverage` のもらったいいね | 残す | 有効な全リレーから取るので、切り替えても変わらない |
| `coverage` のそのほかの種類 | 消す | 選択中のリレーから取っている |
| タイムラインの行 | もらったいいねの役割だけ残す。役割がなくなった行は消す | 1つのいいねが「A がした（Like）」と「B がもらった（ReceivedLike）」の両方に当たることがある。Like 側は新しいリレーで取り直し、取れれば `upsert` で全部の役割に戻る |
| `referencedEvents` | 残す | ID で取るイベントは中身が変わらない。見つからなかった ID は保存しないので、次の取得でまた探す |
| `engagement` | 消す | `engagementSource` は選択中のリレーから取っている |

- `RelayIndependentKinds = setOf(ReceivedLike)` を1か所に置く。`JournalFetchPlanner` が全リレーから取る種類と一致させる。
- `JournalCoverage.retainKinds`、`JournalTimeline.retainKinds` を足す。

## 5. 結果を読む規則（`network/RelayFetchPolicy.kt`）

### 5.1 `RelayOutcome.Closed` に再試行の判断を載せる

```kotlin
data class Closed(val reason: String, val retry: RetryDisposition = RetryDisposition.RetryWithBackoff) : RelayOutcome
```

`NostrRepository` が `Closed` を作るとき、すでに計算している `classification.disposition` を入れる。
`FetchCompleted.outcomes` だけで判定できるようになる。既定値があるので、既存の呼び出しとテストは変えなくてよい。

### 5.2 リレーごとの判定

| 結果 | `RelayFetchVerdict` |
| --- | --- |
| `Eose` | `Succeeded` |
| `Unavailable`、`TimedOut`、結果なし | `RetryWithBackoff` |
| `Closed(RetryWithBackoff)` | `RetryWithBackoff` |
| `Closed(RetryOnFilterChange)` | `IsolateFilters` |
| `Closed(RetryAfterAuth)`、`Closed(DoNotRetry)` | `Stop` |

`RelayOutcome?.verdict()` と `RetryDisposition?.verdict()`（CLOSED を受けていなければ null）を用意する。

### 5.3 1回の取得のまとめ（`RelayFetchSummary`）

- `succeeded`、`failed`（`Succeeded` 以外と、その判定）、`retryable`（`RetryWithBackoff`）
- `anySucceeded`: どれかが取り終えた。取れた分を確定してよい。
- `allSucceeded`: 全部取り終えた。取得結果で既存を置き換えてよい。

`NostrRepository` は時間切れのとき、結果のないリレーを `TimedOut` で埋めるので、`outcomes` には対象の全リレーがそろう。

### 5.4 待ち時間（`RelayRetryPolicy`）

`delayAfter(failureCount)`: 続けて `failureCount` 回失敗した後の待ち時間。`baseDelayMillis` から倍にし、`maxDelayMillis` で止める。`maxFailures` 回に達したら null（諦める）。

| 使うところ | 待ち | 諦める | 今との差 |
| --- | --- | --- | --- |
| フィードの履歴 | 2秒から倍、上限30秒 | `maxFailures`（呼び出し側の引数。差分取得は3） | なし |
| フィードのリアクション数 | 1秒から倍、上限30秒 | 5回目の失敗（4回まで取り直す） | なし |
| ジャーナル | 2秒から倍、上限30秒 | 3回目の失敗（取り直しは2回） | 新規。フィードの差分取得と同じ |

## 6. フィードへの適用（動きは変えない）

- `RelayFeedHistoryCoordinator`
  - `scheduleRetry` の CLOSED の分岐を `relay.closedDisposition.verdict()` で書く。
  - 手動の再試行で飛ばすリレーの判定を `verdict() == Stop` にする。
  - `finishPage` の成功の判定を `verdict() == Succeeded` にする。
  - 待ち時間を `RelayRetryPolicy.history().delayAfter` から取る。`closedDisposition` は手動の再試行と UI の数（`awaitingAuthRelayCount`）にも使うので、状態としては残す。
- `EngagementFetcher`
  - 成功の判定と CLOSED の分岐を `verdict()` で書く。
  - 待ち時間を `RelayRetryPolicy(1_000, 30_000, maxFailures = 5)` から取る。

## 7. ジャーナルへの適用

### 7.1 取得結果

`JournalFetchStatus`（取得元が返す）と `JournalFetchResult` に次を持たせる。

| 項目 | 意味 | 求め方 |
| --- | --- | --- |
| `complete` | 取得済みにしてよい | `anySucceeded` |
| `allRelaysFinished` | 取得結果で置き換えてよい | `allSucceeded` |
| `retryRelays` | 間を空けて取り直すリレー | `retryable` |

送るものがなかった取得（フィルターが空）は、全部取り終えたものとする。`shouldCommitJournalFetch` は消す。

### 7.2 取得済みの記録の意味が変わる

`journal-month-fetch-design.md` 2章3項は、取得済みを「その日・その種類・そのメンバーは全リレーから取り切った」と定めている。これを次に改める。

> その日・その種類・そのメンバーを、1つ以上のリレーから取り切った。取り終えなかったリレーは、取り直しているか、諦めた。

- 置き換え（手動の更新、`applyResults` の `replace`）は、今までどおり全リレーが取り終えたときだけ行う。取り終えなかったリレーにしかない行を消さないため。条件を満たさないときは足すだけにする。
- `JournalRangePager` は、ここまでのページを全リレーが取り終えたか（`allRelaysFinished`）を積み、ページの結果に載せる。どれかのページで取り終えなかったリレーがあれば、そのページ以降で取り切れた日も置き換えない。
- 取り終えなかったリレーがあっても、取り終えたリレーがあれば次のページを取りに行く。`relay-unavailable-design.md` 11章の2つ目（不完全な結果で次のページを取らない）もこれで解消する。

### 7.3 取り直し（`JournalRelayRetries`）

- 失敗はリレーごとに数える。同じリレーで待っている間に失敗した取得（選択日、月の残り、もらったいいね、リアクション数）は、同じ待ちにまとめて順に取り直す。
- 取り直す内容は、元のリクエストの対象を `RelayTarget.Single(url)` に替えたもの。日付の範囲は元のままで、打ち切られたらページ送りで続きを取る。結果は足すだけにする。
- 成功したら失敗の回数を戻す。待ち時間と回数は 5.4。
- 取り直しで増えた行の参照先（返信の親など）も取る。
- リアクション数は、最初の取得の集計に取り直した分を足し、表示へは足すだけで反映する（`mergeProgressive`）。同じイベントは1回だけ数える。
  取り直しの結果で置き換えはしない。集計には最初の取得の後に表示へ入った分（確定した自分のいいね等）が含まれず、置き換えると消えるため（レビューで見つけた不具合）。
- 取り直しの処理が例外を投げたら、失敗として数える（キャンセルは除く）。待ちの記録が残ったままだと、そのリレーの取り直しを受け付けなくなるため。
- 諦めたリレーは、取り直しをやめるとき（7.4）まで受け付けない。フィードが `maxFailures` に達したリレーを、手動の再試行か復帰まで取らないのと同じ。

### 7.4 取り直しをやめるとき

**月の移動、リレーの切り替え、手動の更新のときだけやめる。** 種類やメンバーの切り替えではやめない。

- `loadMonth` は種類・メンバーの切り替えでも呼ばれる。先行実装では `loadMonth` のたびにやめていたため、もらったいいねを取り直している途中に種類を切り替えると取り直しが消えていた。取得済みの記録はもう付いているので、そのリレーの分は手動で更新するまで取れない（レビューで見つけた不具合）。
- 取り直しは `isCurrent(token)`（`loadMonth` のたびに進む世代）で結果を捨てない。やめるときは `cancelAll` で処理ごと止める。
- 取り直しの結果は、その時点のメンバーで判定する（`applyResults` と同じ）。
- カレンダー表示での手動の更新（`loadDate(force = true)`、日単位）ではやめない。その日を取り直すだけで、月の残りの取り直しは無駄にならない。

### 7.5 ログ

`[Journal]` に出す。

- `reset keeps rows=…/… coveredDays=…/… refs=…`
- `incomplete <id> complete=… retry=[…] outcomes=…`
- `retry relay=… attempt=… in=…ms works=…`、`retry relay=… kinds=… accepted=…`、`retry done relay=…`、`relay gave up relay=… failures=… dropped=…`

## 8. テスト

| 対象 | 確かめること |
| --- | --- |
| `RelayFetchPolicyTest` | 結果ごとの判定、`anySucceeded`・`allSucceeded`・`retryable`、待ち時間（倍・上限・諦める回数） |
| `JournalEngagementTest` | `JournalFetchStatus.of`（全部 EOSE、一部 `Unavailable`、一部拒否、全部時間切れ、結果なし） |
| `JournalRelayRetriesTest` | 2秒・4秒で取り直し3回目で諦める、待っている間の失敗をまとめる、成功で回数を戻す、リレーごとに数える、例外を失敗として数える、諦めたリレーは `cancelAll` まで受け付けない、`cancelAll` |
| `JournalTimelineTest` | `retainKinds`（タイムライン・取得済みの記録） |
| `JournalControllerTest` | 下の表 |
| フィードの既存テスト | すべて通る（動きを変えていない確認） |

`JournalControllerTest` で確かめること:

- リレーを切り替えても、もらったいいねのリクエストを出さず、行が残る。前のリレーにしかない自分の投稿・したいいねは消える
- リレーを切り替えても参照先を取り直さない
- 1つのリレーが落ちていても取得済みになり、そのリレーだけを取り直して行が増える
- 落ちたままのリレーは2回取り直して諦める。拒否したリレーは取り直さない
- 手動の更新で、取り終えなかったリレーがあれば行を消さない
- 1つのリレーが落ちていても、リレーを切り替えたときにもらったいいねを取り直さない
- リアクション数を取り直したリレーの分を、二重に数えずに足す。次に表示しても取り直さない
- リアクション数の取り直しは足すだけで、前に表示していた分を消さない
- **種類を切り替えても、もらったいいねの取り直しが続く**（7.4）
- **月を移動したら、元の月の取り直しをやめる**（7.4。移動先の月も同じリレーで失敗して取り直すので、元の月の日付のリクエストが出ないことで確かめる）

`settle()` は時間を進めない（`runCurrent`）。取り直しは `backgroundScope` で動くので、`advanceUntilIdle` ではなく `advanceTimeBy` で時間を進める。

## 9. 実機での確認（Android）

1. ジャーナルで「もらったいいね」を選び、接続できないリレーがあっても1回で取得済みになること（`incomplete … complete=true`）。
2. 取り終えなかったリレーだけを取り直し、成功・諦めがログに出ること。
3. リレーを2回切り替えて、`loadMonth cached` になり、もらったいいねのリクエストが出ないこと。行が画面に残ること。
4. 取り直しの待ち中に種類を切り替えても、取り直しが続くこと。

## 10. 残る制約

- 諦めた後（3回目の失敗）は、そのリレーにしかないデータを手動で更新するまで取らない。フィードと同じ。
  諦めたリレーの復旧を検知して取り直すには、取得済みの記録にどのリレーを除いたかを持たせる必要がある。`relay-unavailable-design.md` 11章の案で、必要になったら別に設計する。
- `IsolateFilters`（複合のフィルターの拒否）は、ジャーナルでは取り直さない。ジャーナルのリクエストはフィルターが1〜2個で、分けて送る価値が小さい。
  選択中のリレーがこれを返すと、その取得は確定しない（今と同じ）。
- 応答しないリレー（`TimedOut`）は、時間切れ（8秒）まで待ってから確定する。もらったいいねの表示はその分遅れる。
- 諦めるまでの回数は、月を開いている間だけ数える（取り直しをやめると回数も戻す）。月を開き直すと、また2回取り直す。
- どのリレーにもつながっていない（オフライン）ときは、通信の層が時間切れまで待つ（`relay-unavailable-design.md` 4章）。全リレーが `TimedOut` になり、取得は確定せず、全リレーがそれぞれ取り直しの対象になる。
  通信が戻れば、最初に取り終えたリレーの分で取得済みになる。戻らなければ、リレーごとに2回取り直して諦める。

## 11. 進め方

1. この設計書のレビュー。
2. 7.4 のテストを先に書き、失敗することを確かめる。
3. 7.4 を実装する。
4. コードのレビュー、指摘の修正。指摘がなくなるまで 2〜4 を繰り返す。
5. 全テスト、Android 実機の確認（9章）。
6. `relay-unavailable-design.md`（3章、5.3、11章）と `journal-month-fetch-design.md`（2章）を更新する。

## 12. 実機での確認結果（Android、ASUS_AI2302、2026-10-14）

有効6リレー。r.bitcoinhold.net は DNS で引けない。r.ydg.works はこの回は時間切れになった。

```
incomplete … complete=true retry=[wss://r.bitcoinhold.net, wss://r.ydg.works] outcomes=… 4つは Eose …
fetch dates=2026-10-01..2026-10-14(14d) kinds=[ReceivedLike] target=AllEnabled events=37 complete=true
retry relay=wss://r.bitcoinhold.net attempt=1 in=2000ms works=1
retry relay=wss://r.ydg.works attempt=1 in=2000ms works=1
retry relay=wss://r.bitcoinhold.net attempt=2 in=4000ms works=1
relay gave up relay=wss://r.bitcoinhold.net failures=3 dropped=1
retry relay=wss://r.ydg.works kinds=[ReceivedLike] accepted=37
retry done relay=wss://r.ydg.works
setRelayUrl url=wss://yabu.me (previous=wss://r.kojira.io) -> reset
reset keeps rows=37/62 coveredDays=14/14 refs=24
loadMonth cached: 2026-10-01 already loaded for [ReceivedLike] …
setRelayUrl url=wss://relay-jp.nostr.wirednet.jp (previous=wss://yabu.me) -> reset
loadMonth cached: 2026-10-01 already loaded for [ReceivedLike] …
```

- 1回目の取得で取得済みになり、取り終えなかった2つのリレーだけを取り直した。
- リレーを2回切り替えても、もらったいいねのリクエストは出ず、37件が画面に残った。
- 9章の4: 7.4 を直した版で、取り直しを待っている間（最初の取得から0.3秒後）に種類を「もらったいいね」から「投稿＋もらったいいね」に替えた。
  取り直しはやめずに続き、r.ydg.works の分（37件）を取り、r.bitcoinhold.net は3回目の失敗で諦めた。

```
retry relay=wss://r.ydg.works attempt=1 in=2000ms works=1
setKinds selected=[Post, ReceivedLike] effective=[Post, ReceivedLike]
loadMonth … reset=false generation=4 kinds=[Post, ReceivedLike]
fetch … kinds=[ReceivedLike] target=Single(url=wss://r.ydg.works) events=37 complete=true
retry done relay=wss://r.ydg.works
relay gave up relay=wss://r.bitcoinhold.net failures=3 dropped=1
```

## 13. レビューの記録

| 段階 | 指摘 | 対応 |
| --- | --- | --- |
| 設計 | 種類・メンバーの切り替えで `loadMonth` が取り直しをやめてしまう | 7.4。月の移動・リレーの切り替え・手動の更新だけでやめる。テストを先に書いて失敗を確かめてから直した |
| 設計 | カレンダー表示の手動の更新（日単位）の扱いが決まっていない | やめない（7.4） |
| 設計 | オフラインのときの挙動が書かれていない | 10章 |
| コード | リアクション数の取り直しがそろうと置き換え、最初の取得の後に確定した自分のいいねが消えうる | 足すだけにした（7.3）。`stoppedRelays` は使わなくなったので消した |
| コード | 取り直しの処理が例外を投げると、そのリレーの待ちが残り、以後受け付けない | 例外を失敗として数える（7.3） |
| コード | テストの期待（諦めた後も受け付ける）がフィードの規則と違った | 諦めたリレーは `cancelAll` まで受け付けないのが正しい。テストを直し、その性質もテストにした |
| コード | フィード・リアクション数の置き換えで動きが変わっていないか | 待ち時間、諦める回数、`resumeWithBackoff` の待ち（失敗0回で2秒）、CLOSED の分岐が同じことを確かめた。既存テストもすべて通る |


## 14. 遅いリレーを待たない（2026-10-14 追記）

### 14.1 観察（Android 実機、`[Fetch]` ログ）

もらったいいねの表示は、11章までの変更では速くならなかった。リレーごとの応答は次のとおり。

| リレー | 結果 | REQ からの時間 |
| --- | --- | --- |
| yabu.me | EOSE（37件） | 0.17秒 |
| relay-jp.nostr.wirednet.jp | EOSE（32件） | 0.35秒 |
| r.kojira.io | EOSE（37件） | 0.36秒 |
| nos.lol | EOSE（16件） | 0.60秒 |
| r.bitcoinhold.net | 接続できない（待たない） | すぐ |
| r.ydg.works | REQ に何も返さない | 8.0秒（時間切れ） |

表示に要るものは0.6秒でそろっているが、取得は全リレーの結果がそろうまで終わらない。
月一覧は取得が終わるまでスケルトンを出す（`journal-month-fetch-design.md` 10章）ので、毎回8秒待つ。
r.ydg.works はつながっているので、「接続できないリレーを待たない」（`relay-unavailable-design.md`）の対象にならない。

### 14.2 方式

フィードの履歴（`HISTORY_RELAY_SETTLE_DELAY_MS`）と同じく、**最初のリレーが EOSE を返してから1秒**待って、まだ終わっていないリレーを待たない。

- 通信の層: `SubscriptionSession.finishPendingRelays()` を足す。有限取得で、まだ結果のないリレーを `TimedOut` にして、いま完了させる（時間切れと同じ `completeFetch(timedOut = true)`）。
  `Unavailable`・`Closed`・`Eose` の結果はそのまま。配信中の購読（Live）と完了済みの取得では何もしない。
  既定の実装は何もしない（テストの偽のセッションを変えずに済む）。いつ呼ぶかは呼び出す側が決める（`ChannelListFetch` が待ちを呼び出し側で持つのと同じ）。
- ジャーナル（`collectFetch`）: `settleMillis` を受け取り、最初の `SubscriptionSignal.Eose` から `settleMillis` 後に `finishPendingRelays()` を呼ぶ。完了の知らせ（`FetchCompleted`）で受信を終える流れは変えない。
  投稿の取得（`NostrJournalEventSource`）、リアクション数（`NostrJournalEngagementSource`）で1秒にする。メンバー候補（`NostrJournalInteractionSource`）は届いた分から出しているので変えない。
- 終えた時点で終わっていなかったリレーは `TimedOut` なので、7.3 の取り直し（2秒・4秒、3回目の失敗で諦める）に回る。取り直しは対象が1リレーなので、待ちは効かない（最初の EOSE で終わる）。

### 14.3 期待する効果と代わりに起きること

- 今回の環境で、もらったいいねの表示が約8秒から約1.2秒（最初の EOSE 0.17秒＋1秒）になる見込み。日表示、投稿の月の取得、「すべてのリレー」を選んだとき、リアクション数も同じ。
- フィードは遅いリレーの取得を裏で続けるが、ここでは打ち切って取り直す。ジャーナルは全リレーを1つの購読で送るので、遅いリレーだけ残すには取得元の作りを大きく変える必要があるため。
  - 遅いが答えるリレー（1〜8秒）には REQ が1回多く飛ぶ。そのリレーにしかない行は2〜3秒後に足される。
  - 答えないリレーは、取り直しのたびに8秒つながったままになり、2回で諦める。裏の通信で表示は待たせない。
- 手動の更新で置き換える条件（全リレーが取り終えた）は変えない。答えないリレーがある間は、今までどおり置き換えずに足すだけになる。
- 最初の EOSE から1秒は、一覧がほぼ空のリレー（0件ですぐ EOSE）が最初でも同じ。データを持つリレーが1秒以上かかると、その分は取り直しで後から足される。フィードも同じ。

### 14.4 テスト

| 対象 | 確かめること |
| --- | --- |
| `JournalEventSourceTest` | 最初の EOSE から1秒で `finishPendingRelays` を呼ぶ。終わっていないリレーは取り直しに回る。全リレーが1秒以内に終われば呼ばない。EOSE が1つもなければ呼ばない |
| `JournalEngagementSourceTest`（新規） | リアクション数も同じ待ちで終える |
| 通信の層 | `finishPendingRelays` は単体テストの仕組みがない（`NostrRepository` を偽のリレーで動かすテストがない）。実機の `[Fetch]` ログで、時間切れと同じ形の `COMPLETE` が約1秒で出ることを確かめる |

### 14.5 レビューの記録

| 段階 | 指摘 | 対応 |
| --- | --- | --- |
| 設計 | 取得元は対象のリレーの一覧を持たず、終えたときに結果のないリレーを名指しできない | 通信の層に `finishPendingRelays` を足し、正しい `FetchCompleted` を返させる |
| 設計 | 偽のセッションが7つあり、操作を足すと全部直すことになる | 既定の実装を何もしないものにする |
| 設計 | `NostrRepository` の時間切れの処理には別の作業の未コミットの変更がある | そこには手を入れず、別の関数として足す |
| コード | 参照先の取得（`EventByIdFetcher`、選択中のリレーで見つからない分をほかの全リレーへ）は待ちの対象外で、答えないリレーがあると8秒待つおそれがある。月一覧は参照先がそろうまで出ない | 実機で測ると、参照先は選択中のリレーから0.1秒で全部取れ、待ちは起きなかった。`EventByIdFetcher` は共有の部品なので今回は変えず、残る制約とする（14.7） |
| コード | タイマーと自然な完了が重なったとき、取り消されたとき、取り直し（1リレー）のとき | `completeFetch` は完了済みなら何もしない。`finally` でタイマーとセッションを閉じる。1リレーは最初の EOSE で完了し、タイマーはすぐ止まる |

### 14.6 結果（Android 実機、2026-10-14）

| | 変更前 | 変更後 |
| --- | --- | --- |
| もらったいいねを選んでから月一覧が出るまで | 8.0秒（r.ydg.works の時間切れ待ち） | 1.1秒 |

```
22:35:45.379 loadMonth … kinds=[ReceivedLike]
22:35:46.457 incomplete … complete=true retry=[wss://r.bitcoinhold.net, wss://r.ydg.works] …
22:35:46.474 backfill done …
22:35:52.190 fetch … target=Single(url=wss://r.ydg.works) events=37 complete=true
22:35:52.198 retry done relay=wss://r.ydg.works
```

- r.ydg.works は答えないのではなく遅いリレーだった（取り直しで約3.7秒で37件）。待たなかった分は取り直しで足された。
- 自動テストは1590件すべて通った（`JournalEventSourceTest` に待ちのテスト4件を足した）。

### 14.7 残る制約

- 参照先の取得（`EventByIdFetcher`）は待ちを入れていない。選択中のリレーにない参照先があり、ほかに答えないリレーがあると、月一覧が出るまで時間切れを待つことがある。必要になったら、`finishPendingRelays` を使って同じ待ちを入れる。
- 最初の EOSE から1秒の待ちは、プロフィール・下書きメモなど、ほかの取得には入れていない。

## 15. もらったいいねも選択中のリレーから取る（2026-10-15 仕様変更）

### 15.1 背景と決定

ジャーナルは選択中の1リレーを見る画面で（`journal-refactor-design.md` 0.2）、もらったいいねだけが例外として有効な全リレーから取っていた（届く先が読めないため）。
14章の計測で、遅いリレーを待っていたのはこの例外の取得だけとわかった。投稿・返信・リポスト・したいいね・リアクション数は1リレーなので、もともと待ちは起きない。

各リレーが持っていたもらったいいね（2026-10-14、37件）: yabu.me 37、r.kojira.io 37、r.ydg.works 37、relay-jp 32、nos.lol 16。

**決定（利用者）: もらったいいねも選択中のリレーだけから取る。** ジャーナル全体を「そのリレーに何があるかを見る」で一貫させる。
選択中のリレーに届いていないいいねは出ない（上の例で relay-jp なら5件、nos.lol なら21件が出ない）。

### 15.2 方式

| 項目 | 変更前 | 変更後 |
| --- | --- | --- |
| もらったいいねの取得先（`JournalFetchPlanner.receivedLikes`） | 有効な全リレー | 選択中のリレー。未選択（`relayUrl == null`）なら有効な全リレー（投稿などと同じ規則） |
| リクエスト | 投稿などとは別 | 別のまま（フィルターが `#p` で違い、取得済みの記録も種類ごとに付けるため。1つの REQ にまとめるのは今回はしない） |
| リレーの切り替え（4章） | もらったいいねの行・取得済みの記録を残す | **すべて捨てて取り直す**（もらったいいねもリレーに依存するようになったため）。ID で取った参照先は今までどおり残す |
| `ReactionEventStore` からの先出し（`withCachedReceivedLikes`） | 通信前に、端末に保存済みのもらったいいねを入れる | **やめる** |

- 先出しをやめる理由: `ReactionEventStore` はフィード・通知など、どのリレーから届いたリアクションも保存していて、届いたリレーを持たない。
  入れると、選択中のリレーにないいいねが出る。リレーを切り替えて捨てた直後にも、前のリレーの分がまた入る。
  月一覧は取得が終わるまでスケルトンなので、先出しは月一覧には効いていなかった。日表示では即時の表示がなくなるが、1リレーの取得は0.2秒前後。
- 4章で足した `JournalCoverage.retainKinds`・`JournalTimeline.retainKinds`・`RelayIndependentKinds` は使わなくなるので消す。`reset keeps …` のログも消す。
- 14章の待ち（最初の EOSE から1秒）と7章の取り直しは残す。選択中のリレーでは最初の EOSE で終わるので効かず、未選択（全リレー）のときと、取り直しの規則（選択中のリレーが落ちているとき）で使う。

### 15.3 期待する効果

- もらったいいねの表示: 約1.1秒 → 選択中のリレーの応答（今回の環境で約0.2〜0.4秒）。
- リレーの切り替え: もらったいいねも取り直すが、1リレーなので約0.2〜0.4秒。

### 15.4 テスト

| 対象 | 確かめること |
| --- | --- |
| `JournalFetchPlannerTest` | `forDate`・`forRange` のもらったいいねが選択中のリレー（`Single`）宛て。未選択なら `AllEnabled` |
| `JournalControllerTest` | 月のもらったいいねを選択中のリレーから取る。リレーを切り替えると、もらったいいねも新しいリレーから取り直し、前のリレーの行は残らない。参照先は残す。保存済みのもらったいいねを先に入れない |
| `JournalControllerTest`（7章の取り直し） | 全リレーを対象にした取得で確かめていたテストは、未選択（`setRelayUrl(null)`）で確かめる |
| `JournalTimelineTest` | `retainKinds` のテストを消す |

### 15.5 設計レビュー

| 指摘 | 対応 |
| --- | --- |
| 先出しを残すと、選択中のリレーにないいいねが出る。リレーの切り替えで捨てた直後にも戻る | 先出しをやめる（15.2） |
| もらったいいねを投稿のリクエストに混ぜれば REQ が1本減る | 取得済みの記録と打ち切りの判定がリクエスト単位なので、混ぜると種類ごとの判定が崩れる。今回は分けたまま |
| 1リレーでも、選択中のリレーが落ちていれば待つ | 接続できなければ通信の層ですぐ `Unavailable`、答えなければ8秒の時間切れ（今までの投稿の取得と同じ）。取り直しは7章のとおり |

### 15.6 実装とコードレビュー

- `JournalFetchPlanner.receivedLikes` に `relayUrl` を渡し、投稿などと同じ規則で宛先を決める。
- `JournalController` から `cachedReceivedLikes`・`withCachedReceivedLikes`・`RelayIndependentKinds`・`reset keeps` のログを消し、リレー切り替えでタイムラインと取得済みの記録を空にする（参照先は残す）。
- `JournalCoverage.retainKinds`・`JournalTimeline.retainKinds` とそのテストを消す。
- テストを先に直し、取得先を確かめる5件が失敗することを確かめてから実装した。全1588件が通る。
- コードレビューでは、消した関数・引数の残り、変更したファイルの新しい警告がないことを確かめた。新しく足したテストの試験的 API の警告は、クラスに `@OptIn` を付けて消した。

### 15.7 iOS 実機の観察（2026-10-15 00:41）

> 2026-10-15 12:40 訂正: このときの iPhone のアプリは 10-14 22:17 の最初のビルドのままで、14章（1秒の待ち）も入っていなかった。
> `xcodebuild` の後のビルドで Kotlin のフレームワークを作るビルドフェーズが実行されず、古いフレームワークが入り続けていた（`reset keeps` のログが出ていたことで気づいた）。
> 約6秒は、待ちのない版で遅いリレー（r.ydg.works）の EOSE を待っていたためで、「選択中以外のリレーへの通信が遅い」という下の推測は誤り。
> 以後、iOS はビルドの出力を消してから作り直して入れる。

以下は当時の記述。

- もらったいいね（全リレー宛て）を選んでから一覧が出るまで約6秒。全リレーが EOSE を返していた（r.ydg.works も）。1秒の待ちで打ち切られたリレーはない。
  最初の EOSE そのものが約6秒後だったとみられる。選択中のリレー（yabu.me）だけに送る投稿の取得は1秒以内に終わっていた。
- 選択中以外のリレーへの通信が遅い（使っていない接続のつなぎ直しなど）可能性がある。iOS の `[Fetch]` ログを取っていないので原因は未確認。
- 15章でもらったいいねも選択中のリレーに送るようにしたので、表示には効かなくなる見込み。ほかの全リレー宛ての取得（参照先の残り、リレー未選択のとき）では残りうる。


## 16. 最終レビュー（2026-10-15 02:10、予約していたレビュー）

対象は今回の作業分の差分全体（`RelayFetchPolicy`、フィード・リアクション数の置き換え、`JournalRelayRetries`、`finishPendingRelays` と1秒の待ち、もらったいいねを選択中のリレーから取る変更、設計書）。記事・下書きまわりの別作業の変更は対象外。
「リレー切り替えでもらったいいねを残す変更」は15章で取りやめたので、取りやめた後の状態を見た。

| 観点 | 結果 |
| --- | --- |
| 待ちで打ち切ったリレー | `completeFetch` が購読を閉じるので、打ち切ったリレーにも CLOSE を送り、同時購読数の枠を空ける |
| `finishPendingRelays` を呼ぶ時点 | 最初の EOSE の後なので、対象のリレーの一覧（`fetchExpectedRelays`）は決まっている。何もせずに終わる心配はない |
| 取り直しを途中でやめたとき | 単一スレッドで、取り消しは中断点でだけ起きるので、待ちの記録が残らない |
| 選択中のリレーが1つだけのとき | 待ちは最初の EOSE で終わって効かない。取り直しは同じリレーに送る |
| 新しい判定の使われ方 | `IsolateFilters` などはフィードとリアクション数で使われている。使われていない公開 API はない |
| 空白の誤り | `git diff --check` で問題なし |
| ドキュメント | `docs/README.md` の説明が取りやめた変更（リレー切り替えでもらったいいねを残す）を指していた → 直した |

コードの指摘はなく、テストの追加は不要だった。全1588件が通る。

残っていること:
- Android 実機での15章の確認（端末のロックのため未実施）。
- iOS 実機での15章の確認（新しいビルドの入れ直しが必要）。
- iOS で、選択中以外のリレーへの通信が遅い原因（15.7）は未確認。

## 17. 15章の実機確認（Android、ASUS_AI2302、2026-10-15 12:09）

| 操作 | 取得先 | 件数 | もらったいいねの取得にかかった時間 |
| --- | --- | --- | --- |
| relay-jp を選んだ状態で「もらったいいね」を選ぶ | `Single(relay-jp.nostr.wirednet.jp)` | 32 | 0.11秒（選んでから月一覧の取得完了まで0.12秒） |
| yabu.me に切り替える | `Single(yabu.me)` | 37 | 0.10秒 |
| nos.lol に切り替える | `Single(nos.lol)` | 16 | 0.71秒 |

- もらったいいねは選択中のリレーにだけ送られ、件数はそのリレーが持つ分（14.1 の計測と同じ）になった。
- リレーを切り替えると前のリレーの行は消え、新しいリレーの分だけが出た（画面で「この月・16件」）。
- 全リレー宛てのときの約1.1秒（14章）、約8秒（14章より前）から、選択中のリレーの応答時間だけになった。

### 17.1 iOS 実機（iPhone 16、2026-10-15 12:35、ビルドを消して作り直した版）

- もらったいいねは `Single(yabu.me)` に送られ、選んだのと同じ秒に37件そろった（`complete=true`、取り直しなし）。
- 投稿・リアクション数も yabu.me から1秒以内にそろった。
- 同じ時間に開いたプロフィールで、バッジの取得が毎回約10秒かかっていた。relay-jp の同時購読数（既定8）が、開いたプロフィールの購読（`ugs-`・`url-`・`uf-`、1人につき3本）と常時の購読で埋まり、バッジの取得が空きを待ったまま時間切れになっていた。
  プロフィール（`UserProfileViewModel`）とバッジのコードは今回変えていないので、今回の変更が原因ではない。別の課題として扱う。
