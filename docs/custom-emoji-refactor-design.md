# カスタム絵文字 リファクタ設計

作成日: 2026-09-28
状態: S0〜S7、F1〜F11 を実装済み（2026-09-28）。実装で設計から変えた点は9章。

## 0. 仕様整理

カスタム絵文字の仕様は `app-requirements.md` の「カスタム絵文字」節（4行）と NIP 表にしかなく、
kind 10030 同期、お気に入り、最近使ったリアクション、タップ遷移などは実装だけが仕様になっている。
本章は現行実装から読み取れる挙動を書き出したもので、ここと異なる挙動は 6章の修正段階で明示的に変える。

### 0.1 関連するイベント

| kind | 用途 | 読み | 書き |
| --- | --- | --- | --- |
| 0 | プロフィール名の絵文字 | `emoji` タグ（`NostrProfile.toProfile`） | name / display_name 中のコードに `emoji` タグ（`EditProfileViewModel`） |
| 1 / 1111 / 42 | 本文の絵文字 | `emoji` タグ（`customEmojiMap()`） | kind 1・チャンネル作成時の kind 42 は `composeNoteContent` がタグ付与。**チャンネル内の通常送信（`ChannelController.sendMessage`）は付与しない** |
| 7 | 絵文字リアクション | content が `:code:` かつ同名 `emoji` タグ（`toCustomReaction`） | `ReactionOption.eventTags`（3要素タグ） |
| 30315 | ステータス | `StatusEventCodec` | 同左（登録済み絵文字から解決） |
| 10030 | 使う絵文字（NIP-51） | インライン `emoji` = お気に入り、`a` = 絵文字セット参照 | `EmojiPreferenceSynchronizer` が端末状態から生成 |
| 30030 | 絵文字セット | 設定画面・ピッカーの「公開セット」（全体の新しい100件）と 10030 の参照先 | 書かない |

NIP-30 の要点（2026-09 時点の原文で確認）:
- shortcode は英数字・ハイフン・アンダースコア。
- タグは `["emoji", shortcode, url, <emoji-set-address>]`。4要素目は kind 30030 の `kind:pubkey:d` を指す任意の値。
- 対象 kind は 0 / 1 / 1111 / 7 / 30315（kind 42 は仕様上の列挙外だが、他クライアントも付与している）。

### 0.2 端末側の状態（`CustomEmojiStore`、アカウント別キー）

| 状態 | 保存キー | 意味 |
| --- | --- | --- |
| `emojiLists` | `custom_emoji_lists_<pubkey>` | 登録済みセット。id は `pubkey:d`（公開セット由来）または `manual`（旧手動登録） |
| `favoriteEmojis` | `favorite_custom_emojis_<pubkey>` | お気に入り（= 10030 のインライン `emoji`） |
| `emojis` | `custom_emojis_<pubkey>` | 上2つの和集合を **shortcode で重複排除** したもの。派生値だが保存もしている |
| `recentReactions` | `recent_reactions_<pubkey>` | Unicode/カスタム混在の履歴（最大24） |
| `recentEmojiShortcodes` | `recent_custom_emojis_<pubkey>` | 旧形式の履歴。読む画面はもうない |

アカウント切り替えは `AccountSession` → `EmojiPreferenceSynchronizer.start()` → `CustomEmojiStore.activateAccount()`。
初回だけ、アカウント別キーがなく旧グローバルキーがあれば、そのアカウントへ移して旧キーを消す。

### 0.3 画面

- **設定画面**（`CustomEmojiSettingsScreen`、`CustomEmojiRoute(query, imageUrl)`）: 公開セット／登録済みの2タブ、セット詳細、登録・解除、お気に入り切り替え。
  「登録済み」の判定はセットの id ではなく、**全絵文字の shortcode→URL が登録済み集合と一致するか** で行う。
- **ピッカー**（`StandardEmojiPickerSheet`）: 履歴→カスタム→標準の順。検索では登録済みに加えて公開セットの絵文字も候補に出す。
  公開セットの取得のため、開くたびに `CustomEmojiSettingsViewModel` を1つ生成する。
