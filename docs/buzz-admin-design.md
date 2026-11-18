# Buzz の Owner / Admin の操作 設計

- 作成日: 2026-11-18
- ステータス: 実装済み（Web 版で模擬リレーを使って確認。実機・実在の Buzz コミュニティでは未確認、11章）
- 前提: [nip29-buzz-design.md](nip29-buzz-design.md)（チャットタブ）、[chat-create-design.md](chat-create-design.md)（チャンネルの作成と、名前・説明・公開範囲の編集。10章）。両書で対象外にしていた「管理」のうち、Buzz の owner / admin が行う操作を扱う。
- 調査対象: `block/buzz`（75d78ea、2026-11-15）の `NOSTR.md`、`crates/buzz-relay/src/handlers/{relay_admin,ingest,side_effects,req}.rs`、`api/invites.rs`。`block/buzz-app`（456bc5e、2026-11-16）の `features/channel-members/administration-protocol.ts`、`features/relay/channel-lifecycle-protocol.ts`、`features/communities/{admin-protocol,roster,native-api}.ts`、`bundled/moderation/*`。
- 対象範囲（ユーザーの選択）: ① チャンネルのメンバー管理、② チャンネルの管理操作、③ コミュニティの招待とメンバー。モデレーション（追放・発言禁止・通報の処理、kind 9040〜9044）は対象外。

## 1. Buzz の権限は2層

| 層 | 役割 | どこに出るか | 何を決めるか |
| --- | --- | --- | --- |
| コミュニティ（NIP-43） | `owner` / `admin` / `member` | kind 13534（リレー署名、`["member", pubkey, role]`） | 招待の発行、コミュニティのメンバーの削除、役割の変更 |
| チャンネル（NIP-29） | `owner` / `admin` / `member` / `guest` / `bot` | kind 39002（`["p", pubkey, "", role]`）、39001（`["p", pubkey, role]`、owner と admin だけ） | チャンネルのメンバーと役割、アーカイブ、削除、他人の発言の削除、参加時の役割など |

- 2つは独立している。コミュニティの owner でも、チャンネルではただの member のことがある（逆も）。
- どちらも**表示のための判定**で、実際の可否はリレーが決める。拒否されたら理由を見せる。

## 2. 調査結果

### 2.1 チャンネルの役割ごとにできること（`NOSTR.md` の Announce channels）

| 操作 | guest | member / bot | admin / owner |
| --- | --- | --- | --- |
| 読む、退出する | ○ | ○ | ○ |
| 投稿、返信、自分の発言の編集・削除、リアクション | × | ○ | ○ |
| 人を加える（9000） | × | member・guest・bot として | どの役割でも |
| 名前、説明、公開範囲、`join_role`、`unlisted`、アーカイブ | × | × | ○ |
| 他人を外す（9001）、他人の発言を消す（9005） | × | × | ○ |
| チャンネルの削除（9008） | × | × | owner だけ |

- 最後の owner は退出できない（`Last-owner guard`）。Buzz アプリは「Transfer ownership before leaving the channel.」と出して退出を出さない。
- Buzz アプリの UI（`memberAuthority`・`lifecycleRules`）:
  - 役割の変更と追加は、DM でもアーカイブ済みでもなく、Solo でもないチャンネルで、自分が owner / admin のとき。変えられる役割は `admin` / `member` / `guest`。
  - 外すのは、DM でもアーカイブ済みでもないチャンネルで、自分が owner / admin のとき（Solo でも可）。
  - 対象: 自分以外で、役割が `owner` でも不明でもない人。
  - アーカイブ: DM でなく、アーカイブされておらず、owner / admin。解除: アーカイブ済みで owner / admin。削除: DM でなく、アーカイブされておらず、owner。

### 2.2 チャンネルの命令

