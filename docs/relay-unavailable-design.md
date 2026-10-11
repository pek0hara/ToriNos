# 接続できないリレーを待たない 設計

作成日: 2026-10-10
状態: 1〜3段階を実装済み（2026-10-10、未コミット）。結果は 9.1。コードと突き合わせた見直しで、4章（再試行の接続中・どのリレーにもつながっていないとき）と 5.3（早く諦める受け手）を足した
関連: `subscription-architecture-design.md`（7章 シグナル、8章 EOSE と有限取得）、`journal-month-fetch-design.md`（13章）

## 1. 背景

Android 実機のログ（`journal-month-fetch-design.md` 13.1）では、有効な6リレーのうち2つが接続できなかった。

| リレー | 失敗のしかた | 失敗までの時間 |
| --- | --- | --- |
| r.bitcoinhold.net | DNS で引けない | すぐ（数十ms） |
| r.ydg.works | HTTP は返すが WebSocket に応じない | すぐ（ハンドシェイクで失敗） |

どちらも接続の試みはすぐに失敗し、2秒・4秒…（最大30秒）おきに再試行し続ける。
それでも有限取得（`SubscriptionBehavior.Fetch`）は、この2つの EOSE を時間切れ（ジャーナルは8秒）まで待っていた。

- ジャーナルのメンバー候補: 全件で確定するまで毎回約8秒（13章で、届いた分から出すことで体感だけ直した）。
- いいね・取り消し: `publish` が全リレーの送信を待ち、10秒の時間切れまで確定しなかった（`publishUntilFirstSuccess` に替えて直した）。

### 1.1 いまの仕組みの穴

- `subscription-architecture-design.md` 8章は「snapshot 内の全リレーが `Eose`・`Closed`・`Unavailable` のどれかになったら完了」と決めている。
  型（`RelayOutcome.Unavailable`、`SubscriptionSignal.RelayUnavailable`）もあり、受け手（`PostPageFetcher`、`ChannelController`、`FetchTrace`）も書かれている。
- しかし `NostrRepository` はどちらも一度も出していない。リレーの接続失敗は `NostrRelay.connect` の `onFailure` でログに出すだけで、購読側に伝わらない。
- フィードだけは、未コミットの作業で `RelayFeedHistoryCoordinator.stopWaitingForFailedRelays` が `relayConnectionStates == Disconnected` を見て、表示の境界から外している。
  ただしこれは表示の判断だけで、そのリレーへの有限取得は時間切れまで残る。

## 2. 目的

1. 接続に失敗しているリレーの有限取得を、時間切れを待たずに `RelayOutcome.Unavailable` で終える。
2. 配信中の購読（`Live`）には `SubscriptionSignal.RelayUnavailable` を出す。
3. 「接続に失敗している」の判定を通信の層の1か所にまとめ、フィードの判定も同じものを使う。

## 3. 非目標

- 接続も失敗もしないまま止まるリレー（パケットが捨てられる等）。接続の時間切れ（OkHttp は既定10秒）か、取得の時間切れに任せる。
- EOSE を返さない・遅いリレー。これまでどおり取得の時間切れで `TimedOut` にする。
- 取得の「完全・不完全」の判定。`Unavailable` は `Eose` ではないので、受け手はこれまでの `TimedOut` と同じく不完全として扱う（5.4）。
  そのため、接続できないリレーが有効な間、ジャーナルが「取得済み」を記録できない問題は残る（11章）。
- 投稿の送信（`publish`）。7章で別に決める。
- 接続できないリレーを設定から自動で外すこと、UI に出すこと。

## 4. 「接続できない」の定義

**接続を試みて、つながる前に失敗し、いまは次の再試行を待っている。** これを「接続できない」とする。

- 1回目の接続中は含めない。数ミリ秒差でつながるリレーを外すと、あとから投稿が割り込むため。
- **再試行の接続中も含めない。** その接続の結果を待ち、失敗したらそこで「接続できない」にする。
  アプリを前面に戻した直後や電波が不安定なときは、前の失敗が残ったまま再接続が始まり、すぐつながることが多い。
  再試行の接続中まで外すと、いまなら取れていた取得がすぐ失敗になる（見直しで見つけたデグレ）。
  DNS やハンドシェイクの失敗は数十msで分かるので、死んだリレーで待つ時間はほとんど増えない。