- **クイックメニュー**（`NoteCard` 内）: 最近使ったリアクション16件。
- **本文表示**（`LinkedText`）: イベントの `emoji` タグに **閲覧者の登録済み絵文字を足した** マップで画像化する（タグが優先）。
  どちらにもない `:xxx:` は下線付きリンクにし、タップで設定画面の検索へ移る。
- **タップ遷移**: 本文の絵文字・リアクションチップ（長押し）・`CustomReactionLink` から
  `CustomEmojiStore.requestOpenSearch` → グローバル `SharedFlow` → `AppSessionCoordinator` が `CustomEmojiRoute` へ遷移。
  設定画面は shortcode+URL が一致するセットを登録済み→公開100件の順に探し、見つからなければ検索語として表示する。

### 0.4 同期（kind 10030）

- 起動時に1回だけ最新の 10030 を取得（8秒）。未送信の送信箱（`EmojiPreferenceOutbox`）の方が新しければそちらを採用。
- `a` 参照の 30030 を取得し、取れなかった参照は `unresolvedSetReferences` として次回送信時にも残す。
- 端末の変更は 350ms デバウンスで署名・送信。失敗したリレーは送信箱に残し、30秒ごとに再送。
- 競合解決は「最後に書いた側が全体を置き換える」。起動後に他クライアントの変更は受け取らない。

## 1. 目的

1. 絵文字の同一性を `(shortcode, imageUrl)` に統一し、shortcode 衝突時に絵文字が消えたり差し替わったりしない。
2. `emoji` タグの解析・生成と shortcode 正規表現を1か所にする（現在は解析5か所、正規表現3か所）。
3. 端末状態を「アカウントセッションが所有する不変データ＋純粋な更新関数」にし、common test で固定する。
4. kind 10030 同期で端末の変更が巻き戻る・消える経路をなくす。
5. `CustomEmojiSettingsScreen`（844行）とピッカーから通信・判定ロジックを外し、1ファイル 400行程度にする。

## 2. レビュー結果（現状の問題）

重要度: 高 = データ消失・誤表示、中 = 仕様逸脱・不整合、低 = 保守性。

