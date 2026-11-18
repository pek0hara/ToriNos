# チャットの新規作成（チャンネル・DM・Solo）設計

- 作成日: 2026-11-17
- ステータス: 段階A（チャンネル・グループの作成）と段階B（Buzz の DM）を実装済み。段階C（Solo）は未実装。実機・実在のリレーでの確認は未実施（9章）
- 前提: [nip29-buzz-design.md](nip29-buzz-design.md)（チャットタブ、段階1〜4 実装済み）。同書で対象外としていた「グループ・コミュニティの作成」と「DM」のうち、作成に必要な部分を扱う。
- 調査対象: `block/buzz`（リレー `crates/buzz-relay`、モバイル `mobile/`、2026-11-15 時点 75d78ea）、`block/buzz-app`（456bc5e）。NIP-29 本文は 2026-11-17 に確認。
- きっかけ: Buzz モバイルの「＋」メニュー（Solo: New chat / New workspace、Team: New DM / New channel）と同じことを ToriNos のチャットタブからできるようにする。

## 1. 対象範囲

「新しいチャット」を、Buzz のメニュー4項目に対応させて次のように分ける。

| Buzz のメニュー | 中身 | ToriNos での扱い |
| --- | --- | --- |
| New channel | チャンネルを作る（kind 9007） | **段階A**。標準の NIP-29 リレーでのグループ作成も同じ画面で扱う |
| New DM | 相手を選んで DM チャンネルを開く（kind 41010） | **段階B**。DM チャンネルの一覧表示も必要（今は非表示） |
| New chat（Solo） | 自分とエージェントだけの非公開チャンネル（9007 + `buzz.solo` ラベル） | **段階C**。自分のエージェントが居るときだけ使える |
| New workspace（Solo） | Solo チャットをまとめるフォルダー（暗号化した設定 `groups:me`） | 対象外 |

**確認したい点**: 「新しいチャット」が Solo の New chat だけを指すなら、段階C を先にする。ただし Solo はエージェント（Buzz デスクトップが動かす）が居ないと会話にならず、ToriNos の鍵でエージェントを持つ人に限られるため、この設計では A → B → C の順を推奨する。

対象外: ワークスペース、サイドバーのセクション、期限付きチャンネル（`ttl`）、チャンネルの編集・アーカイブ・削除、メンバーの追加・削除（9000 / 9001）、招待コードの発行（9009）、DM へのメンバー追加（41011）、DM を隠す（41012）。

## 2. 調査結果

### 2.1 Buzz: チャンネル作成（kind 9007）

送るイベント（`mobile/lib/features/channels/channel_management_provider.dart` の `buildCreateChannelTags`）:

```json
{ "kind": 9007, "content": "", "tags": [
  ["h", "<クライアントが作る UUID v4>"],
  ["name", "release-notes"],
  ["visibility", "open"],
  ["channel_type", "stream"],
  ["about", "説明（任意）"]
] }
```

リレーの検査（`buzz-relay/src/handlers/ingest.rs`、`side_effects.rs`、`buzz-core/src/channel.rs`）:

| 項目 | 規則 | 拒否理由 |
| --- | --- | --- |
| `h` | 必須。UUID として読めること。クライアントが決める | `invalid: create-group must include an h tag with the new channel id` |
| `name` | 必須。先頭の `#` と空白、末尾の空白を除いて 1〜255 文字 | `invalid: channel name is required` / `… longer than 255 characters` |
| 名前の重複 | コミュニティ内で使用済みなら拒否 | `invalid: …`（`ChannelNameTaken`） |
| `channel_type` | 省略時 `stream`。`stream` / `forum` を使う | `invalid channel_type: …` |
| `visibility` | `open` / `private`。省略時は種別の既定 | `invalid visibility: …` |
| `t`（ラベル） | 最大8個、各 1〜64 文字（`a-z 0-9 . : -`）。`stream` `forum` `dm` `workflow` `system` は不可 | `invalid: …` |
| `p`（最初のメンバー） | 作成者以外に最大32人 | `invalid: a channel may start with at most 32 members` |
| `unlisted` | `"true"` / `"false"` | `invalid: invalid unlisted value` |

- チャンネルは、イベントの保存と同じトランザクションで作られる。作成者は owner になる。
- **同じイベントの再送は成功扱い**（`OK true "duplicate:"`）。同じ UUID で別のイベントは `OK false "duplicate: channel already exists"`。OK を取りこぼしたときは、同じ署名済みイベントを送り直せばよい。
- 作成後、リレーが kind 39000（情報）と 39002（メンバー）を出す。モバイルは `{kinds:[39000], #d:[id], limit:1}` と `{kinds:[39002], #d:[id], limit:1}` を別々に読んで、自分がメンバーに入っていることを確かめてから開く（`findJoinedChannel`）。
- 39000 の形（`buzz-db/src/store/channel.rs` の `group_metadata_tags`）: `d`、`name`、`about`、`public` または `private`、非公開なら `restricted` と `closed`、`t`（1つ目が種別、2つ目以降がラベル）、`P`（作成者）、`join_role` など。