| 操作 | kind | タグ | 備考 |
| --- | --- | --- | --- |
| 加える・役割を変える | 9000 | `["h", id]`、`["p", pubkey]`、`["role", r]` | 既にメンバーなら役割の変更になる。同じ役割なら何も起きない |
| 外す | 9001 | `["h", id]`、`["p", pubkey]` | |
| アーカイブ・解除 | 9002 | `["h", id]`、`["archived", "true" \| "false"]` | 部分更新なので他の項目は変わらない |
| 参加時の役割 | 9002 | `["h", id]`、`["join_role", "member" \| "guest"]` | 既存のメンバーの役割は変わらない。`guest` は「お知らせチャンネル」（誰でも読めるが書けない） |
| 一覧に出さない | 9002 | `["h", id]`、`["unlisted", "true" \| "false"]` | NIP-11 の `supported_extensions` に `buzz-unlisted-channels` があるリレーだけ。古いリレーは無視する |
| 発言を消す（管理者） | 9005 | `["h", id]`、`["e", 対象]` | 対象はちょうど1つ（`e` と `a` の合計が1でないと拒否）。同じチャンネルの発言だけ |
| 削除 | 9008 | `["h", id]` | owner だけ |

- 変更の後、リレーは新しい 39000 / 39001 / 39002 を出す。`#d` でチャンネルを指定した購読（チャット画面が張っている）には、EOSE の後も新しいものが届く。
- 39000 には `["join_role", r]` が常に、`["unlisted"]` が設定中に付く。

### 2.3 コミュニティの命令（NIP-43）

| 操作 | kind | タグ | 送れる人 |
| --- | --- | --- | --- |
| メンバーを加える | 9030 | `["p", pubkey]`、`["role", r]` | admin / owner（admin は member だけ加えられる） |
| メンバーを外す | 9031 | `["p", pubkey]` | admin / owner（admin は member だけ外せる。自分と owner は外せない） |
| 役割を変える | 9032 | `["p", pubkey]`、`["role", "admin" \| "member"]` | owner だけ。自分と owner の役割は変えられない。`owner` にはできない |

- `h` タグを持たない「コミュニティ全体の命令」。保存されず、リレーがその場で処理して OK を返す。WebSocket の EVENT でも REST の `/events` でも同じ `ingest` を通る（Buzz アプリは REST を使う）。
- 拒否の文言（`admin-protocol.ts` の `REASONS`、`relay_admin.rs`）: `invalid: actor not authorized: must be admin or owner` / `…: must be owner` / `…: only owner can grant admin role` / `…: admins can only remove members` / `invalid: cannot remove yourself` / `invalid: cannot remove the relay owner` / `invalid: cannot change your own role` / `invalid: cannot change the relay owner's role` / `invalid: cannot set role to owner` / `invalid: member not found: <hex>`。
- 変更の後、リレーは新しい 13534 を出す。
- **13534 の読み取り**: `{kinds:[13534], authors:[リレーの鍵], limit:1}`。認証したメンバーなら読める（`req.rs` の `filters_are_nip43_membership_only`。チャンネルの権限の計算を飛ばす特別扱いがある）。リレーの鍵（NIP-11 の `self`）と署名者が一致するものだけを使う。
- Buzz アプリはメンバーの直接追加（9030）を UI から隠している（`DIRECT_MEMBER_ADD_ENABLED`）。人を増やすのは招待で行う。

### 2.4 招待の発行（REST）

- `POST https://<host>/api/invites`、本文 `{"ttl_secs": n, "max_uses": m | null}`。NIP-98 の `Authorization` が必須で、本文の SHA-256 を `payload` タグに入れる（`make_nip98_closure_for_admission(..., true, true)`）。
- `ttl_secs` は 60〜2,592,000（30日）、`max_uses` は 1〜10,000 か null（無制限）。
- 送れるのはコミュニティの owner / admin だけ。それ以外は 403 `{"error":"only relay owners and admins can create invites"}`。
- 応答: `{"code", "expires_at"(Unix 秒), "max_uses", "uses_remaining", "url": "https://<host>/invite/<code>"}`。`url` は ToriNos の参加シートがそのまま受け付ける形（nip29-buzz-design.md 2.1）。
- 発行した招待の一覧・取り消しの API は無い（Buzz アプリにも無い）。

### 2.5 ToriNos の現状