| # | 重要度 | 問題 | 場所 |
| --- | --- | --- | --- |
| R1 | 高 | 旧「手動登録」リスト（id `manual`）は 10030 にインライン `emoji` として送られ、次回起動の同期で **お気に入りに変わり、リストは消える**。 | `publishCurrentPreferences` の `inlineEmojis`、`refreshFromRelays` |
| R2 | 高 | 公開セットの「解除」は `removeList(set.id, set.emojis)`。id が登録されていないのに「登録済み」と判定された場合（別セットに同じ絵文字がある）、フォールバックで **他のセットから該当絵文字を削除** する。 | `CustomEmojiSettingsScreen` の `isRegistered` と `CustomEmojiStore.removeList` |
| R3 | 高 | 送信処理が、署名前（リレー送信前）に送信時スナップショットを `applySyncedPreferences` で端末へ書き戻す。署名待ち（外部署名アプリ等）の間の操作は巻き戻り、`save()` で保存もされる。後続送信が署名で失敗すると巻き戻りが残る。 | `publishCurrentPreferences` |
| R4 | 高 | `save()` / `saveRecent()` は呼ぶたびに `Dispatchers.Default` へ個別に `launch` するため、書き込み順が保証されず古い状態が最後に残り得る。複数 `StateFlow` の更新も非原子的。 | `CustomEmojiStore` |
| R5 | 中 | `emojis` は shortcode で重複排除するため、別セットの同名絵文字（例: `:kusa:` が2セットにある）は片方しかピッカー・投稿・表示に出ない。`uniqueDraftEmoji` の衝突回避ロジックが入力段階で無効化されている。 | `rebuildEmojis`、`addList`、`LinkedText` |
| R6 | 中 | 本文表示で、作者が `emoji` タグを付けていない `:code:` を **閲覧者の登録画像** で描画する。NIP-30 ではタグのない shortcode は画像にしない。作者の意図と違う画像を出し得る。要件（`app-requirements.md` 199行）もこの挙動を前提にしている。 | `LinkedText`、`NoteCard` の文字数計算 |
| R7 | 中 | 未登録 `:xxx:` を無条件にリンク化するため、`12:30:45` の `:30:` などが下線リンクになる。 | `LinkedText.unregisteredEmojiSegments` |
| R8 | 中 | NIP-30 の4要素目（絵文字セットアドレス）を読みも書きもしない。そのためタップ遷移は「公開セットの新しい100件」から推測するしかなく、古いセットの絵文字は開けない。 | 全タグ生成・解析箇所 |
| R9 | 中 | 公開セット取得の購読IDが `custom-emojis-<generation>` で、ViewModel ごとに1から数える。ピッカー用と設定画面用のVMが同時に生きると同じIDになり、一方の `onCleared` が他方の購読を閉じる。 | `CustomEmojiSettingsViewModel.currentSubId` |
| R10 | 中 | ピッカーを開くたびに全体の kind 30030 を100件購読する（フォロー等での絞り込みなし）。 | `StandardEmojiPickerSheet` |
| R11 | 中 | ログアウト（`deactivateAccount`）で状態を消さないため、前アカウントの絵文字が次の `activateAccount` まで見える。 | `CustomEmojiStore.deactivateAccount` |
| R12 | 中 | チャンネル内の通常送信は `emoji` タグを付けない（入力補助もない）ため、手入力した `:code:` が他クライアントで画像にならない。 | `ChannelController.sendMessage` |
| R13 | 中 | 同期は起動時1回だけで、他クライアントでの変更は再起動まで反映されない。起動直後に端末で操作すると、取得前の古いキャッシュを元に送信してリモートの変更を上書きし得る。 | `EmojiPreferenceSynchronizer` |
| R14 | 低 | `emoji` タグ解析が5か所（`CustomEmojiTags.kt`、`NostrProfile.kt` の private コピー、Synchronizer 2か所、Settings VM）、shortcode 正規表現が3か所（`CustomEmojiTags`、`LinkedText`、`UrlExtraction`）。 | — |
| R15 | 低 | セットの id が文字列で3形式（`30030:pk:d` / `pk:d` / `manual`＋`authorPubkey`）あり、`toEmojiSetReference` と `emojiSetAuthorPubkey` がそれぞれ推測している。 | `CustomEmojiList.id` |
| R16 | 低 | 未使用 API: `add` / `addAll` / `remove` / `markUsed` / `isFavorite` / `recentEmojiShortcodes`。派生値 `emojis` を保存している。 | `CustomEmojiStore` |
| R17 | 低 | UI イベント（設定画面を開く）がデータストアのグローバル `SharedFlow`（replay なし）に載っている。コレクタ不在時は捨てられる。 | `requestOpenSearch` |
| R18 | 低 | `NoteCard`・`LinkedText` の各インスタンスが `CustomEmojiStore.emojis` を購読し、登録変更で全カードが再計算される。 | — |
| R19 | 低 | `CustomEmojiStore` / `EmojiPreferenceSynchronizer` の状態遷移にテストがない（タグ解析3件のみ）。 | commonTest |
| R20 | 低 | 公開セットの重複排除が「作者＋タイトル」でも行われ、同じ作者の同名別セット（d 違い）が1つに潰れる。意図的な挙動だが仕様として明記されていない。 | `deduplicatePublishedEmojiSets` |

## 3. 目標構成

```
emoji/
  EmojiShortcode.kt        shortcode 正規表現・正規化（唯一の定義）
  EmojiTagCodec.kt         emoji タグの解析・生成（4要素目対応）、本文からのタグ解決
  EmojiSetAddress.kt       value class（kind 30030 固定、pubkey 64桁、d）。parse / toTagValue
  EmojiModels.kt           CustomEmoji / RegisteredEmojiSet / RecentReaction
  EmojiPreferences.kt      不変状態 + 派生値（available, byShortcode）
  EmojiPreferencesReducer.kt  純粋な更新関数
  EmojiPreferencesStorage.kt  保存形式 v2 と旧形式からの移行
  CustomEmojiRepository.kt    AccountSession 所有。StateFlow<EmojiPreferences>、直列化された更新と保存
  EmojiPreferenceSync.kt      10030 の取得・送信（旧 EmojiPreferenceSynchronizer）
  EmojiSetDiscovery.kt        30030 の公開一覧と address 指定取得（共有、1購読）
ui/settings/emoji/          設定画面を 一覧 / 詳細 / 検索ロジック に分割
```