### 2.2 Buzz: DM を開く（kind 41010）

```json
{ "kind": 41010, "content": "", "tags": [["p", "<相手>"], ["p", "<相手2>"]] }
```

- `p` は自分以外に 1〜8 人（合計9人まで）。`h` タグは無い。
- 同じ参加者の組み合わせなら既存の DM を返す（参加者の集合のハッシュで探す）。作り直しにはならない。
- **結果は OK メッセージで返る**: `["OK", id, true, "response:{\"channel_id\":\"<UUID>\",\"created\":true}"]`。古いリレーは `response:` 無しの JSON だけを返す（モバイルの `parseCommandResponse` は両方読む）。
- 同じイベントの再送は `OK true "duplicate: already processed"` で、**channel_id が返らない**。取りこぼしたときは、新しいイベントで開き直す（同じ DM が返る）。
- DM の 39000: `t` = `dm`、`hidden`、`private`、`closed`、`restricted`、**参加者全員の `p` タグ**（名前の解決用）。`name` は表示に使わず、参加者から作る。
- 隠した DM（41012）は kind 30622（`#p` = 自分）の一覧に載り、モバイルはそれを一覧から除く。開き直すと隠した状態は解除される。
- 相手の検索は、モバイルではリレーのプロフィール検索（`searchProfiles`）を使う。

### 2.3 Buzz: Solo の New chat

- Solo チャットは「`buzz.solo` ラベルの付いた非公開チャンネル」。専用の保存先や kind は無い（`buzz-app/docs/solo/README.md`）。
- 作成（`mobile/lib/features/channels/me/new_me_chat.dart`）: 9007（`visibility=private`、`channel_type=stream`、`["t","buzz.solo"]`、`about` 無し）→ エージェントを 9000（`h`、`p`、`["role","member"]`）で追加 → 最初のメッセージ（kind 9、`h`、`["p", エージェント]`）。
- リレーに何も作らないまま下書き画面を開き、**最初の送信で作る**。途中で止まったときのために、チャンネルID・名前・エージェント・署名済みの最初のメッセージを端末に保存し、同じ ID でやり直す。
- 選べるのは自分が所有するエージェントだけ（エージェントの kind 0 に所有者の署名入り `auth` タグ）。エージェントだけの会話では、送信のたびにエージェントを `p` でメンションする必要がある。
- ワークスペースへの振り分けは暗号化した設定で、無くてもチャットは成り立つ。

### 2.4 標準の NIP-29

- 本文の管理イベント表に `9007 create-group` はあるが、タグも、誰が送れるかも、作成後の動作も書かれていない（「グループを作る方法は無く、リレーが特定の ID に規則を作るだけ」）。
- `9002 edit-metadata` は「group-metadata の全フィールド」（`name`、`picture`、`about`、`private`、`restricted`、`hidden`、`closed` など）。フラグの外し方は書かれていない。
- グループ ID は「任意の長さのランダムな文字列」。ユーザーが送るイベントは `h` 必須。
- 実装（relay29 系）では「9007 + `["h", 新しいID]` を送ると作られ、送った人が管理者になり、名前は 9002 で付ける」のが通例だが、本文の保証は無い。**ローカルのリレーで実物を確かめてから実装する**（未確定事項1）。

### 2.5 ToriNos の現状と足りないもの

| 箇所 | 現状 | 必要な変更 |
| --- | --- | --- |
| `chat/GroupMessages.kt` の `GROUP_PUBLISHABLE_KINDS` | 9、7、5、9021、9022、11、1111、40003、45001、45003 | 9007、9002、9000 を足す |
| `groupPublishViolation` | `h` が1つでグループIDと一致することを要求 | 41010 は `h` が無いので別の検査を作る |
| `RelayPublishResult` | 成功したリレーの集合だけ。OK のメッセージは失敗時しか持たない | 成功時の OK メッセージを持たせる（41010 の応答、`duplicate:` の判別） |
| `GroupMetadata.layout` | `dm` は `Hidden`（一覧に出さない） | `Direct` を足して DM を出す |
| `ChatTabViewModel.refreshBuzz` | 39000 を `#d` 無しで取り、`hidden` を除く | 自分がメンバーの DM を `#d` 指定で取る |
| `GroupMetadata` | 最初の `t` だけ読む | 2つ目以降の `t`（ラベル）と `p`（DM の参加者）を読む |
| ヘッダー | 切り替えメニューの末尾に「グループに参加…」 | 「＋」ボタンを足す |

NIP-28 の `ui/channel/ChannelCreateSheet.kt` は入力欄の並びの参考にするが、部品は共有しない（前提設計 5.1 の方針どおり）。

## 3. UI 設計

### 3.1 入口