- チャット画面は `#d` 付きで 39000〜39002 を購読していて、`admins`（39001）と `members`（39002）を持つ。39002 の役割は読んでいない。
- 管理者の 9005 は、表示（`GroupTimeline.setModerators`）には既に反映される。送る手段が無い。
- 送信は `ChatAccount.publishToGroup`（`h` が1つ・送信先がそのリレーだけ、を `groupPublishViolation` で検査）と、`h` を持たない Buzz の命令の `buzzCommandViolation`（今は 41010 だけ）。
- NIP-98 の署名と REST は `BuzzJoinService`（`BuzzHttp`、`nip98Tags`、`nostrAuthorizationHeader`）にある。
- 宛先の選択は「新しい DM」のシート（コミュニティのメンバー候補 + npub 直接指定）がある。

## 3. UI 設計

### 3.1 チャンネル: メンバー（チャット画面の「グループの情報」シート）

メンバーの行に役割を出し、管理できる人には行の右に「︙」を出す。

```
メンバー 12人                         ［＋ 追加］   ← canManage のときだけ
 (●) Alice        オーナー
 (●) Bob          管理者           ︙
 (●) Carol                         ︙
 (●) Dave         ゲスト           ︙
 (●) 自分（自分） 管理者
```

- 役割の表示: オーナー / 管理者 / ゲスト / ボット。member は出さない。
- 「︙」の項目: 「管理者にする」「メンバーにする」「ゲストにする（読むだけ）」のうち今の役割以外、区切り、「チャンネルから外す」（赤）。
- 外すときは確認ダイアログ「〇〇 をチャンネルから外しますか？」。
- 送っている間はその行に小さな進行表示。リレーが新しい 39002 を出すまでは、変更後の役割（外した人は消す）を表示する（編集の `savedEdit` と同じ考え方）。
- 拒否されたら、シートの下にエラーを出す（下の 3.5）。
- ［＋ 追加］: 「新しい DM」と同じ宛先シート（コミュニティのメンバー候補から既存のメンバーを除いたもの + npub）。複数選べる。役割（メンバー / ゲスト / 管理者）を選んで［追加］。1人ずつ 9000 を送る（Buzz アプリと同じ。1つのイベントに複数人を入れる形は応答の解析が増えるので使わない）。

### 3.2 チャンネル: 管理の操作（チャット画面の「︙」メニュー）

```
グループを編集
グループのリンクをコピー
──────────
アーカイブする              ← owner / admin、稼働中
アーカイブを解除する        ← owner / admin、アーカイブ済み
チャンネルを削除            ← owner、稼働中（赤）
──────────
グループから退出            ← 最後の owner は出さない（下の注記）
```

- アーカイブ: 確認ダイアログ「アーカイブすると、一覧の『アーカイブ済み』に移り、新しい投稿ができなくなります」。
- 削除: 確認ダイアログ「チャンネル『〇〇』を削除します。メッセージはすべて読めなくなり、元に戻せません」。削除できたら一覧へ戻り、チャットタブの一覧を読み直す。
- 最後の owner: 「グループから退出」の代わりに、灰色の「退出するには先にほかの人をオーナーにしてください」。ToriNos からは owner を渡せない（Buzz アプリも UI では渡さない）ので、説明だけ。
- 情報シートの見出しの注記に「アーカイブ済み」「一覧に出さない」「参加するとゲスト」を足す。

### 3.3 チャンネル: 編集ダイアログの追加項目（Buzz だけ）

既存の「グループを編集」（名前・説明・公開範囲）に足す。

- 「参加した人の役割」: ［メンバー］［ゲスト（読むだけ）］。ゲストを選ぶと「誰でも読めますが、書けるのは管理者が加えたメンバーだけです。今のメンバーはそのまま書けます」。
- 「一覧に出さない（リンクを知っている人だけ）」のスイッチ: 公開のチャンネルで、リレーが `buzz-unlisted-channels` を名乗るときだけ出す。
- 変えた項目だけを送る（既存の `buildBuzzEditMetadata` の延長）。

### 3.4 チャンネル: 他人の発言の削除

- 発言の「︙」に、自分の発言でなく、自分がそのチャンネルの owner / admin で、DM でないとき「削除（管理者）」を出す。確認ダイアログ「〇〇 の発言を削除しますか？」。
- 送るのは 9005（1件ずつ）。届いたら表示から消える（既存の `setModerators` の経路）。

### 3.5 コミュニティの管理（チャットタブ）