### 3.1 モデル

```kotlin
data class CustomEmoji(val shortcode: String, val imageUrl: String, val setAddress: EmojiSetAddress? = null)
// 同一性は (shortcode, imageUrl)。setAddress は出所情報で、等価比較に含めない（key を別に持つ）。

data class RegisteredEmojiSet(
    val address: EmojiSetAddress?,   // null は旧「手動登録」だけ
    val title: String,
    val emojis: List<CustomEmoji>,
)

sealed interface RecentReaction { data class Unicode(val value: String); data class Custom(val emoji: CustomEmoji) }

data class EmojiPreferences(
    val sets: List<RegisteredEmojiSet>,
    val favorites: List<CustomEmoji>,
    val unresolvedSetAddresses: Set<EmojiSetAddress>,
    val recent: List<RecentReaction>,
    val revision: Long,               // 端末で変更するたびに +1。同期の競合判定に使う
) {
    val available: List<CustomEmoji>  // (shortcode, url) で重複排除。保存しない
    val byShortcode: Map<String, List<CustomEmoji>>
}
```

- shortcode 衝突時の既定解決（`resolve(shortcode)`）は「お気に入り → セット登録順の先頭」とし、関数1つで決める。
  投稿時は `uniqueDraftEmoji` の連番化を維持し、ピッカーには衝突する絵文字を両方出す。
- `ReactionOption.Custom` は `CustomEmoji` を持つ形にし、`eventTags` で4要素目を出力する（F6）。

### 3.2 更新関数（純粋）

| 関数 | 仕様 |
| --- | --- |
| `registerSet(set)` | address が同じものを置き換え。address なしは不可 |
| `unregisterSet(address)` | **address 一致だけ** を削除。内容による他セットからの削除はしない（R2） |
| `toggleFavorite(emoji)` | (shortcode, url) で判定 |
| `recordUse(reaction)` | 先頭へ移動、最大24 |
| `applyRemote(parsed, resolvedSets)` | 6章 F3 の競合規則に従う |
| `isRegistered(address)` | 登録済み判定は address で行う（内容一致による判定をやめる） |

### 3.3 リポジトリとライフサイクル

- `CustomEmojiRepository` を `AccountSession` のリソースにし、`CustomEmojiStore` シングルトンを廃止する。
  画面は `LocalAccountSession`（既存の所有モデルに合わせる。`account-session-viewmodel-architecture.md`）経由で受け取る。
- 更新は `Mutex` 内で `reducer → StateFlow → 保存` を1回で行う。保存は単一キー（`emoji_preferences_v2_<pubkey>`）に JSON 1本で書き、
  書き込みは1本の直列キュー（`Channel` + 1コルーチン、最新値だけ書く conflate）で順序を保証する（R4）。
- セッション終了時は状態を破棄する。未ログイン時は空の状態を返す（R11）。
- 旧キー（グローバル・アカウント別の5キー）は初回読み込み時に v2 へ変換して削除する。`manual` リストは F1 の方針で変換する。

### 3.4 同期

- 送信は `revision` 付きスナップショットから組み立て、**端末状態へは書き戻さない**。成功時は `lastSyncedRevision` と `latestEvent` だけ更新する（R3）。
- 取得時の競合規則: `revision > lastSyncedRevision`（未送信の変更あり）なら端末側を採用して送信を予約、そうでなければリモートを採用（R13 の上書き防止）。
- 取得は起動時に加え、アプリの前面復帰時に行う（常時購読はしない。決定事項 D3）。
- 「購読→EOSEまたはタイムアウト→close」の一時取得を小さな共通関数にし、Synchronizer に2回ある手書きの collect/subscribe/close をなくす。

### 3.5 公開セットの取得