- つながっていた接続が切れただけ（つながった後の切断）は含めない。次の再接続が失敗したら含める。
- つながったら外す。
- **どのリレーにもつながっていないときは、取得を「接続できない」で終えない**（5.2）。
  端末側の通信の問題（オフライン、復帰の直後）とみなし、今までどおり時間切れまで待つ。
  困っているのは「ほかのリレーは答えているのに、死んだリレーを待つ」場合なので、目的は保てる。

### 4.1 NostrRelay に足すもの

判定は `RelayOutbox` と同じく、接続を持たない小さなクラス `RelayConnectionHealth` に分ける。
Ktor のテスト用エンジン（`MockEngine`）は WebSocket を扱えず、`NostrRelay` そのものは単体テストできないため。

```kotlin
internal data class RelayConnectionFailure(val reason: String, val atMillis: Long)

internal data class RelayHealth(
    /** つながる前に失敗し、まだつながっていない。最初の失敗を持ち続ける。 */
    val failure: RelayConnectionFailure? = null,
    /** 接続を試みている最中。 */
    val attempting: Boolean = false,
) {
    /** 失敗して、次の再試行を待っている。 */
    val isUnavailable: Boolean get() = failure != null && !attempting
}

internal class RelayConnectionHealth(nowMillis: () -> Long) {
    val state: StateFlow<RelayHealth>
    fun onAttemptStarted()
    fun onConnected()                               // failure を消す
    fun onFailedBeforeConnecting(error: Throwable)  // 中断は失敗にしない。失敗中なら failure を変えない
    fun reset()                                     // 接続をやめたとき
}
```

- 失敗の理由は `"クラス名: メッセージ"`（メッセージがなければクラス名だけ）を120文字までにしたもの。
- `CancellationException` は失敗にしない（`disconnect()` や画面を閉じたときの中断）。
- 再試行がまた失敗しても `failure` は最初の値のまま。配信中の購読への通知（5.5）は「`failure` が null から値になった」ときだけ出す。
- `NostrRelay` は接続の試みの前に `onAttemptStarted`、`openSession` の直後に `onConnected`、`webSocket` がつながる前に例外で終わったら `onFailedBeforeConnecting`、接続のループを抜けるときに `reset` を呼ぶ。

`RelayConnectionState` に値を足す案は採らない。`when` で全部の値を扱っている画面（リレー設定、チャンネルのリレー詳細、`RelaySelector`）に影響が広がるため。

## 5. 方式（NostrRepository）

### 5.1 判定の読み方

- `NostrRepository` は判定を写して持たない。ロックの中で `activeRelays[url].relay.health.state.value` を直接読む。
  写しを別のコルーチンで更新すると、つながった直後に開いた取得が、古い「接続できない」を見てしまうため。
- `createActiveRelayHandle` で `health.state` を集めるのは、変化したときに有限取得を見直す（5.3）きっかけと、Live への通知（5.5）のためだけ。
- 「どのリレーにもつながっていない」は、`activeRelays` のどれも `connectionState == Connected` でないこと。
- 「接続できないリレーのうち、まだ結果のないものを `Unavailable` にし、残りがなくなったか」の判定は、純粋な関数 `fetchFailureUpdate` に分けてテストする（`ActiveSubscriptionRecord` は private で、`NostrRepository` は実際の接続を持つため）。

```kotlin
internal data class FetchFailureUpdate(
    val unavailable: Map<String, RelayOutcome.Unavailable>,  // 新しく Unavailable にするリレー
    val settled: Boolean,                                    // 対象の全リレーに結果がそろった
)

internal fun fetchFailureUpdate(
    expected: Set<String>,
    outcomes: Map<String, RelayOutcome>,
    unavailable: Map<String, RelayConnectionFailure>,  // isUnavailable のリレー
    anyRelayConnected: Boolean,                        // false なら何も変えない
): FetchFailureUpdate
```

### 5.2 有限取得を見直す（1か所にまとめる）

`sweepUnavailableFetchesLocked()` が、終わっていない有限取得すべてについて `fetchFailureUpdate` を当てる。