入口: チャットタブのヘッダーの切り替えメニューで Buzz コミュニティを選んでいて、そのコミュニティで自分が owner / admin のとき、メニューの下に「コミュニティの管理…」。

シート「コミュニティの管理 — png.communities.buzz.xyz」:

```
［メンバー］［招待］

── メンバー（42人） ──────────
 (●) Alice      オーナー
 (●) Bob        管理者        ︙
 (●) Carol                    ︙
 …
```

- 役割: オーナー / 管理者。member は出さない。並び: オーナー、管理者、そのほか（名前順）、自分は最後。
- 「︙」（Buzz アプリの `allowedActions` と同じ）:
  - 自分が owner: 相手が admin なら「メンバーにする」、member なら「管理者にする」、区切り、「コミュニティから外す」（赤）。
  - 自分が admin: 相手が member のときだけ「コミュニティから外す」。
  - 自分と owner には出さない。
- 外すとき: 確認ダイアログ「〇〇 をコミュニティから外しますか？ このコミュニティにログインできなくなります」。
- 変更が受け付けられたら 13534 を読み直す。

```
［メンバー］［招待］

 有効期限   ( 1時間 | 1日 | 7日 | 30日 )      既定: 7日
 使える回数 ( 1回 | 10回 | 無制限 )            既定: 1回
                                   ［招待リンクを作る］
 ── 作った招待 ──
 https://png.communities.buzz.xyz/invite/v2.xxxx…   ［コピー］
 11月25日 18:00 まで · 1回
```

- 作った招待は、このシートを開いている間だけ一覧に出す（保存しない。発行済みの一覧を読む API が無いため）。
- 招待のコードはログに出さない（nip29-buzz-design.md の規則）。
- 「招待リンクを知っている人は誰でも参加できます」と注記する。

### 3.6 エラーの出し方

リレーの拒否は、知っている文言だけを日本語にし、知らない文言は「受け付けられませんでした（<原文>）」にする。

| 原文（前方一致・部分一致） | 表示 |
| --- | --- |
| `actor not authorized`（全般） | 権限がありません |
| `only owner can grant admin role` | 管理者にできるのはオーナーだけです |
| `admins can only remove members` | 管理者が外せるのはメンバーだけです |
| `cannot remove yourself` / `cannot change your own role` | 自分には使えません |
| `cannot remove the relay owner` / `cannot change the relay owner's role` | オーナーには使えません |
| `member not found` | すでにメンバーではありません |
| `only relay owners and admins can create invites` | 招待を作れるのはオーナーと管理者だけです |
| `ttl_secs must be` / `max_uses must be` | 期限・回数の指定が正しくありません |
| `community writes are temporarily unavailable` | 一時的に変更できません。しばらくしてからお試しください |
| `auth-required:` / `restricted:` | ログインできていないか、権限がありません |

## 4. 実装設計

### 4.1 チャンネル（`chat/ChannelAdministration.kt`、純粋関数）

```kotlin
enum class ChannelRole { Owner, Admin, Member, Guest, Bot, Unknown }

/** 最新の 39002（リレー署名）から役割。39002 が無ければ null。 */
fun channelRoles(events: Iterable<NostrEvent>, groupId: String, relayPubkey: String?): Map<String, ChannelRole>?

data class ChannelAuthority(
    val viewerRole: ChannelRole?,
    val canManage: Boolean,      // 役割の変更と追加
    val canRemove: Boolean,      // 外す、他人の発言の削除
    val canArchive: Boolean, val canUnarchive: Boolean, val canDelete: Boolean,
    val isLastOwner: Boolean,
)
fun channelAuthority(metadata: GroupMetadata?, roles: Map<String, ChannelRole>?, viewer: String?): ChannelAuthority
fun canChangeRole(authority, roles, viewer, target): Boolean
fun canRemoveMember(authority, roles, viewer, target): Boolean

fun buildChannelPutUser(groupId, pubkey, role: ChannelRole): GroupEventDraft     // 9000、role は Admin/Member/Guest だけ
fun buildChannelRemoveUser(groupId, pubkey): GroupEventDraft                     // 9001
fun buildChannelArchive(groupId, archived: Boolean): GroupEventDraft             // 9002
fun buildChannelDelete(groupId): GroupEventDraft                                 // 9008
fun buildAdminDeletion(groupId, eventId): GroupEventDraft                        // 9005、e は1つ

/** 送った変更を、リレーの新しい 39002 が届くまで重ねる。 */
fun applyPendingRoles(roles: Map<String, ChannelRole>, pending: Map<String, ChannelRole?>): Map<String, ChannelRole>
fun adminRejectionMessage(reason: String): String
```