- `EmojiSetDiscovery` をセッションで1つ持ち、公開一覧の購読をピッカーと設定画面で共有する。購読IDは `sessionId` を含める（R9、R10）。
- `fetch(address)` を用意し、タップ遷移で address が分かる場合は直接そのセットを開く（R8）。
- 「作者＋タイトル」での重複排除は維持し、この文書を仕様とする（R20）。

### 3.6 表示とナビゲーション

- `LinkedText` は `customEmojis: Map<String, String>` を引数だけから受け取り、リポジトリを購読しない（R6、R18）。
  入力中のプレビューなど端末の絵文字を使いたい画面は、呼び出し側で明示的にマップを渡す。
- 未登録 `:xxx:` のリンク化は、前後が英数字でない場合に限り、かつ `enableCustomEmojiLinks` のときだけ行う（R7）。
- 設定画面への遷移は `CustomEmojiNavigator`（`CompositionLocal` で注入するコールバック）にし、`CustomEmojiRoute` に `setAddress` を追加する（R17）。

## 4. 段階（構造の整理、挙動は変えない）

| 段階 | 内容 | 完了条件 |
| --- | --- | --- |
| S0 | 現行挙動の固定テスト: `customEmojiTagsForContent`、`toCustomReaction`、`parseEmojiPreferenceTags`、`resolveDraftEmojis`、`uniqueDraftEmoji`、`CustomEmojiStore` の add/remove/toggle（テスト用に保存先を差し替え可能にする） | 既存挙動のテストが緑 |
| S1 | `EmojiShortcode` / `EmojiTagCodec` を作り、解析5か所・正規表現3か所を置き換える（4要素目は読むだけで捨てる） | 重複定義ゼロ |
| S2 | `EmojiSetAddress` を導入し、`CustomEmojiList.id` の3形式を読み込み時に変換。`toEmojiSetReference` / `emojiSetAuthorPubkey` を削除 | 文字列 id の推測コードゼロ |
| S3 | `EmojiPreferences` + reducer を作り、`CustomEmojiStore` の内部をそれに置き換える（公開APIは維持）。未使用APIを削除（R16） | reducer のテスト |
| S4 | 保存形式 v2 と移行、直列保存キュー | 旧形式からの移行テスト |
| S5 | `CustomEmojiRepository` を `AccountSession` 所有に移し、`CustomEmojiStore` を廃止。呼び出し側（約20ファイル）を差し替え | `CustomEmojiStore` 参照ゼロ |
| S6 | `EmojiSetDiscovery` の導入、`CustomEmojiSettingsViewModel` から通信を移す | 購読の共有 |
| S7 | 設定画面の分割、`CustomEmojiNavigator` 導入 | 各ファイル 400行程度 |

## 5. テスト方針

- すべて commonTest。reducer・codec・storage 移行・競合規則は純粋関数として直接テストする。
- 同期は `publish` / `fetch` / `sign` を関数引数で差し替えられる形にし（`StatusPublisher` と同じ方式）、以下を固定する:
  署名待ち中の端末変更が失われない、送信失敗時に送信箱へ残る、未送信変更があるときリモートで上書きしない、未解決参照を落とさない。

## 6. 修正（挙動の変更）

S の各段階の後、独立したコミットで行う。

| 修正 | 内容 | 前提 |
| --- | --- | --- |
| F1 | `manual` リストの扱い: v2 移行時に **お気に入りへ統合** し、手動リストという概念をなくす（既に同期でそうなっているため、端末表示を実態に合わせる） | S4 |
| F2 | 解除は address 一致のみ、登録済み判定も address（R2） | S3 |
| F3 | 送信時の書き戻し廃止と `revision` による競合規則（R3、R13） | S5 |
| F4 | ログアウト時の状態破棄（R11） | S5 |
| F5 | 購読IDの衝突解消と公開一覧の共有（R9、R10） | S6 |
| F6 | 送信タグに4要素目（セットアドレス）を付与。リアクション・本文・ステータス・プロフィール | S2 |
| F7 | タップ遷移で4要素目があれば `fetch(address)` で直接開く（R8） | F6、S6 |
| F8 | shortcode 衝突時にピッカーで両方表示（R5） | S3 |
| F9 | 本文表示をイベントのタグだけにする（R6、決定事項 D1）。`app-requirements.md` の該当記述は更新済み | S1 |
| F10 | 未登録コードのリンク化条件を絞る（R7） | — |
| F11 | チャンネルの入力欄に絵文字ピッカーを付け、送信時に `emoji` タグを付ける（R12、決定事項 D2） | S5 |