（実装時の変更）一覧の右下に既にある「＋」ボタン（これまでは参加シートを開くだけ）を、メニューを開くボタンにした。ヘッダーには足していない。参加先が1つも無いうちは、これまでどおり参加シートを直接開く。当初案は次のとおり。

ヘッダーの設定（⚙）の左に「＋」を置く。切り替えメニューの「グループに参加…」は残す。

```text
┌──────────────────────────────────────────┐
│ (●)  png ▾                        ＋  ⚙ │
├──────────────────────────────────────────┤
                              ┌────────────────────┐
                              │ ✎ 新しいチャット    │ ← 段階C。Buzz で、自分のエージェントが居るとき
                              │ 💬 新しい DM        │ ← 段階B。Buzz のみ
                              │ ＃ 新しいチャンネル │ ← 段階A
                              │ ──────────────     │
                              │ ＋ グループに参加…  │
                              └────────────────────┘
```

- 作成先は、ヘッダーで選んでいるリレー・コミュニティ。「すべて」を選んでいて参加先が複数あるときは、シートの最初に作成先を選ぶ。1つならそれを使う。0なら「新しい〜」は出さず「グループに参加…」だけ。
- 項目の出し分け: 「新しい DM」「新しいチャット」は作成先が Buzz のときだけ。標準の NIP-29 では「新しいチャンネル」を「新しいグループ」と表示する。
- 公開鍵だけのログインでは「＋」を押せるが、作成系の項目は無効にして「署名できるアカウントでログインしてください」を出す（既存の参加シートと同じ扱い）。
- 接続・ログイン中（AUTH 前）は項目を無効にする。

### 3.2 新しいチャンネル（段階A）

```text
┌────────────────────────────────────┐
│ 新しいチャンネル            [作成] │
│ 作成先  png.communities.buzz.xyz ▾ │ ← 複数あるときだけ選べる
│ 名前                               │
│ ┌────────────────────────────────┐ │
│ │ release-notes                  │ │
│ └────────────────────────────────┘ │
│ 説明（任意）                        │
│ ┌────────────────────────────────┐ │
│ │ このチャンネルの用途            │ │
│ └────────────────────────────────┘ │
│ 公開範囲  ( 公開 ) ( 非公開 )       │
│  公開: コミュニティの誰でも見つけて参加できます │
└────────────────────────────────────┘
```

- 名前はリレーと同じ規則で整える（先頭の `#`・空白、末尾の空白を除く）。空、または 255 文字超なら［作成］を無効にする。
- 種類はシートでは選ばず、＋のメニューの「新しいチャンネル」「新しいフォーラム」で決める。「新しいフォーラム」は Buzz の作成先があるときだけ出し、作成先も Buzz に限る（見出しは「新しいフォーラム」、説明の下に形式の説明を出す）。シートの中に種類の切り替えを置くと、作成先が標準のときに見えず、フォーラムを作れないように見えたため。
- 公開範囲の既定は Buzz モバイルと同じ「公開」。
- 一覧・参加・情報・編集のアイコンは、画像の無いフォーラムを「#」ではなく吹き出し（`Icons.Outlined.Forum`）にする。
- 標準の NIP-29 には種類の指定が無い。「公開範囲」は 公開（フラグ無し）／非公開（`private` + `closed`）。
- 送信中はボタンをスピナーにし、入力を無効にする。
- 成功したらシートを閉じ、一覧に足して、そのチャット画面（フォーラムならフォーラム画面）を開く。
- 失敗はシート内に出す。リレーの拒否理由のうち、名前の重複（`ChannelNameTaken` の文言）は「この名前は既に使われています」に置き換え、それ以外は理由をそのまま見せる。入力は残す。

### 3.3 新しい DM（段階B）

```text
┌────────────────────────────────────┐
│ 新しい DM                   [開く] │
│ 宛先: [tanaka ×] [sato ×]          │ ← 最大8人
│ ┌────────────────────────────────┐ │
│ │ 名前、npub で探す               │ │
│ └────────────────────────────────┘ │
│ (●) tanaka   Tanaka Taro           │
│ (●) suzuki   鈴木                  │
│ このコミュニティの中だけの DM です。 │
│ Nostr の DM（NIP-17）とは別物で、    │
│ リレーの運営者は内容を読めます。     │
└────────────────────────────────────┘
```

- 候補は、そのコミュニティで見かけた人（参加中チャンネルのメンバー一覧 39002 と、読み込み済みの発言の作者）を名前で絞り込む。npub / hex を貼れば直接指定できる。リレーのプロフィール検索は、仕組みを確かめてから足す（未確定事項3）。
- 既にある組み合わせなら、その DM が開く（リレーが同じものを返す）。
- **暗号化されないことを明記する。** ToriNos には NIP-17 の DM があり（[dm-feature-design.md](dm-feature-design.md)）、同じ「DM」という言葉で暗号化を期待されるため。
- 一覧での DM の表示（個別のコミュニティを選んだとき、「チャット」「フォーラム」の下に「DM」の区切り。「すべて」では他と同じ並びに混ぜる）:

```text
│ DM                                        │
│ (●) tanaka                       12:40  ● │
│ (●●) sato, suzuki                11/15    │ ← 3人以上は名前を並べる
```

  名前は 39000 の `p` から自分を除いた参加者のプロフィール名。アイコンは1人ならその人のアバター、複数なら重ねる。kind 30622 に載っている DM は出さない。

### 3.4 新しいチャット（Solo、段階C）

- 「＋ → 新しいチャット」で、リレーに何も作らずに下書き画面を開く（Buzz と同じ）。上部でエージェントを選ぶ（自分のエージェントが1つなら固定）。
- 最初の送信で、作成 → エージェント追加 → メッセージ送信をまとめて行う。進行中は送信ボタンをスピナーにする。
- 名前は最初のメッセージの先頭（改行まで、40文字まで）から作る。重複したら末尾に時刻を足して1回だけやり直す。
- 一覧では Solo を別の区切り（「Solo」）で出す。判定は 39000 の `t` に `buzz.solo` があること。
- 自分のエージェントが居ないコミュニティでは項目を出さない。

### 3.5 途中で止まったとき

作成が終わる前にアプリが落ちた・通信が切れた場合、次にチャットタブを開いたとき、一覧の上に帯を出す。

```text
│ 「release-notes」の作成が終わっていません   [再開] [破棄] │
```

「破棄」は端末の記録を消すだけで、リレーに作られたものは消さない（その旨を書く）。

## 4. 実装設計

### 4.1 イベントの組み立て（`chat/GroupCreation.kt`、純粋関数）

```kotlin
enum class NewGroupType { Chat, Forum }
enum class NewGroupVisibility { Open, Private }

data class NewGroupSpec(
    val groupId: String,            // Buzz は UUID v4、標準はランダムな英数字
    val name: String,
    val about: String,
    val type: NewGroupType,
    val visibility: NewGroupVisibility,
    val labels: List<String> = emptyList(),      // Solo は ["buzz.solo"]
    val initialMembers: List<String> = emptyList(),
)

fun canonicalGroupName(input: String): String          // 先頭の # と空白、末尾の空白を除く
fun groupNameError(name: String): GroupNameError?      // Empty / TooLong(255)
fun newBuzzChannelId(random: Random): String           // UUID v4
fun newStandardGroupId(random: Random): String         // 小文字英数字 12 文字

fun buildBuzzCreateChannel(spec: NewGroupSpec): GroupEventDraft            // kind 9007
fun buildStandardCreateGroup(groupId: String): GroupEventDraft             // kind 9007、h だけ
fun buildStandardEditMetadata(spec: NewGroupSpec): GroupEventDraft         // kind 9002
fun buildBuzzAddMember(groupId: String, pubkey: String): GroupEventDraft   // kind 9000、role=member
fun buildBuzzOpenDm(pubkeys: List<String>): CommandDraft                   // kind 41010、1〜8人、自分を除き重複を除く

fun parseBuzzCommandResponse(okMessage: String): JsonObject?   // "response:{…}" と素の JSON の両方
fun buzzDmChannelId(okMessage: String): String?
fun classifyCreateRejection(reason: String): CreateRejection   // NameTaken / InvalidName / NotAllowed / Other
```

- Buzz の 9007 のタグ順はモバイルに合わせる: `h`、`name`、`visibility`、`channel_type`、`t`…、`about`（空なら付けない）、`p`…。`ttl` と `unlisted` は付けない。
- 標準の 9007 と 9002 には `previous` を付けない（新しいグループには参照できるイベントが無い）。

### 4.2 送信の検査

- `GROUP_PUBLISHABLE_KINDS` に 9007、9002、9000 を足す。9007 の `h` は新しい ID なので、既存の「`h` が1つで ID と一致」の検査はそのまま通る。
- 41010 は `h` が無いので、`buzzCommandViolation(event, targets, communityRelay, flavor)` を別に作る: kind が許可した命令（当面 41010 だけ）、宛先がそのコミュニティのリレーだけ、流儀が Buzz。**標準の NIP-29 リレーや通常のリレーへ 41010 を送らないことをテストで固定する。**
- 9000 を送れるのは「自分が今作った Solo チャットにエージェントを足す」場合だけに限る（呼び出し側を `SoloChatCreator` だけにする）。

### 4.3 OK メッセージの受け渡し

`RelayPublishResult` に `acceptedMessages: Map<String, String>`（リレーURL → OK true のメッセージ）を足す。`GroupPublishOutcome.Published` に `message: String` を足し、`ChatAccount.publishToGroup` と新しい `ChatAccount.publishCommand(relayUrl, draft)` から返す。