- 役割は 39002 だけから読む。39001 は既存どおり「管理者」の集合（表示と 9005 の反映）に使い、権限の判定には 39002 の役割を使う（Buzz アプリは両者の食い違いをエラーにするが、ToriNos は表示の判定なので 39002 に合わせ、食い違いで操作を隠しはしない。リレーが最終判断する）。
- `relayPubkey`（NIP-11 の `self`）が分からないときは、役割を読まない（管理の操作を出さない）。署名者を確かめられないため。
- `GroupMetadata` に `joinRole: String?`、`isUnlisted: Boolean` を足す。

### 4.2 コミュニティ（`chat/CommunityAdministration.kt`）

```kotlin
const val COMMUNITY_ROSTER_KIND = 13534
const val COMMUNITY_ADD_MEMBER_KIND = 9030
const val COMMUNITY_REMOVE_MEMBER_KIND = 9031
const val COMMUNITY_CHANGE_ROLE_KIND = 9032

enum class CommunityRole { Owner, Admin, Member }
data class CommunityMember(val pubkey: String, val role: CommunityRole)

fun parseCommunityRoster(event: NostrEvent, relayPubkey: String): List<CommunityMember>?   // 署名者が違えば null
fun latestCommunityRoster(events, relayPubkey): List<CommunityMember>?
enum class CommunityAction { Promote, Demote, Remove }
fun allowedCommunityActions(actor: CommunityRole?, target: CommunityMember, self: Boolean): List<CommunityAction>
fun buildCommunityRemove(pubkey): GroupEventDraft
fun buildCommunityRole(pubkey, role: CommunityRole): GroupEventDraft   // Admin/Member だけ

data class InviteRequest(val ttlSeconds: Long, val maxUses: Int?)   // 範囲を検査
fun inviteRequestBody(request): String
data class MintedInvite(val code: String, val url: String, val expiresAt: Long, val maxUses: Int?, val usesRemaining: Int?)
fun parseMintedInvite(body: String, origin: String): MintedInvite?    // url がそのコミュニティの /invite/ でなければ null
fun inviteRefusalMessage(status: Int, body: String): String
```

- 招待の発行は `BuzzInviteService(http, signer)`（`BuzzJoinService` と同じ作り）。`nip98Tags(url, "POST", body)` で署名する。
- `parseMintedInvite` は `url` が `https://<そのホスト>/invite/` で始まることを確かめる（別のホストのリンクを配らない）。

### 4.3 送信の経路（`ChatAccount`）

- チャンネルの命令（9000・9001・9002・9005・9008）は `publishToGroup` を使う。`GROUP_PUBLISHABLE_KINDS` に 9000・9001・9005・9008 を足す（`h` が1つ・送信先がそのリレーだけ、の検査はそのまま効く）。
- コミュニティの命令（9031・9032）は `h` を持たないので、`BUZZ_COMMAND_KINDS` に足し、`buzzCommandViolation` で「Buzz のコミュニティで、そのリレーだけへ」を確かめてから `publishKeepingRelay` で送る（41010 と同じ経路）。9030 は UI で使わないので足さない。
- `markChannelDeleted(ref)` / `deletedChannels: StateFlow<Set<GroupRef>>`: 削除の OK を受けたチャンネル（端末のメモリだけ）。チャットタブの一覧はこれを除く。リレーの読み直しや控え（`ChatListCache`）に古い 39000 が残っていても、削除したものを出し直さないため。
- `fetchCommunityRoster(relayUrl)`: `keepingRelay` の中で `{kinds:[13534], authors:[self], limit:1}` を読む。`self` は `RelayInformationRepository` から。
- `mintInvite(relayUrl, request)`: `BuzzInviteService` を使う。
- NIP-46 の `NIP46_SIGNED_KINDS` に 9000・9001・9005・9008・9031・9032 を足す（27235 は既にある）。