- `fetchOutcomes[url] = RelayOutcome.Unavailable(reason)`。
- `finishFetchOnRelayLocked` で、そのリレーの状態を「取得を終えた」にする。あとでつながっても REQ は送らない（この取得の snapshot は変えない）。
  同時購読数の空きを待っている（REQ をまだ送っていない）リレーも同じに扱える。`finishFetch` は REQ を送っていなければ CLOSE を出さず、`Finished` は空きができても開き直さない。
- `shouldCompleteFetchLocked()` なら、ロックの外で `completeFetch(timedOut = false)` する。
- `Eose` や `Closed` をすでに返したリレーは変えない。

呼ぶところ:

1. `openSubscription` の最後。**キャッシュのイベントを流した（`replayCachedToSession`）後に呼ぶ。** 先に完了を出すと、キャッシュの分が完了の後に届くか、捨てられる。いまの「対象リレーが0件なら完了」と同じ順番。
2. どれかのリレーの `health.state` が変わったとき（再試行が失敗して待ちに入った、など）。つながって REQ を送った後に切れ、再接続が失敗した場合もここに入る。
3. どれかのリレーがつながったとき（`connectedJob`）。「どのリレーにもつながっていない」から抜けたときに、待っていた取得を見直す。

### 5.3 受け手への影響

`FetchCompleted` を受ける箇所を全部見た。どれも「全リレーが `Eose`」か「そのリレーが `Eose`」を完全の条件にしていて、`Unavailable` は不完全になる。
いまの `TimedOut` と同じ扱いなので、**判定は変わらず、結果が早く届くだけ**になる。

| 受け手 | 完全の条件 | `Unavailable` のとき |
| --- | --- | --- |
| `shouldCommitJournalFetch`（ジャーナル） | `!timedOut` かつ全部 `Eose` | 不完全（いまと同じ） |
| `ProfileRepository`、`PostRelayResolver`、`EmojiRelayFetch`、`BadgeTransport` | 同上 | 不完全 |
| `EventByIdFetcher` | `Eose` 以外があれば `Incomplete` | `Incomplete` |
| `EngagementFetcher`、`EmojiDiscoveryTransport` | そのリレーが `Eose` | 失敗 |
| `ChannelListFetch`、`ArticleDraftRepository` | `Eose` の数・集合 | 数えない |
| `PostPageFetcher`（検索） | `Unavailable` を区別済み | 「検索リレーに接続できませんでした」 |

- `FetchCompleted.timedOut` は false になる（`TimedOut` を含まないため）。`timedOut` だけを見て完全とする受け手はなかった。
- **失敗の回数で諦める受け手は、早く諦める。** 1回の失敗に時間切れの8〜10秒かからなくなるため。

| 受け手 | 諦めるまで（いま） | 改修後 |
| --- | --- | --- |
| `EngagementFetcher` の履歴（4回、1・2・4・8秒の待ち） | 約47秒 | 約15秒 |
| フィードの差分取得（`maxFailures = 3`、2・4秒の待ち） | 約36秒 | 約6秒 |
| フィードの履歴（`maxFailures` なし） | 諦めない | 諦めない。取り直しの待ちだけになる |

  ずっと死んでいるリレーは早く諦めるほうが無駄がない。10〜40秒だけ落ちていたリレーは、いまなら復旧して取れていた分が、次の復帰か「再試行」まで取れなくなる。
  回数に数えない作りにすると、死んだリレーの差分取得がいつまでも終わらないため、これは受け入れる。
- フィードの「一部の履歴を取得できていません」は、死んだリレーがあると8〜10秒後ではなくすぐ出る。

### 5.4 配信中の購読（Live）

- `failure` が null から値になったとき、そのリレーを対象に含む `Live` の購読へ `SubscriptionSignal.RelayUnavailable(url, reason)` を出す。失敗が続いている間は1回だけ。
- 購読を開いた時点で `failure` があるリレーにも、開いた直後に1回出す。
- 購読の状態（再接続したら REQ を送り直す）は変えない。つながれば今までどおり EVENT・EOSE が届く。
- いまの受け手は `ChannelController` だけで、`relayRefusals` に入れ、EVENT・EOSE で消している。表示はチャンネルのリレー詳細の理由の欄だけ。

## 6. フィードの判定をそろえる

`RepositoryFeedSubscriptionGateway.isRelayConnectionFailing` を、`relayConnectionStates == Disconnected` から、そのリレーの `RelayHealth.isUnavailable` に替える（公開用の読み口を `NostrRepository` に足す）。