- 既存の呼び出しは `message` を使わないので挙動は変わらない。
- `auth-required:` のときに認証を待って1回だけ送り直す既存の流れ（`publishWithAuthRetry`）を、41010 でも使う。

### 4.4 作成の流れ（`chat/GroupCreator.kt`）

共通: 署名の前に、作成の記録（journal）を端末に保存する。記録は「ID・名前・設定・署名済みイベント・どこまで済んだか」。アカウントとリレーごとに1件。

**Buzz のチャンネル**

1. ID を作り、9007 に署名して記録する。
2. 送る。`OK true`（`duplicate:` を含む）なら次へ。`OK false` は理由を分類して終了（記録は消す。名前の重複は入力に戻す）。応答が無ければ記録を残して「再開」に回す。
3. 確認: `{kinds:[39000], #d:[id]}` と `{kinds:[39002], #d:[id]}` を読み、リレー鍵の署名と、自分がメンバーに入っていることを確かめる。すぐ出ないことがあるので 0 / 250 / 750 / 1500 ms で読み直す（モバイルの参加確認と同じ間隔）。
4. 一覧（`buzzChannels`・`buzzJoined`・`metadataByRef`）に足し、`roomCache.setJoinedHint` を立て、記録を消して画面を開く。
5. 3 で確認できなかった場合も、作成は成功として記録を消し、「作成しました。一覧に出るまで少しかかることがあります」と出して一覧を読み直す。

再開: 記録にある**同じ署名済みイベント**を送り直す（`duplicate:` で成功になる）。Buzz は 15 分より古いイベントを受け付けないので、署名から 14 分を過ぎていたら、先に 3 の確認をして、無ければ同じ ID で署名し直す。別イベントで ID が使用済み（`duplicate: channel already exists`）なら、3 の確認で自分のものか確かめる。

Buzz のチャンネルは 10009 に入れない（前提設計 6.4 と同じ）。

**標準の NIP-29 のグループ**

1. ID を作り、記録する。9007（`h`）を送る。
2. 9002（`h`、`name`、`about`、非公開なら `private` と `closed`）を送る。
3. 39000 を読んで確認し、10009 に `["group", id, relay, name]` を足す（既存の `recordJoined`）。
4. 2 だけ失敗したとき: グループは出来ているので、記録を「名前の設定待ち」にして「再開」で 2 からやり直す。

作成の前にそのリレーへ AUTH する必要があるので、未参加のリレーでは既存の確認（「公開鍵を伝えてログインしますか？」）を挟み、許可リストへ入れる。

**Buzz の DM**

1. 41010 を送る。OK メッセージから `channel_id` を取る。
2. 取れなかった場合（`duplicate: already processed` など）は、新しい 41010 で1回だけ開き直す。
3. 39000 / 39002 を `#d` で読んで確認し、一覧の DM に足して開く。記録は持たない（何度開いても同じ DM が返るため）。

**Solo（段階C）**

`SoloChatCreator` が「9007（`buzz.solo`、private）→ エージェントが居なければ 9000 → 最初のメッセージ」を順に行う。各段の前に「済んでいるか」を読み（39000 / 39002 / メッセージ ID）、済んでいれば飛ばす。記録には署名済みの最初のメッセージも入れ、二重送信を防ぐ（14 分を過ぎたら、届いているか ID で確かめてから署名し直す）。

エージェントの一覧は「kind 0 の `auth` タグに自分の署名があるもの」。検証式は Buzz の実装（`nostr:agent-auth:<agent>:` の SHA-256 への Schnorr 署名）に合わせる。エージェントの候補をどこから取るかは未確定（未確定事項5）。

### 4.5 DM の一覧（段階B）

- `GroupLayout.Direct` を足し、`channelType == "dm"` に割り当てる。`workflow`・`system` は `Hidden` のまま。
- `GroupMetadata` に `participants: List<String>`（`p` タグ）と `labels: List<String>`（2つ目以降の `t`）を足す。
- `refreshBuzz`: 39002（`#p` = 自分）で分かった参加中の ID のうち、39000 がまだ無いものを `{kinds:[39000], authors:[self], #d:[…]}` で取りに行く（DM は `hidden` なので `#d` 無しでは返らない見込み）。今の `!it.isHidden` の除外は、DM については「自分がメンバーなら出す」に変える。
- kind 30622（`#p` = 自分、`limit:1`）を読み、載っている DM を除く。タグの形は実物で確かめる（未確定事項4）。読めなければ除外しない。
- 表示名は `dmDisplayName(participants, me, profiles)`（純粋関数）で作る。プロフィールは既存の `ProfileRepository` 経由で取り、kind 0 を直接購読しない（`verifyNoDirectProfileSubscriptions`）。
- DM の画面は既存のチャット画面をそのまま使う。ヘッダーの `#名前` を参加者名に替え、「リンクをコピー」「退出」は出さない。
- 最新の発言の取得（今は参加中の先頭30件）は、チャンネルと DM を合わせて30件のままにし、DM を後ろに回す。