### 4.4 画面と状態

- `GroupChatViewModel`: `roles`、`authority`、`pendingRoles`（送った変更と、送った時点の最新の 39002 の ID。**それと違う ID の 39002** が届いたら外す。`created_at` で比べると、同じ秒に出た新しい 39002 を見落とすため）、`busyMembers`、`adminSheet`（追加のシート）、`confirm`（確認ダイアログの内容）。操作: `changeRole`・`removeMember`・`openAddMembers`・`addMembers`・`archive`・`unarchive`・`deleteChannel`・`adminDelete`。
- `GroupChatScreen`: 情報シートのメンバー行、追加シート、メニュー項目、確認ダイアログ、編集ダイアログの追加項目、発言の「削除（管理者）」。
- `ChatTabViewModel`: 選んでいる Buzz コミュニティの自分の役割（13534 を読む。選び直したときと、管理シートを開いたときに読む）、`communityAdmin`（シートの状態: タブ、メンバー、送信中、作った招待、エラー）。
- `ChatTabScreen`: 「コミュニティの管理…」とシート。

## 5. 実装順（テスト先行）

1. `ChannelAdministrationTest`: 役割の読み取り（署名者、`d`、最新、不明な役割）、権限（役割・DM・アーカイブ・Solo・最後の owner）、対象ごとの可否、イベントの形、重ね合わせ、拒否理由の文言。
2. `CommunityAdministrationTest`: 13534 の読み取り（署名者の不一致、重複、不明な役割）、`allowedCommunityActions` の表、命令の形、招待の範囲と本文、応答の解析（別ホストの URL を拒否）、拒否理由。
3. `BuzzInviteServiceTest`: NIP-98 の `u`・`method`・`payload`、403 の文言。
4. 送信の検査（`groupPublishViolation` に新しい kind、`buzzCommandViolation` に 9031・9032、9030 は不可）、NIP-46 の kind の網羅（既存のテスト）。
5. ViewModel と画面。
6. Web 版で模擬の Buzz リレーを使って確認。

## 6. 検証

- 単体テスト（上の5章）と、Android・iOS・Wasm のコンパイル、`verifyNoDirectProfileSubscriptions`。
- Web 版（ヘッドレス Chrome、`routeWebSocket` の模擬 Buzz リレー。実リレーには送らない）:
  - チャンネル: 役割の表示、役割の変更（9000 の形と、新しい 39002 での表示）、外す（確認→9001）、追加（宛先シート→9000×人数）、アーカイブ（確認→9002）→「アーカイブ済み」、解除、削除（確認→9008→一覧へ戻る）、他人の発言の削除（9005→消える）、最後の owner の退出の案内、member には何も出ないこと。
  - 編集: 参加時の役割、一覧に出さない（`buzz-unlisted-channels` の有無で出し分け）。
  - コミュニティ: owner で「コミュニティの管理…」が出る、member では出ない。役割の変更（9032）、外す（9031）、admin のときの項目の絞り込み、拒否理由の表示、招待の発行（NIP-98 の検算、`payload` の一致）→リンクのコピー。
- 実在の Buzz コミュニティと実機での確認は、この作業では行わない（実在のコミュニティのメンバーや招待を勝手に変えないため）。

## 7. 懸念とリスク

- **取り返しのつかない操作**: 削除・外す・アーカイブはすべて確認ダイアログを通す。削除は名前を出して念を押す。
- **権限の表示と実際のずれ**: 39002・13534 は古いことがある。表示は目安で、拒否は理由を見せる。変更の後は新しい状態が届くまで変更後を重ねて見せる（届かなければ次の購読で正しい状態に戻る）。
- **WebSocket で 9031・9032 が受け付けられない場合**: Buzz アプリは REST の `/events` で送っている。`ingest` は共通だが、もし WebSocket で `restricted:` などで断られるなら、REST（NIP-98 付き `POST /events`）へ切り替える。実在のリレーで確かめるまでは未確定（8章）。
- **招待のリンクの扱い**: 作った招待は画面にしか出さず、ログ・保存に残さない。
- **NIP-46**: 既存の接続は新しい kind の許可を持たないので、署名器によっては操作のたびに承認を求められる。

## 8. 未確定事項