## 7. 範囲外

- 絵文字セット（kind 30030）の作成・編集・公開。
- リアクション集計（`EngagementReducer` 等）の構造。`toCustomReaction` の呼び出し元は S1 の codec 置き換えだけ行う。
- 標準絵文字カタログ（`StandardEmojiCatalog.kt`）。

## 8. 決定事項と未確定事項

### 8.1 決定事項

- D1（2026-09-28、旧 U1）: 閲覧者の登録絵文字で他人の本文を画像化する挙動（R6）はやめる。本文・プロフィール・ステータス・リアクションは、そのイベントの `emoji` タグだけで画像化する。
  入力中のプレビューなど自分の下書きを表示する画面だけは、下書きが保持する絵文字（`PostState.customEmojis`）を呼び出し側から明示的に渡す。
  `NoteCard` の折りたたみ文字数計算（`countTextWithCustomEmojis`）も、イベントのタグにある shortcode だけを1文字として数える。
- D2（2026-09-28、旧 U2）: チャンネル（kind 42）の入力欄に、投稿画面と同じ絵文字ピッカー（`StandardEmojiPickerSheet`）を付ける。
  下書きは投稿画面と同じく絵文字の一覧を保持し（`resolveDraftEmojis` / `uniqueDraftEmoji` を共用）、`sendMessage` では
  `customEmojiTagsForContent`（S1 以降は `EmojiTagCodec`）で本文中のコードに `emoji` タグを付ける。手入力した登録済みコードにも付与する。
  絵文字の挿入処理は `ComposerBodyEditor.insertEmoji` から共通関数へ切り出し、投稿画面とチャンネルで使う。

- D3（2026-09-28、旧 U3）: kind 10030 は常時購読しない。起動時とアプリの前面復帰時に取り直す。
  前面復帰時の取得は前回取得から一定時間（目安 60秒）以内なら省略する。取得結果の反映は 3.4 の競合規則（`revision`）に従う。
- D4（2026-09-28、旧 U4）: 最近使ったリアクションは端末内だけに保存し、同期しない。

### 8.2 未確定事項

なし。

## 9. 実装で設計から変えた点

### 9.1 段階のまとめ方

| コミット | 含む段階 |
| --- | --- |
| 現行挙動の固定テスト | S0 |
| 絵文字タグの解析とセットアドレスを1か所にまとめる | S1、S2 |
| 設定を不変データと純粋な更新関数にする | S3、S4、F1（旧手動登録リストをお気に入りへ統合）、F8（同名の絵文字を両方使える） |
| アカウントセッションが所有する構成へ移す | S5、S6、S7、F2（登録判定・解除をアドレスで行う）、F3（送信時の書き戻し廃止と競合規則）、F4（ログアウト時の状態破棄）、F5（購読の共有） |
| セットアドレスを読み書きする | F6、F7 |
| 本文の絵文字はイベントのタグだけで画像にする | F9、F10 |
| チャンネルの入力欄に絵文字ピッカーを付ける | F11 |

F2〜F5・F8 は、新しい構造（アドレスを持つ `RegisteredEmojiSet`、セッション所有のリポジトリ、送信処理の書き直し）に移した時点で旧挙動が再現できなくなるため、構造整理と同じコミットに含めた。

### 9.2 構成