### 4.6 画面と状態

- `ChatTabUiState` に `create: CreateSheetState?` を足す（既存の `join: JoinSheetState?` と同じ持ち方）。

```kotlin
sealed interface CreateSheetState {
    data class PickTarget(val kind: CreateKind, val targets: List<ChatRelaySummary>) : CreateSheetState
    data class Channel(val target: ChatRelaySummary, val name: String = "", val about: String = "",
        val type: NewGroupType = NewGroupType.Chat, val visibility: NewGroupVisibility = NewGroupVisibility.Open,
        val isSubmitting: Boolean = false, val error: String? = null) : CreateSheetState
    data class Dm(val target: ChatRelaySummary, val query: String = "", val picked: List<String> = emptyList(),
        val candidates: List<String> = emptyList(), val isSubmitting: Boolean = false, val error: String? = null) : CreateSheetState
}
```

- 作成処理は `ChatAccount` のスコープで動かし、シートを閉じても続ける（参加処理と同じ）。
- ViewModel のプロパティは `init` より前に宣言する（前提設計のレビュー記録にある Wasm の null 参照の再発防止）。
- 作成後の画面遷移は既存の `GroupChatRoute` / `GroupForumRoute` を使う。フォーラムは作成時に種類が分かっているので、最初からフォーラムの道筋で開く。

## 5. 実装順（テスト先行）

段階A:

1. `canonicalGroupName`・`groupNameError`・ID の生成（UUID v4 の形、標準 ID の文字種）。
2. `buildBuzzCreateChannel`（タグの順と省略、公開範囲、種類）、`buildStandardCreateGroup`、`buildStandardEditMetadata`。
3. `classifyCreateRejection`（2.1 の拒否理由の文言）、`duplicate:` の成功扱い。
4. `RelayPublishResult.acceptedMessages` と `GroupPublishOutcome.Published.message`。
5. 送信の検査: 9007 / 9002 が通る、他のリレー宛ては通らない。
6. `GroupCreator`（偽のリレーで）: 成功、名前の重複、OK の取りこぼし → 同じイベントで再開、14 分超の署名し直し、確認が遅れるとき、標準の 9002 だけ失敗。
7. 記録の保存・復元・破棄（アカウントとリレーの境界、別アカウントの記録を拾わない）。
8. 「＋」メニュー、作成シート、再開の帯の配線。

段階B:

9. `buildBuzzOpenDm`（人数の上下限、自分と重複の除外）、`parseBuzzCommandResponse`（`response:` 付き、素の JSON、壊れた JSON、`duplicate:`）。
10. `buzzCommandViolation`（Buzz 以外へ送らない）。
11. `GroupMetadata` の `participants`・`labels`、`GroupLayout.Direct`、`dmDisplayName`、30622 による除外。
12. `refreshBuzz` の DM 取得、一覧の DM 区切り、宛先選びのシート。

段階C:

13. エージェントの所有確認（`auth` タグの検証）、`SoloChatCreator` の各段の飛ばし方と再開、Solo の区切り。

## 6. 検証

- 単体テストと既存の全体テスト、Android / iOS / Wasm のコンパイル、`verifyNoDirectProfileSubscriptions`。
- Web 版（ヘッドレス Chrome）で、既存のドライバー（Buzz と標準リレーを模したもの）に 9007・9002・41010 の応答を足して、作成 → 一覧に出る → 開く、名前の重複、OK を返さない場合の再開を確認する。
- ローカルの buzz-relay（`docker-compose.yml`、`BUZZ_REQUIRE_RELAY_MEMBERSHIP=true`）と Buzz モバイル／デスクトップを並べ、ToriNos で作ったチャンネル・DM が Buzz 側に同じ形で見えること、Buzz 側で作ったものと 39000 のタグが同じことを確認する。
- 標準はローカルの relay29 系リレーで、9007 → 9002 → 39000 の実際の応答を確かめる。
- `[Relay]` ログで、9007・41010 がそのコミュニティのリレー以外へ出ていないことを確かめる。
- **実在のコミュニティでは、了承を得てから行う。** チャンネルは作ると全員に見え、ToriNos からは消せない（削除は対象外）。DM は相手に通知が届く。

## 7. 懸念とリスク