1. 9031・9032 を WebSocket の EVENT で送ったときに、本番の Buzz が REST と同じに受け付けるか（7章）。
2. 削除（9008）の後、そのチャンネルの 39000 がどう見えるか（消えるのか、残るのか）。ToriNos は削除の OK を受けた時点で一覧から外し、読み直しに任せる。
3. コミュニティの admin が、13534 を読めない設定のコミュニティがあるか（読めなければ「コミュニティの管理…」は出ない）。

## 9. セルフレビューの記録

- 反映待ちの重ね合わせを `created_at` の比較にしていた。リレーは変更と同じ秒に新しい 39002 を出すことがあり、`>` では外れず `>=` では古いものでも外れる。送った時点の最新の 39002 の ID と違うものが届いたら外す形にした（4.4）。
- 削除の後、チャットタブの控え（`ChatListCache`）と、答えがそろわないときに前回の一覧を残す規則（`refreshBuzz`）のために、削除したチャンネルが一覧に出続けることがある。削除したものを端末のメモリで除く（4.3）。
- 39001 と 39002 の役割が食い違うとき、Buzz アプリは操作をすべて止める。ToriNos は表示の判定なので 39002 に合わせる（4.1 に明記）。リレーが最終判断するため、誤って出した操作は拒否されて理由が出るだけで済む。
- （2026-11-18 改訂）当初は owner / admin のときだけ［＋ 追加］を出していたが、ユーザーの要望で、投稿できるメンバー（member・bot）にも出すようにした。根拠はリレーのコード `buzz-core/src/channel.rs` の `decide_member_put` と `may_change_role`: ゲスト以外のメンバーは member・guest として人を加えられ、ゲストをメンバーにできる。管理者を付ける・ほかの役割の変更・外すは owner / admin だけ。非公開のチャンネルでも、メンバーなら加えられる（`NOSTR.md` の「Private: owner/admin only」はコードと食い違っていて、コードに従った）。相手が自分のエージェントの追加を断る設定（`channel_add_policy` が `owner_only` / `nobody`）なら、リレーが `policy:` で断る。
- 9030（直接追加）は送信の許可リストにも入れない。UI で使わない kind を送れる状態にしない。

## 10. 対象外

モデレーション（9040〜9044）、メンバーの直接追加（9030）、owner の譲渡、`auto_join`（既定のチャンネル。リレーの owner / admin の操作で、チャンネルの役割とも別）、ラベル、期限（`ttl`）、`topic` / `purpose`、招待の一覧と取り消し、標準の NIP-29 リレーでの管理（9000 / 9001 / 9005 / 9008 は NIP-29 にもあるが、役割の決め方がリレーごとに違うため、今回は Buzz だけ）。

## 11. 実装状況（2026-11-18）

### 実装したもの

| 部品 | 場所 |
| --- | --- |
| チャンネルの役割の読み取り、権限、命令（9000・9001・9002 archived・9005・9008）、反映待ちの重ね合わせ、拒否理由の文言 | `chat/ChannelAdministration.kt` |
| コミュニティのメンバー一覧（13534）、操作の可否、命令（9031・9032）、招待の発行（`BuzzInviteService`） | `chat/CommunityAdministration.kt` |
| 参加した人の役割・一覧に出さない（39000 の読み取りと 9002） | `chat/GroupState.kt`（`joinRole`・`isUnlisted`）、`chat/GroupEditing.kt` |
| 送信の許可（9000・9001・9005・9008、Buzz の命令に 9031・9032）、NIP-46 の kind | `chat/GroupMessages.kt`、`chat/GroupCreation.kt`、`account/nip46/Nip46Signer.kt` |
| メンバー一覧の読み取り、コミュニティの命令、招待、削除したチャンネルの記録 | `chat/ChatAccount.kt`（`fetchCommunityRoster`・`sendCommunityCommand`・`mintInvite`・`deletedChannels`） |
| チャット画面: メンバーの役割と「︙」、追加のシート、アーカイブ・解除・削除、最後のオーナーの案内、発言の「削除（管理者）」、確認ダイアログ、編集の追加項目 | `ui/chat/GroupChatViewModel.kt`、`ui/chat/GroupChatScreen.kt` |
| チャットタブ: 「コミュニティの管理…」とシート（メンバー・招待）、削除したチャンネルを一覧から外す | `ui/chat/ChatTabViewModel.kt`、`ui/chat/ChatTabScreen.kt`、`chat/ChatPresentation.kt`（`inviteSummary`） |