- `CustomEmojiRepository` は `Mutex` ではなく、変更を `Channel` で受けて1本のコルーチンが順に適用する。読み込み前に受けた変更もこの順番待ちに入るので、起動直後の操作が読み込みで消えない。UI への反映は数ミリ秒遅れる。
- 保存は `StateFlow` に最新状態を置き、1本のコルーチンが順に書く（conflate）。セッション終了の直前に受けた変更は、保存前にスコープが止まると失われることがある（旧実装のグローバルスコープでの保存と違う点）。
- `EmojiSetDiscovery` はセッション所有ではなく、アプリ全体で1つのオブジェクトにした。公開セットはアカウントに依存せず、ログイン前でも設定画面・絵文字タップの遷移で使うため。一覧の購読は同時に1本で、最初の EOSE（またはタイムアウト）で読み込み中を解除し、遅いリレーの結果も20秒は受け取る。前回の取得から5分以内は取り直さない（再読み込みボタンは常に取り直す）。
- 設定画面は `CustomEmojiSettingsScreen`（一覧）、`CustomEmojiSetDetailScreen`、`CustomEmojiSetRows`、`CustomEmojiSettingsLogic`（純粋処理）に分けた。パッケージは `ui/settings` のまま。
- `CustomEmojiNavigator` は関数型の `LocalCustomEmojiNavigator`（`compositionLocalOf`）にした。`CustomEmojiRoute` に `setAddress` を追加した。
- 一時的な取得（kind 10030、参照先の kind 30030、アドレス指定の kind 30030）は `fetchEventsUntilEose` にまとめ、購読要求より先に受信と EOSE の待ち受けを始めるようにした。
- 前面復帰の検知は `AccountSessionHost` でライフサイクルが STARTED になったときに行う。

### 9.3 挙動

- `CustomEmoji` には `setAddress` を持たせなかった。等価比較や下書き（kind 31234）の保存形式に影響するため。送信タグの4要素目は、送信時点で登録済みのセットから `EmojiPreferences.setAddressOf` で引く。アドレスは `ReactionOption.Custom` と `CustomReaction` にだけ持たせ、`key` には含めない。
- ピッカーの検索で公開セット（未登録）から選んだ絵文字は、リアクションではセットのアドレスを付けるが、本文の下書きに挿入した場合は付かない（下書きは `CustomEmoji` だけを保持するため）。
- 本文中の絵文字（`LinkedText`）のタップでは4要素目を使わず、shortcode と画像の一致で所属セットを探す。表示用の `customEmojis` が shortcode → URL のマップのため。
- `customEmojiTagsForContent` は、同じ shortcode が複数あれば先に並ぶものを使う（旧実装は後勝ち）。呼び出し側は「下書きで選んだもの → お気に入り → 登録順のセット」の優先順で渡す。
- 画像の分からないコードの検索リンクは、前後が半角英数字のときだけ除外する。日本語は区切りなしで `こんにちは:wave:` と書くことが多いため、全角文字の隣は候補に残す。
- 画像の分からないコード（URL なし）をタップしたときは、shortcode だけで一致するセットがあっても自動では開かず、検索語として一覧を出す（旧実装と同じ）。
- ログインしていないときは設定画面を閲覧専用にした（登録・お気に入りボタンを無効化）。旧実装ではアカウントに属さないグローバルな保存先へ登録できたが、同期もされず、次にログインしたアカウントへ移されるだけだった。
- チャンネルの入力欄（`AppMessageComposer`）は、絵文字の挿入のためにカーソル位置を持つ `TextFieldValue` を内部で保持する。外から本文が変わったとき（送信後のクリアなど）だけ追従する。スレッドの返信欄も同じ部品だが、絵文字ボタンは出していない（返信の `emoji` タグ付与は本設計の範囲外）。

### 9.4 自動検証

次のコマンドが成功した（iOS シミュレータでの common test を含む）。

```text
./gradlew :composeApp:allTests \
  :composeApp:compileAndroidMain \
  :composeApp:compileKotlinIosSimulatorArm64
```

追加・更新したテスト: `EmojiTagCodecTest`、`EmojiShortcodeTest`、`EmojiSetAddressTest`、`EmojiPreferencesReducerTest`、`EmojiPreferencesStorageTest`、`EmojiPreferenceSyncTest`、`EmojiSetDiscoveryTest`、`CustomReactionTest`、`CustomEmojiSelectionTest`、`EmojiPickerSheetTest`、`DraftCustomEmojisTest`。

実機・シミュレータでの画面操作（登録・解除、絵文字タップの遷移、チャンネルでの絵文字入力、他クライアントとの kind 10030 同期）は未確認。