- いまの判定は、接続の状態が `Disconnected` の間だけ「失敗している」になる。再試行の接続中（`Connecting`）は外れるので、開いた瞬間によって結果が揺れる（4章）。
- `NostrRelay` の状態の初期値は `Disconnected` で、ハンドルを作ってから `connect` するまでの間に、この値が写る可能性がある。実装のときに確かめ、新しい判定ではこの間を含めない。
- フィードのページ取得は `RelayTarget.Single` の有限取得なので、5.2 でそのまま早く終わる。
  `finishPage` では失敗として扱われ、`startRetryTimer` の待ち（回数に応じて延びる）の後に取り直す。いまは「8秒待って失敗→待って取り直す」なので、待ちの8秒がなくなる分だけ取り直しが早く回る。
  `maxFailures` は既定で無制限なので、諦める回数には届かない。実機で回数とログを確かめる。
- フィードの `isRelayConnectionFailing` は未コミットの作業に含まれる。その作業をコミットしてから、この章の変更を入れる。

## 7. 投稿の送信（判断が必要、今回は入れない案）

`publish`（投稿、返信、プロフィールの更新、削除、フォロー）は全リレーへの送信を待ち、接続できないリレーには10秒の時間切れまでかかる。

- 案A（推奨）: 失敗しているリレーへの送信は、待たずに「接続できません」で失敗にする。
  いまも10秒で時間切れになると送信待ちから外すので、失われるのは「10秒以内に復旧したら届いた」場合だけ。
- 案B: いまのまま。いいね・取り消しは `publishUntilFirstSuccess` で直したので、残るのは投稿の画面の待ちだけ。

案Aにすると「どのリレーに届いたか」の表示で、接続できないリレーがすぐ失敗として出る。実装は 5章の後に別のコミットで入れる。

## 8. テスト

先にテストを書く。

1. `RelayConnectionHealthTest`
   - 1回目の接続中は接続できないにしない。接続前に失敗したら接続できない。
   - 再試行の接続中は接続できないにしない（失敗は持ち続ける）。再試行がまた失敗しても最初の失敗のまま。
   - つながったら消える。つながった後の切断では失敗にしない。もう一度失敗したら記録し直す。
   - 中断は失敗にしない。`reset` で消える。理由は短くする。
2. `FetchFailureUpdateTest`
   - 接続できないリレーのうち結果のないものだけが `Unavailable` になる。`Eose`・`Closed` を返したリレーは変えない。
   - 取得の対象でないリレーは見ない。残りがなくなったら終わりにする。
   - どのリレーにもつながっていなければ何も変えない。
3. `NostrRepository` への組み込み（5.2、5.4）は単体テストせず、実機のログ（9章）で確かめる。
4. フィード（6章）は、フィードの未コミットの作業をコミットした後に、ゲートウェイを差し替えるテスト（`FeedRelayMergeGapTest` の作り）で足す。

## 9. 実機での確認（Android）

同じ6リレー（r.bitcoinhold.net、r.ydg.works を含む）で、`FetchTrace` のログを見る。

| 項目 | いま | 期待 |
| --- | --- | --- |
| ジャーナルのメンバー候補が全件で確定するまで | 約8〜9秒 | 約0.7秒（応答する4リレーの EOSE まで） |
| ジャーナルで月を開いたときの取得 | 時間切れ待ちあり | 2リレーが `UNAVAILABLE` ですぐ終わる |
| フィードのページ取得の取り直し | 8秒＋待ち | 待ちだけ |
| 起動直後（まだ1回目の接続中） | — | 失敗が分かるまでは待つ（外さない） |
| 機内モードでジャーナルを開く | 8秒後にエラー | 今と同じ（どのリレーにもつながっていないので待つ） |
| 再試行の接続中に開いた取得 | — | その接続の結果を待つ |

### 9.1 結果（Android 実機、2026-10-10）

この日の r.ydg.works は接続でき、EOSE を返すのが遅い（10秒以上）リレーになっていた。接続できないのは r.bitcoinhold.net だけだった。