### 設計からの変更

- 加えるシートの候補は、コミュニティのメンバー一覧（13534）から、今のチャンネルのメンバーを除いたもの（「新しい DM」の候補の取り方ではなく）。コミュニティから外した人が候補に出ない。
- グループの編集は、ダイアログからボトムシート（上にキャンセル・保存、プロフィール編集と同じ形）に変えた。公開範囲と参加した人の役割は、アイコン・説明付きの選択カード（選んだものは枠とチェック）にし、「一覧に出さない」はスイッチ付きのカードにした。非公開から公開にするときの注意と「今のメンバーの役割は変わりません」は帯で出す。

### メンバーによる追加（2026-11-18 追加）

- `ChannelAuthority` に `canAdd`（DM・アーカイブ済み・Solo でなく、自分がゲスト以外のメンバー）と `addableRoles`（owner / admin はメンバー・ゲスト・管理者、ほかはメンバー・ゲスト）を足した。`roleChoicesFor` で、owner / admin 以外はゲスト → メンバーだけを選べる。
- Web 版で、メンバーとして alice-room を開き、［＋ 追加］が出る・「︙」はゲストにだけ出て「メンバーにする」だけ・追加のシートの役割に管理者が無い、を確認。ゲスト → メンバー（9000 `role member`）と、Dave の追加（9000 `role member`）が受け付けられた（模擬リレーは `decide_member_put` と同じ規則にした）。

### 検証

- 単体テスト: 追加 約50件（`ChannelAdministrationTest`・`CommunityAdministrationTest`・`GroupEditingTest` の追加分・`BuzzAdminUiTest`）。全体 2264件のうち失敗は1件で、今回の変更前からある `Nip46Test.permissionsCoverChatKinds`（入力中の kind 20002 が NIP-46 の一覧に無い。今回の範囲外として触っていない）。Android・iOS・Wasm のコンパイル、`verifyNoDirectProfileSubscriptions` 成功。
- Web 版（ヘッドレス Chrome、模擬の Buzz リレーと REST。ほかのリレーへの接続もすべて模擬で受け、実リレーには送っていない）で確認したこと:
  - コミュニティ: 「すべて」では入口が出ず、Buzz のコミュニティを選ぶと「コミュニティの管理…」が出る。オーナーで、管理者にする（9032）→ 表示が「管理者」に、外す（確認 → 9031）→ 一覧から消える。管理者のときは、メンバーにだけ「︙」（外すだけ）が出る。
  - 招待: 1日・10回で作成 → `POST /api/invites` の本文 `{"ttl_secs":86400,"max_uses":10}`、NIP-98 の署名・`u`・`method`・`payload`（本文の SHA-256）をリレー側で検証して一致 → リンクと「11月19日 08:53 まで · 10回」を表示。
  - チャンネル（オーナー）: メニューにアーカイブ・削除と「退出するには先にほかの人をオーナーにしてください」。メンバーの役割の表示、ゲストにする（9000 `role guest`）、外す（確認 → 9001）、追加（候補 → 9000 `role member`）、編集で参加時の役割と一覧に出さない（9002 は変えた2項目だけ）→ 情報シートに「メンバーだけが書ける・一覧に出さない・参加するとゲスト」、他人の発言の削除（確認 → 9005 → 表示から消える）、アーカイブ（確認 → 9002 → メニューが「解除」だけに）、解除、削除（確認 → 9008 → 一覧へ戻り、チャンネルが消える）。
  - チャンネル（メンバー）: メニュー・発言の長押し・メンバー一覧のどこにも管理の操作が出ない。
  - 最初のコミュニティの命令は、接続の認証前に送られて `auth-required:` で断られ、認証を待って1回だけ送り直して通った（既存の送り直しの経路）。
- 確認していないこと: 実機（Android・iOS）、実在の Buzz コミュニティ（未確定事項 1〜3）、NIP-46 の署名器、標準の NIP-29 リレー（対象外）。