- **作ったものを消せない**: 削除（9008）とアーカイブは対象外。作成前の確認は入れないが、公開チャンネルは「コミュニティの全員に見える」ことをシートに書く。誤作成の後始末は Buzz アプリで行う。
- **DM が暗号化されない**: NIP-17 の DM と混同されないよう、宛先シートと DM 画面のグループ情報に明記する。
- **二重作成**: OK を取りこぼして別の ID で作り直すと、同名は拒否され、別名だと2つ出来る。ID と署名済みイベントを記録して同じものを送り直すことで防ぐ。
- **標準の NIP-29 の作成は本文に規定が無い**: リレーごとに挙動が違う可能性がある。対応していないリレーでは拒否理由をそのまま見せる。実物確認の結果、差が大きければ標準の作成は段階A から外す。
- **ゲストの権限**: Buzz のゲストは書き込みができない。作成が拒否されたときは理由を見せるだけにし、事前の判定はしない（自分の役割を確実に知る手段を今は持っていないため）。
- **Solo の前提**: エージェントが居ないと成り立たない。ToriNos だけの利用者には出ない機能になる。
- **Buzz の仕様の変化**: 9007 のタグや 41010 の応答は Buzz 独自で、変わりうる。組み立てと解析を 4.1 の純粋関数に閉じ込め、知らない形は「失敗」ではなく「確認できなかった」として一覧の読み直しに回す。

## 8. 未確定事項

1. 標準の NIP-29 リレー（relay29 系）での 9007 と 9002 の実際の受け付け条件、作成者が管理者になるか、9000 が出るか。
2. Buzz で、ゲストや権限の無いメンバーが 9007 を送ったときの拒否理由の文言。
3. Buzz のプロフィール検索（`searchProfiles`）の実体（NIP-50 の `search` フィルターか、REST か）。分かれば DM の宛先検索に使う。
4. kind 30622（隠した DM の一覧）のタグの形。
5. Solo で選べるエージェントの候補の取り方（コミュニティ内の kind 0 を `auth` タグで絞れるか）。
6. 「新しいチャット」の範囲（1章の確認したい点）。

## 9. 実装状況（2026-11-17）

### 実装したもの（段階A・B）

| 部品 | 場所 |
| --- | --- |
| 名前の整形、ID の生成、9007 / 9002 / 41010 の組み立て、拒否理由の分類、命令の応答の解析、命令の送信先の検査 | `chat/GroupCreation.kt` |
| 作成の流れ、終わっていない作成の記録と再開 | `chat/GroupCreator.kt` |
| 署名済みイベントの送り直し、作成・再開・破棄、DM を開く、作成の間リレーとの接続を保つ | `chat/ChatAccount.kt`（`createGroup`・`resumeCreation`・`discardCreation`・`openBuzzDm`・`keepingRelay`） |
| 受理したリレーの OK メッセージの受け渡し | `network/NostrRepository.kt`（`RelayPublishResult.acceptedMessages`） |
| DM の種類（`GroupLayout.Direct`）、参加者・ラベルの読み取り、DM の見出し、隠した DM（kind 30622） | `chat/GroupState.kt` |
| 「＋」メニュー、作成シート、DM の宛先シート、再開の帯、一覧の DM の区切り | `ui/chat/ChatTabScreen.kt`、`ui/chat/ChatTabViewModel.kt` |
| DM の画面（見出しは参加者名、「DM（暗号化なし）」の表示、リンクのコピーと退出は出さない） | `ui/chat/GroupChatScreen.kt`、`ui/chat/GroupChatViewModel.kt` |
| NIP-46 の署名器に頼む kind に 9002・9007・41010 を追加 | `account/nip46/Nip46Signer.kt` |

### 設計からの変更

- 入口は右下の「＋」（3.1）。
- 作成先は、シートの最初の段ではなくシート内の「作成先」で選ぶ（複数あるときだけ選べる）。標準のリレーは、参加中のグループが無くても、ヘッダーで選んでいれば作成先になる。
- 作成の確認は、39000 と 39002 を1回の要求（フィルター2つ、それぞれ `limit: 1`）で読む。
- 作成の間は1本の接続を保つ（`keepingRelay`）。手順ごとにつなぎ直すと、そのたびに NIP-42 の署名が要るため（Web 版の確認で見つけた）。
- 標準の NIP-29 で、作れたのに参加中の一覧（10009）へ足せなかったときは、端末に「承認待ち」として残す。
- NIP-46 の既存の接続は、接続時に伝えた権限に 9002・9007・41010 が入っていない。署名器によっては、作成のときに改めて承認を求められる。

### 未実装

- 段階C（Solo の New chat）。エージェントの候補の取り方（未確定事項5）が決まっていない。
- リレーのプロフィール検索を使った DM の宛先検索（未確定事項3）。今は「参加中のチャンネルのメンバー」と npub / hex の直接指定。
- 「すべて」表示での DM の区切り（個別のコミュニティを選んだときだけ「DM」で区切る。「すべて」ではコミュニティごとのまとまりの中に混ぜる）。

### 検証

