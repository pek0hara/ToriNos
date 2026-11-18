# Buzz の在席表示 設計

- 作成日: 2026-11-17
- ステータス: 実装済み（実在の Buzz コミュニティ・実機での確認は未実施）
- 目的:
  - Buzz のチャンネルで、発言者のアバターに在席（オンライン・離席中）の印を付ける。
  - Buzz の DM では、見出しに相手の在席を出す。
  - メンバー一覧（見出しを押して開くグループの情報）では、各メンバーのアバターに印を付ける。
  - ToriNos でそのコミュニティの画面を開いている間は、自分を「オンライン」として Buzz に伝える。
- 対象: Buzz のコミュニティだけ。標準の NIP-29 リレーでは送らず、表示もしない（kind 20001 は Buzz 独自の使い方のため）。
- 調査対象:
  - [block/buzz-app](https://github.com/block/buzz-app)（2026-11-16 時点）: `src/features/presence/presence.ts`、`presence/activity.ts`、`presence/react.tsx`、`relay/live.ts`（`publishPresence`、`watchPresence`）、`relay/transport.ts`（`presenceSnapshot`、`parsePresence`）
  - [block/buzz](https://github.com/block/buzz)（2026-11-15 時点）: `crates/buzz-relay/src/handlers/event.rs`、`api/bridge.rs`（`synthesize_presence`）、`crates/buzz-pubsub/src/presence.rs`、`crates/buzz-auth/src/nip98.rs`
- 関連: `docs/buzz-typing-indicator-design.md`（入力中表示。同じ保存されないイベントの扱い）、`docs/nip29-buzz-design.md`

## 1. Buzz の仕様

### 1.1 送る（自分の在席）

| 項目 | 値 |
| --- | --- |
| kind | 20001（保存されない） |
| content | `online`・`away`・`offline` のどれか。古い形の `{"status":"online"}` も読める |
| タグ | 無し（チャンネルに属さない、コミュニティ全体のもの） |
| 送る間隔 | 約60〜65秒ごとに送り直す |

- リレーは受け取ると Redis に `online`・`away` を **180秒** の期限付きで保存し、`offline` なら消す。保存できたときだけ `OK` を返し、購読者へ配る。
- Buzz のアプリは、10分間操作が無いと `away` にする。利用者が手で `away`・`offline` を選ぶこともできる。アプリを閉じたときに `offline` は送らず、期限切れ（最長3分）で消える。

### 1.2 一括取得（今の在席）

- `POST https://<host>/query`。本文は `[{"kinds":[20001],"authors":[…],"limit":人数}]`。
- **NIP-98 の署名（`payload` 付き）が要る**（Buzz の REST 共通）。
- 応答はリレーの鍵で署名した kind 20001 の配列で、1人1件、`["p", 対象]` のタグが1つ、content が在席。**応答に無い人はオフライン**。
- Buzz のアプリは、署名者がリレーでないもの、`p` が1つでないもの、頼んでいない人のもの、同じ人が2件あるものが1件でもあれば、応答全体を信用しない。

### 1.3 変化の受け取り（配信）

- WebSocket で `{"kinds":[20001],"authors":[…]}` を購読すると、その人たちの送信（1.1）がそのまま届く。保存されていないので、購読した時点の状態は届かない（1.2 で取る）。
- 1人について、届いてから **75秒** 経つと「不明」に戻す（送り直しが60〜65秒ごとなので、それが届かなければ古い）。
- 一括取得は約60〜65秒ごと。失敗したら約60秒あけて取り直す。
- 一度に見るのは **256人** まで。

### 1.4 表示

- `online`: ●（Active）、`away`: ◐（Away）、`offline`: ○（Offline）。不明のときは何も出さない（情報が無いことはオフラインの証拠ではない）。
- メッセージのアバター、DM の一覧の相手、メンバー一覧、プロフィールに出す。

## 2. ToriNos での方針

### 2.1 自分の在席を送る

- 送るのは「その Buzz コミュニティのチャンネル（チャット・スレッド・フォーラム）の画面が前面に出ている間」だけ。ToriNos はチャット専用のアプリではないので、フィードを見ているだけの人をオンラインにしない。
- 画面が前面に出たら、すぐに `online` を送り、その後は **60秒（+0〜5秒の揺らぎ）** ごとに送り直す。送れなかった（未接続・未認証）ときは5秒後にもう一度試す。
- 同じコミュニティの画面が複数あっても（チャットの上にスレッドを開いた、など）、送るのはコミュニティごとに1つの繰り返しにする（参照の数を数える）。
- 画面が背面に回る・閉じると送り直しをやめる。`offline` は送らない（Buzz のアプリと同じく、リレーの期限で最長3分で消える）。
- `away`（操作が無いと離席）は送らない。画面が前面にある間だけ送るので、操作の無い時間は短いため。
- **確認なしで署名できるアカウント（秘密鍵・パスキー）のときだけ送る**。外部の署名器だと1分ごとに署名の確認が出るため。
- 自分の鍵で認証済みの接続があるときだけ送る。接続を作ったり、認証を待ったりはしない（入力中と同じ）。

### 2.2 ほかの人の在席を読む

- 画面（チャット・スレッド）に出ている発言者と、DM の参加者を対象にする。新しい発言者から順に 256人まで。自分も含める。
- メンバー一覧を開いている間は、一覧の人を一覧の並び（管理者が先、自分が最後）で発言者より優先して加える。256人を超える一覧では、下の方の人には印が出ない。フォーラムの画面でも、一覧を開いている間だけ在席を読む。
- 一覧を開いたときに、メンバーのプロフィールをグループのリレーから先に取る（発言していないメンバーの名前が出ないため。Buzz で作ったアカウントの kind 0 はコミュニティのリレーにしか無いことがある）。
- 対象が決まったら（変わったら0.5秒待って）、配信の購読を対象に合わせて張り直す。画面の購読とは別にする（対象が変わるたびに発言の購読を張り直さないため）。
- 一括取得は、画面が前面にあって、確認なしで署名できるときだけ行う。前面に出たとき・対象に新しい人が入ったときに取り、その後は60秒ごと。失敗したら60秒あける。
- 外部の署名器では一括取得をせず、配信だけで表示する（相手が送り直す60秒以内に分かる）。
- 届いた在席は75秒で不明に戻す。一括取得の応答より後に配信で届いたものを、古い一括取得で上書きしない（取得を始めた時刻より後に配信で更新された人は、応答で変えない）。
- 一括取得の応答は 1.2 と同じく検証し、1件でもおかしければ全体を捨てる（次の取得を待つ）。署名者がリレーの鍵（NIP-11 の `self`）かを見る。リレーの鍵が分からないときは一括取得をしない。
- 署名そのものの検証は、ToriNos のほかのイベントと同じく行わない（リレーとの接続を信用する）。

### 2.3 表示

- メッセージのアバターの右下に小さな丸を付ける。オンラインは緑の丸、離席中は黄色の丸。**オフラインと不明は何も付けない**（過去の発言が並ぶ画面で、ほとんどの人に印が付いて見づらくなるため。Buzz のアプリとの違い）。
- DM の画面では、見出しの下の行の先頭に「● オンライン」「● 離席中」を出す（相手が1人のときだけ）。● だけを在席の色（緑・黄色）にする（見出しの文字色のままだとオフラインに見えるため）。
- メンバー一覧では、アバターに同じ印を付ける（文字は出さない）。
- 色だけに頼らないよう、アバターの印には読み上げ用の説明（「オンライン」「離席中」）を付ける。

### 2.4 やらないこと

- チャットタブの一覧の DM の行への表示。一覧はコミュニティごとの購読を持たないので、別に組み立てる必要がある。必要になれば次の段階で足す。
- 自分の在席を手で選ぶ設定（離席中・オフライン）。
- `away` の自動判定。
- 標準の NIP-29 グループ。

## 3. 構成

| 部品 | 場所 | 内容 |
| --- | --- | --- |
| 在席のイベント・一括取得の本文と応答・記録（純粋なロジック） | `chat/BuzzPresence.kt` | `BUZZ_PRESENCE_KIND`、`PresenceStatus`、`buildBuzzPresence`、`parsePresenceContent`、`buzzPresenceQueryBody`、`parseBuzzPresenceSnapshot`、`PresenceBook`、`presenceSubjects` |
| 自分の在席の送り直し | `chat/BuzzPresence.kt` | `PresencePublisher`（コミュニティごとの参照の数と繰り返し） |
| 送信・一括取得 | `chat/ChatAccount.kt` | `publishPresence`、`fetchPresenceSnapshot`、`holdPresence` |
| 対象・配信の購読・一括取得の繰り返し・期限 | `ui/chat/GroupChatViewModel.kt` | `presence`（状態）、`setScreenVisible` |
| 一覧の並び | `ui/chat/GroupChatViewModel.kt` | `memberListOrder`（一覧の表示と在席の優先順で共有）、`setMemberListOpen` |
| 表示 | `ui/chat/GroupChatScreen.kt` | アバターの印、DM の見出し、メンバー一覧 |

### 3.1 `PresenceBook`

```kotlin
class PresenceBook(lifetimeMillis: Long = 75_000) {
    fun receiveLive(event: NostrEvent, nowMillis: Long): Boolean          // 表示が変わったら true
    fun applySnapshot(values: Map<String, PresenceStatus>, requestedAtMillis: Long, nowMillis: Long): Boolean
    fun status(pubkey: String, nowMillis: Long): PresenceStatus?           // 不明は null
    fun nextExpiryAt(nowMillis: Long): Long?
}
```

### 3.2 `PresencePublisher`

```kotlin
class PresencePublisher(scope: CoroutineScope, publish: suspend (relayUrl: String) -> Boolean, random: Random) {
    fun hold(relayUrl: String): () -> Unit   // 戻り値で手放す（2回呼んでも1回分）
}
```

1つ目の `hold` で繰り返しを始め、最後に手放したら止める。送れたら60〜65秒後、送れなかったら5秒後に次を送る。

## 4. テスト（先に書く）

`commonTest/.../chat/BuzzPresenceTest.kt`:

- 組み立て: kind 20001、タグ無し、content `online`。
- content の解析: `online`・`away`・`offline`、`{"status":"away"}`、それ以外（未知の文字列・壊れた JSON）は null。
- 一括取得の本文: `[{"kinds":[20001],"authors":[…],"limit":n}]`。
- 一括取得の応答:
  - 載っている人はその在席、載っていない人はオフライン。未知の在席の文字列はその人だけ不明（null）。
  - 署名者がリレーでない、`p` が無い・2つ、頼んでいない人、同じ人が2件、kind が違う、配列でない → 全体を null。
- 記録:
  - 配信で届くと出る。75秒で不明に戻る。
  - 一括取得で出る。期限は応答を受けた時刻から75秒。
  - 取得を始めた後に配信で更新された人は、応答で上書きしない。
  - content が未知の在席は、その人を不明にする（Buzz と同じ）。kind 違いは無視する。
  - `nextExpiryAt` が最も早い期限を返す。
- 対象の選び方（`presenceSubjects`）: 新しい発言者から順に、重複を除き、上限で切る。DM の参加者を先に入れる。
- メンバー一覧（`presenceMembersSubjects`）: 開いている間は一覧の人を発言者より先に、上限で切る。閉じていれば入れない。
- 送り直し（`runTest` の仮想時間）:
  - `hold` ですぐ送り、60〜65秒ごとに送り直す。
  - 同じコミュニティを2回 `hold` しても繰り返しは1つ。片方を手放しても続き、両方手放すと止まる。同じ手放しを2回呼んでも1回分。
  - 送れなかったら5秒後に送り直す。

## 5. 確認

- 単体テスト: `BuzzPresenceTest` 18件。チャット関連（`chat`・`ui.chat`）合わせて 274件、失敗0（iOS シミュレーター）。Android・Wasm のコンパイル成功。
- Web 版（ヘッドレス Chrome、2026-11-17）で、Buzz を模したリレーで確かめた。模擬リレーは、kind 20001 を180秒保存して配り、`/query` で NIP-98（`u`・`method`・`payload`）を検証してリレーの鍵で署名した在席を返す。
  - チャンネルを開くと、自分の `online`（タグ無し）が送られる。一括取得は発言者2人分を NIP-98 付きで頼む。
  - 一括取得の結果で、オンラインの Bob に緑、離席中の Carol に黄色の印が付く。
  - 配信で Bob が `offline` になると印が消え、Carol が `online` になると緑に変わる。
  - 約63秒後に自分の在席を送り直し、約65秒後に一括取得をやり直す。
  - チャンネルを閉じると、その後75秒間は送り直しも一括取得もしない。
  - Buzz の DM の見出しに「● オンライン」（● は緑）が出て、配信で `away` になると「● 離席中」（● は黄色）に変わる。
  - メンバー一覧を開くと、発言していない Dave を含む4人で一括取得し、Bob・Dave・自分に緑、Carol に黄色の印が付く（この時点では行の右端に文言も出していたが、後で外した）。名前もグループのリレーから取って出る。
  - 一覧を開いたまま、配信で Carol が `online` になると緑に変わり、Dave が `offline` になると印が消える。
  - 在席がほかのリレーへ送られないこと。
- 実在の Buzz コミュニティ・実機（Android・iOS）での確認は未実施。外部署名器で送らない・一括取得しないことは条件分岐のみで、画面では確かめていない。