| 画面・取得 | r.bitcoinhold.net | ほかに残った待ち |
| --- | --- | --- |
| 起動直後（フィードの履歴、バッジ、プロフィール、フォロー一覧、絵文字） | すべて `UNAVAILABLE` ですぐ終えた。`TIMEOUT` は0件 | r.ydg.works の時間切れ（対象外） |
| ジャーナルのメンバー候補 | 開いて6msで `UNAVAILABLE` | r.ydg.works の時間切れで、確定まではいまも8秒 |
| 検索（付随情報）、投稿の詳細（リアクション類）、通知、記事 | すぐ終えた。画面にエラーは出ない | r.ydg.works の時間切れ |
| ジャーナル本体、チャンネル | yabu.me だけに問い合わせる設定のため関係しない | — |

#### 改修前との比較

同じ手順（起動して25秒待つ → ジャーナル → メンバー候補を12秒開く → 閉じる、約50秒）を、改修前と改修後で2回ずつ行った。改修前は今回の変更だけを外した版。

| 項目 | 改修前（1回目 / 2回目） | 改修後（1回目 / 2回目） |
| --- | --- | --- |
| r.bitcoinhold.net を含む取得の終わり方 | 時間切れ 15 / 17件 | すぐ終了 39 / 39件（時間切れ0件） |
| ほかのリレーは答え、r.bitcoinhold.net だけが残った取得 | 11 / 7件。全部時間切れで、中央値 10.0秒 | 20 / 28件。時間切れ0件で、中央値 1.4 / 3.7秒（残りは r.ydg.works の遅い EOSE 待ち） |
| 同じ時間内に終わった取得 | 56 / 62件 | 83 / 79件 |
| メンバー候補 | 8.0 / 8.0秒（1回目は r.ydg.works が5.4秒で答えたのに、r.bitcoinhold.net の時間切れを待った） | 8.0 / 8.0秒（2回とも r.ydg.works が8秒以内に答えず時間切れ） |

- r.bitcoinhold.net だけに聞く取得（フィードの履歴、リアクション類）は、改修前は3件とも時間切れ、改修後は9件ともすぐ終了。取り直しの回数は増えたが、REQ を送らないので通信は増えない。
- メンバー候補は、r.ydg.works が速く答えた回なら、改修後は約5.4秒で終わる（改修前1回目の EOSE の時刻から）。

- 起動直後も、ほかのリレーがつながってから「接続できない」にしていた（4章の「どのリレーにもつながっていないとき」）。
- 確かめられなかったこと:
  - 配信中の購読への `RelayUnavailable`。受け手のチャンネルが接続できないリレーを使っていないため。
  - オフラインのとき。端末を無線の adb でつないでいて、機内モードにすると操作できなくなるため。
- ログの見た目の問題（動作には関係しない）:
  - 開いた時点で終えた取得は REQ を送っていないので、`COMPLETE` の時間が `?ms` になる。
  - 終えたリレーへの CLOSE は未接続のため送れず、`DROP` の行が出る。

## 10. 進め方

1. `RelayConnectionHealth` と `NostrRelay` への組み込み（4.1）、テスト。
2. `NostrRepository` の有限取得（5.1、5.2）、テスト、実機の確認。
3. Live の `RelayUnavailable`（5.4）。
4. フィードの判定（6章）。フィードの未コミットの作業をコミットした後。
5. 投稿の送信（7章）は、案を決めてから。

各段階で1コミットにし、それぞれ単独でビルドできるようにする。

## 11. 残る課題

- **ジャーナルが「取得済み」を記録できない。** 取得済みは全リレーが EOSE を返したときだけ記録する（`shouldCommitJournalFetch`）。
  接続できないリレーが有効な間は、月を開くたびに取り直す。今回の改修で1回あたりは速くなる（約8秒→約0.7秒）が、通信の回数は変わらない。
  「つながるリレーからは取り切った」を取得済みにするには、どのリレーを除いたかを記録し、復旧したら取り直す仕組みが要る。取得済みの意味が変わるので、`journal-month-fetch-design.md` と合わせて別に設計する。
- **月の範囲取得が、不完全な結果で次のページを取りに行かない。** `JournalRangePager.accept` は不完全な結果で `finished` にする。
  接続できないリレーがあると毎回不完全になるので、投稿の多い月で2ページ目以降を取りこぼすおそれがある。今回の改修とは関係なく、いまも起きうる。実機で確かめるか、別に調べる。