- 単体テスト: 追加 約45件（`GroupCreationTest`・`GroupCreatorTest`・`GroupDirectTest`・`ChatCreateSheetTest`）。既存の2件を前提の変更に合わせて直した（DM の種類が `Hidden` → `Direct`、NIP-46 の kind の網羅に命令の kind を追加）。
- Web 版（ヘッドレス Chrome、リレーはドライバーが模したもの。実リレーには接続していない）で確認したこと:
  - Buzz: 「＋」メニュー → 新しいチャンネル → 同名（公開）は「この名前は既に使われています」→ 名前を変えて作成 → そのチャット画面が開き投稿できる → 一覧に出る。
  - Buzz: リレーが OK を返さない場合 → 「作成が終わりませんでした」と再開の帯 → 再開で**同じ署名済みイベント**を送り直して開く（チャンネルは1つだけ）。
  - Buzz: 新しい DM → 相手を選ぶ → 開く → 発言 → 一覧の「DM」の区切りに相手の名前で出る。
  - 標準: リレーを選ぶ → 新しいグループ（非公開）→ 9007 → 9002（`name`・`private`・`closed`）→ 画面が開く → 自分の write リレー宛ての 10009 に `["group", id, relay, name]`。
- 確認していないこと: 実機（Android・iOS）、実在の Buzz コミュニティと NIP-29 リレー、フォーラムの作成、3人以上の DM、複数の作成先の切り替え、外部署名器（NIP-46）、14分を過ぎた再開（単体テストのみ）、標準で 9002 だけ失敗した後の再開（単体テストのみ）。未確定事項 1〜4 は未解決のまま。

## 10. グループ情報の編集（2026-11-17 追加、実装済み）

自分が作った（管理している）グループの名前・説明・公開範囲を、チャット画面の「︙ → グループを編集」から変えられるようにした。

### 調査結果

- **Buzz の kind 9002 は部分更新**（`buzz-relay/src/handlers/side_effects.rs` の `persist_edit_metadata`）。無いタグは「変更なし」。`name`・`about`・`visibility`（`open` / `private`）・`archived`・`ttl`・`t`・`join_role`・`unlisted` は、チャンネルの owner か admin だけが変えられる。`topic`・`purpose` は投稿できるメンバーなら変えられる。
  - `t` を送るとラベルの集合が置き換わる（`buzz.solo` などが消える）ので、編集では送らない。
  - `["about", ""]` で説明を消せる。
  - 拒否理由: 権限なしは `invalid: actor not authorized for name/about/…`、同名（公開チャンネル）は `invalid: an open channel named "…" already exists`。
  - 認識できるタグが1つも無い 9002 は拒否される。
- **標準の NIP-29 の kind 9002 は「グループ情報の全フィールド」**。フラグの外し方は本文に無い。
- 管理者は kind 39001（Buzz は owner と admin を載せる）。Buzz の kind 39000 には作成者の `P` タグがある。

### 仕様

| 項目 | Buzz | 標準の NIP-29 |
| --- | --- | --- |
| 入口を出す条件 | 作成者（39000 の `P`）か管理者（39001）。DM には出さない | 管理者（39001） |
| 名前・説明 | 変えた項目だけを送る | 今の内容をすべて送る（画像と公開範囲のフラグも今のまま付ける） |
| 公開範囲 | 変えられる。非公開 → 公開は「これまでの発言を誰でも読める」と赤字で知らせる | 変えられない（フラグの外し方がリレーごとに違うため） |

- 入口の条件は表示のためだけで、実際の可否はリレーが決める。拒否されたら理由を編集画面に出し、入力は残す。
- 保存できたら、リレーが新しい kind 39000 を出すまでは編集後の内容を表示する（古い 39000 で元に戻って見えないように）。
- 名前の規則は作成と同じ（先頭の `#` と空白を除いて 1〜255 文字）。何も変わっていなければ［保存］は押せない。

### 実装

| 部品 | 場所 |
| --- | --- |
| 編集できるかの判定、変更の検出、9002 の組み立て（Buzz / 標準） | `chat/GroupEditing.kt` |
| 作成者（`P` タグ）の読み取り | `chat/GroupState.kt` |
| 権限なしの拒否理由の分類 | `chat/GroupCreation.kt`（`classifyCreateRejection`） |
| 編集の状態と保存 | `ui/chat/GroupChatViewModel.kt`（`openEdit`・`updateEdit`・`saveEdit`） |
| メニューの項目と編集ダイアログ | `ui/chat/GroupChatScreen.kt` |

### 検証

- 単体テスト `GroupEditingTest`（11件）。
- Web 版（模擬の Buzz リレー）で確認: 自分が作ったチャンネルのメニューに「グループを編集」が出る → 既にある名前は「この名前は既に使われています」→ 名前・説明・公開範囲を変えて保存 → 送られたのは変えた3項目だけ → 画面の見出しと一覧（名前、説明、鍵のアイコン）に反映。
- 確認していないこと: 実在の Buzz リレーと実機、標準の NIP-29 リレーでの編集（単体テストのみ）、他人が作ったチャンネルで入口が出ないこと（単体テストのみ）、権限なしの拒否の表示。

### 対象外

画像（アイコン）の変更、アーカイブと削除、メンバーの追加・削除と役割の変更、トピック（`topic` / `purpose`）、期限、参加時の役割（`join_role`）。
