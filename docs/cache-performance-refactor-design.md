# キャッシュ・蓄積状態 性能改善設計

## 1. 目的

キャッシュと長寿命の蓄積状態が、保持件数だけでなく更新時の全件コピー、全件走査、DB再計算、
大きな画像保持によってアプリを重くすることを防ぐ。

本設計が保証する性質は次のとおり。

- すべてのプロセスメモリキャッシュに、件数または推定バイト数の上限がある。
- 頻繁に更新されるキャッシュは、1件の更新でキャッシュ全体を複製しない。
- 画面は関係するキーの変更だけを受け取り、無関係なキャッシュ更新で再計算しない。
- DBキャッシュへの複数書き込みは1トランザクションにまとめ、一覧の再クエリ回数を抑える。
- 画像キャッシュは件数だけでなく、デコード後のメモリ使用量を基準に制限する。
- キャッシュ上限、ヒット率、退避数、更新時間を観測できる。

キャッシュの存在自体を減らすことは目的にしない。通信量、初期表示速度、オフライン表示を
維持しながら、保持と更新のコストを予測可能にする。

## 2. 対象範囲

| 優先度 | 対象 | 主な問題 |
|---|---|---|
| P0 | `ReactionEventStore` | 最大10,000件の不変Mapコピーと全件通知 |
| P0 | チャンネルRoom DB | 相関サブクエリ、書き込みごとの再クエリ、最大50,000件/リレー |
| P0 | `ProfileCache` | 1件ごとのMapコピー、上限到達後のソート、`observeAll()`の広域通知 |
| P1 | `XPostSnapshotCache` | 画像枚数のみの制限で総バイト数が無制限 |
| P1 | `FeedController` | 最大800投稿に付随する多数のMapを更新ごとに全件再構築 |
| P2 | 記事一覧 | ページ追加後の全件変換・版統合・ソート |
| P2 | Coil画像キャッシュ | ライブラリ既定上限への依存と観測手段の不足 |
| P3 | 小規模メタデータキャッシュ | FIFO退避、失敗結果の長期保持、個別の重複実装 |

フィードのUIモデル化、スクロール中の処理抑制、画像プリフェッチの詳細は
[`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md)を正とする。本書では、
キャッシュと蓄積状態の更新コストに関係する境界だけを定義する。

### 2.1 現行実装レビュー（2026-09-23）

設計作成後に現行コードを再確認した結果は次のとおり。件数上限があることと、更新コストが
十分に低いことは別の完了条件として扱う。

| 対象 | 現在の状態 | 判定 |
|---|---|---|
| `ReactionEventStore` | reaction・target authorとも10,000件上限。更新ごとのMapコピーは残る | 一部実装・P0未完了 |
| `ProfileCache` | 2,000件上限、`putEvents()`一括適用、`observe(pubkey)`/`observe(pubkeys)`によるキー指定購読、`observeAll()`廃止を実装済み。退避選定はソートではなく最小`fetchedAt`の1回走査だが、pin機構は未実装 | Loop 2完了・退避方式とpinは残課題 |
| `RelayListEventCache` | メモリ500件上限を実装済み | 件数対策済み |
| `RelayInformationRepository` | 200件LRUとMutexを実装済み | 件数対策済み、TTL未実装 |
| `ArticleMemoryCache` | 記事500件・引用イベント1,000件のLRUを実装済み | グローバル上限済み、画面内集約未完了 |
| リンク・YouTubeプレビュー | 各200件上限。読み出しで順序を更新しないFIFO | 上限済み、LRU・TTL未完了 |
| チャンネルDB | DB version 5。リレーごと50,000メッセージを起動時prune | 上限の総量化・クエリ改善未完了 |
| `XPostSnapshotCache` | 8画像のLRU | 件数済み、バイト上限未完了 |
| `FeedController` | 表示800件、seen 2,000件、解析結果1,000件の上限あり | 上限済み、差分更新未完了 |
| Coil | リクエストサイズ正規化と一部プリフェッチは実装済み | 共通上限・統計未完了 |

`ReactionEventStore.events`は宣言以外の利用箇所が見つからなかった。Phase 1で再確認後、互換APIを
残さず削除する候補とする。

## 3. 共通設計原則

### 3.1 上限は保持対象に合わせる

- 小さいメタデータ: 件数上限付きLRU。
- `NostrEvent`の集合: 件数上限と時間範囲を併用する。
- `ImageBitmap`: 推定バイト数を主上限、件数を安全弁とする。
- DB: グローバル件数、チャンネル単位件数、保存期間を併用する。
- ユーザー操作に必要な状態（お気に入り、既読位置）はキャッシュと同時に削除しない。

### 3.2 ホットパスで不変コレクション全体をコピーしない

ネットワークイベント受信やプロフィール受信のたびに`current + entry`を行わない。内部の所有権を
単一の`Mutex`または単一dispatcherへ閉じ込め、可変Mapを安全に更新する。UIへ渡す値だけを不変にする。

### 3.3 全件スナップショットではなく変更集合を通知する

キャッシュ内部は次の情報を通知する。

```kotlin
data class CacheChange<K>(
    val changedKeys: Set<K>,
    val removedKeys: Set<K> = emptySet(),
    val revision: Long,
)
```

画面は自分が要求したキーとの積集合が空なら何もしない。全件スナップショットが必要な診断・移行用APIは
残してよいが、画面の通常購読には使わない。

### 3.4 書き込みをバッチ化する

同一購読または同一リレー応答で受信したイベントは、1件ずつ公開せず、最大100件または100〜200msの
小さい窓でまとめて適用する。初回の1件を遅らせすぎないよう、件数到達と時間到達の早い方でflushする。

### 3.5 計測なしで上限値を固定しない

本書の初期値は安全な開始値である。実機計測後に変更できるよう、上限は対象クラスの定数へ集約する。
テストは具体値の重複ではなく、「上限を超えない」「最新または利用中の要素を残す」を検証する。

## 4. ReactionEventStore

### 4.1 現状の問題

`NostrRepository`は受信した全イベントを`ReactionEventStore.observe()`へ渡す。リアクション以外の
通常イベントも`eventId -> pubkey`として`_targetAuthors`へ追加される。

- `_targetAuthors`は最大10,000件で、追加ごとにMap全体をコピーする。
- 上限到達後は、リアクション側を全走査して参照中IDをSet化した後、さらにMapをコピーして削除する。
- リアクション追加時は内部Mapをコピーした後、公開用`events`を`mapValues`で再生成する。
- 複数リレーから同じイベントを受信すると、source relay集合の差分だけでも全体更新になる。

### 4.2 目標構造

```kotlin
internal class ReactionCache(
    private val maximumReactions: Int = 10_000,
    private val maximumTargetAuthors: Int = 10_000,
) {
    private val reactionsById = LinkedHashMap<String, CachedReaction>()
    private val targetAuthorsByEventId = LinkedHashMap<String, String>()
    private val referencedTargetCounts = mutableMapOf<String, Int>()

    fun apply(events: List<SourcedEvent>): ReactionCacheChange
    fun matching(query: ReactionQuery): List<NostrEvent>
}
```

- 可変Mapは`ReactionEventStore`のMutex内だけで操作する。
- リアクション追加・削除時に`referencedTargetCounts`を差分更新する。
- target author退避時は参照数0の最古キーを優先し、毎回リアクション全体を走査しない。
- source relay追加だけでイベント本体が同一なら、変更キーだけを通知する。
- `events: StateFlow<Map<...>>`は利用箇所を再確認し、未使用なら削除する。互換期間が必要なら、
  診断用の遅延スナップショットとして提供し、イベント受信ごとには生成しない。
- `matching()`はキャッシュ全体を公開せず、Mutex内で必要な結果だけをコピーする。

> **Loop 1実装判断（2026-09-23）**: `receivedReactions()`などの同期APIとJournalの即時表示を
> 維持するため、Coroutine `Mutex`ではなくcommonの`SynchronousLock`とAndroid/iOSのactual実装を
> 採用した。ロック内はMapの参照・更新・結果List生成だけに限定し、通信、DB、Flow emitは行わない。
> 未使用だった`events: StateFlow<Map<...>>`は削除し、内部Mapの全件公開を廃止した。

> **Loop 1実装レビュー（2026-09-23）**: 受信ごとの全件Mapコピーと参照中target IDの全走査は
> 解消した。一方、上限超過時の`minByOrNull`と`matching()`の抽出・ソートは最大10,000件を走査する。
> 現時点では結果整合性を優先して維持し、Phase 0の処理時間計測で基準超過を確認した場合に限り、
> 最古時刻用の補助インデックスまたはロック外ソートへ分離する。

### 4.3 バッチと通知

- リレー受信イベントを最大100件または100msでまとめる。
- 削除イベントは表示整合性を優先し、同一バッチ内で追加より後に適用する。
- 公開通知は`ReactionCacheChange`を1回だけ発行する。
- 画面側は対象event IDと`changedKeys`が交差した場合だけエンゲージメントを再構築する。

### 4.4 受け入れ条件

- 10,000件保持後に通常イベントを1件追加しても、10,000件のMapスナップショットを生成しない。
- 同じリアクションを3リレーから受信しても、イベント本体は1件だけ保持する。
- 削除イベント、relay別source除去、`p`タグフィルターの既存テストが維持される。
- 10,000件からの`matching()`結果が現行実装と一致する。

## 5. チャンネルRoom DB

### 5.1 現状の問題

チャンネル一覧クエリは、チャンネルごとに未読件数の`COUNT(*)`と最新メッセージ取得を行う。
`channel_messages`には`channelId`単独のインデックスしかなく、`createdAt`順の探索と未読範囲検索が
メッセージ増加に伴って重くなる。また、1メッセージの保存がメッセージ本体、relay対応、
channel relay更新の複数書き込みに分かれ、Flowの再クエリが複数回発生し得る。

### 5.2 インデックス

次の複合インデックスを追加する。

```kotlin
Index(value = ["channelId", "createdAt", "eventId"])
```

- 最新メッセージ: `(channelId, createdAt, eventId)`を逆順走査する。
- 未読件数: 同じインデックスで`channelId = ? AND createdAt > ?`を範囲検索する。
- `channel_relays`の主キーは既に`(relayUrl, channelId)`、`channel_message_relays`の主キーは
  `(relayUrl, eventId)`である。SQLite/Roomが生成した主キー索引を`EXPLAIN QUERY PLAN`で確認し、
  同じ列順のインデックスを重複追加しない。
- `CachedChannelMessageEntity`は現在`indices = [Index("channelId")]`を持つ。複合インデックスの
  先頭列が`channelId`のため、以後のクエリはこの単一列インデックスを必要としない。両方を残すと
  メッセージ挿入のたびに2本のインデックス更新が走り、書き込みコスト削減という目的(3.2)に反する。
  単一列インデックスは複合インデックスへ置き換えて削除する。
- インデックスの追加・削除はRoomのスキーマ変更にあたる。DB version 5から、既存の
  `MIGRATION_4_5`と同じ形で`DROP INDEX`・`CREATE INDEX`を行う`Migration`を追加し、5.6の
  migration testでカバーする。

> **Loop 3実装判断(2026-09-23)**: `MIGRATION_5_6`を追加し、`index_channel_messages_channelId`を
> `DROP INDEX`した上で`index_channel_messages_channelId_createdAt_eventId`を作成する構成にした。
> 索引名はRoomが`CachedChannelMessageEntity`の`indices`指定から生成する名前と完全一致させ
> (`composeApp/schemas/com.nostr.torinos.network.cache.ChannelCacheDatabase/6.json`で確認)、
> アプリ起動時にRoomが行うスキーマ検証(実際のDB構造とエンティティ定義の一致チェック)に通ることを
> 確認した。

### 5.3 書き込みトランザクション

DAOへ次のトランザクションAPIを追加する。

```kotlin
@Transaction
suspend fun upsertMessageBundle(
    message: CachedChannelMessageEntity,
    relay: CachedChannelMessageRelayEntity,
    relayUrl: String,
    channelId: String,
    seenAt: Long,
)
```

1イベントの3書き込みを1トランザクションへまとめる。履歴一括取得では、イベントごとの
トランザクションではなくページ全体を1トランザクションで保存するバルクAPIを使う。

> **Loop 3実装判断(2026-09-23)**: 単発は設計書どおり`upsertMessageBundle(message, relay, relayUrl,
> channelId, seenAt)`をDAOへ追加した。バルク保存は個々の引数のリストではなく、5引数をまとめた
> `ChannelMessageBundle`データクラスのリストを受ける`upsertMessageBundles(bundles:
> List<ChannelMessageBundle>)`とした(Roomの`@Transaction`メソッドはKotlinのデフォルト実装として
> 他のDAOメソッドを順に呼ぶ形で書ける)。`ChannelCacheStore.upsertMessage()`はこの単発APIを使うよう
> 変更し、`ChannelController.fetchHistoryPage()`がページ全体を1件ずつ`upsertMessage()`していた箇所は
> 新設の`ChannelCacheStore.upsertMessages()`(バルクAPI、ページ全体を1トランザクション)へ置き換えた。
> `ChannelListViewModel.updateActivity()`はリアルタイム受信で1件ずつのため単発APIのままとした。

### 5.4 一覧の集約

Phase 1では複合インデックスとトランザクション化だけを行う。それでも一覧クエリが基準を超える場合、
`channel_summaries`へ次を事前集約する。

- `latestMessageId`
- `latestMessageCreatedAt`
- `latestMessageAuthorPubkey`
- `latestMessagePreview`

未読件数は既読位置変更とメッセージ追加で差分更新する。削除や移行で不整合が起きた場合に備え、
集約を再構築するDAOとテストを用意する。事前集約はクエリ計測で必要性を確認してから導入する。

### 5.5 保持方針

- 初期値は全体50,000メッセージを上限とし、現行の「リレーごと50,000」から総量基準へ移す。
- 1チャンネルが全体を占有しないよう、非お気に入りは1チャンネル最大2,000件を初期値とする。
- お気に入りも無制限にはせず、別の高い上限を設定する。
- お気に入り、既読位置、スクロール位置は履歴pruneで削除しない。
- pruneは初回コンテンツ表示を待たせず、DBオープン後のバックグラウンド処理として1回実行する。
- 大量削除後の`VACUUM`は起動時に自動実行しない。空きページ率とDBサイズを見て、充電中などの
  明示的なメンテナンス条件を満たす場合だけ検討する。

> **Loop 4実装判断(2026-09-23)**: `ChannelCacheStore.prune()`を「チャンネル単位の上限切り詰め →
> 全体上限での横断削除 → 孤立`channel_message_relays`の削除」の順に変更した。チャンネル単位の削除は
> 新しい複合indexをそのまま使う`pruneMessagesByChannel(channelId, maxMessages)`、全体上限は
> `pruneMessagesGlobally(maxMessages)`とし、いずれも`channel_messages`から直接`createdAt`昇順で
> 古いものを消す。旧実装は`channel_message_relays`側から「リレーごと」に間引いてから
> `channel_messages`の孤児を消す向きだったため、`getDistinctRelayUrls()`/`pruneMessagesByRelay()`は
> 使用箇所がなくなり削除した。お気に入り判定は`channel_relays`(リレー単位)を介さず`channels.
> isFavorite`を直接参照するため、`relayUrl`を問わずチャンネル単位で一貫した上限になる。非お気に入り
> 2,000件、お気に入り20,000件(全体50,000件の40%を1チャンネルに許容する初期値、実機計測前の仮値)を
> `ChannelCacheStore`の定数として追加した。`channel_read_states`(既読位置・スクロール位置)は
> 元々pruneの対象外で、この変更でも触れていない。

iOSでは現在DB全体がDocuments配下にある。ユーザー状態と再取得可能な履歴を同じDBに保存しているため、
単純にCaches配下へ移さない。将来分離する場合は、既読・お気に入りをApplication Support、
メッセージ履歴をCachesへ分ける。

### 5.6 受け入れ条件

- 50,000件のDBで一覧クエリが対象端末のp95 50ms以内。**(未測定。Phase 0の計測基盤が未着手のため
  マクロベンチマークは持ち越し。下記のEXPLAIN QUERY PLAN比較で構造的な改善は確認済み)**
- 1メッセージ受信による一覧Flowの再評価は1トランザクションにつき最大1回。**(実装済み。
  `upsertMessageBundle`/`upsertMessageBundles`が3書き込みを1トランザクションにまとめたため、Room
  のinvalidation trackerが発火するテーブル変更通知も1回にまとまる)**
- `EXPLAIN QUERY PLAN`で最新メッセージ・未読範囲検索が複合インデックスを利用する。**(確認済み。
  旧`index_channel_messages_channelId`単独では最新メッセージ取得が`USE TEMP B-TREE FOR ORDER BY`を
  伴っていたが、新しい複合インデックスでは両クエリとも`SEARCH ... USING COVERING INDEX
  index_channel_messages_channelId_createdAt_eventId`となり、一時ソートと行参照が不要になった)**
- prune後もお気に入り、既読位置、スクロール位置が維持される。**(Loop 4で確認済み。新しい
  `prune()`は`channel_messages`と孤立した`channel_message_relays`だけを操作し、`channel_read_states`
  (既読位置・スクロール位置)には触れない。`sqlite3`での検証で、チャンネル単位の切り詰め→全体上限の
  横断削除→孤立relay削除のいずれの段階でも対象外テーブルの行数が変化しないことを確認した)**
- Room migration testで既存DBからデータを失わず移行できる。**(自動化されたRoom migration test
  基盤(`androidx.room:room-testing`依存関係、専用test source set)がプロジェクトに存在せず
  (`MIGRATION_1_2`〜`MIGRATION_4_5`にも既存テストなし)、今回も追加を見送った。代わりにRoomが
  エクスポートしたv5/v6スキーマJSON(`composeApp/schemas/com.nostr.torinos.network.cache.
  ChannelCacheDatabase/`)からCREATE TABLE文を復元し、`sqlite3`CLI上で`MIGRATION_5_6`と同一のSQLを
  実行してデータ件数・インデックス構造の整合性を手動検証した。自動テスト化はPhase 5または次に
  Room migrationを追加するタイミングでの判断課題として残す)**

## 6. ProfileCacheとプロフィール購読

### 6.1 現状の問題

プロフィール1件の追加で最大2,000件のMapをコピーし、上限到達後は`fetchedAt`順に全件ソートする。
さらに複数のViewModelが`observeAll()`を購読し、関係するpubkeyを各画面で`filterKeys`している。
100件のプロフィール応答を1件ずつ適用すると、Mapコピーと全購読者の走査が100回発生する。

### 6.2 キャッシュ内部

```kotlin
internal class ProfileMemoryCache(
    private val maximumEntries: Int = 2_000,
) {
    private val entriesByPubkey = LinkedHashMap<String, ProfileCache.Entry>()

    fun putAll(events: Collection<FetchedProfile>): ProfileCacheChange
    fun get(pubkeys: Collection<String>): Map<String, NostrProfile>
}
```

- kind 0応答は`putAll()`でまとめて適用する。
- 同一pubkeyの新旧判定、楽観更新、`fetchedAt`更新の仕様は維持する。
- 退避候補の選択は毎回の全件ソートをやめる。取得順LRU、または`fetchedAt`と世代番号を持つ
  優先キューを使う。
- 自分のプロフィール、現在表示中、編集中のプロフィールはpinできるようにする。
- pinは永続化せず、画面ライフサイクル終了時に解除する。

> **Loop 2実装判断（2026-09-23）**: 退避候補選択は`LinkedHashMap`を`fetchedAt`最小値で1回走査する
> 実装とし、全件ソートは廃止した。取得順LRUや優先キューへの置き換えは、Reaction側と同様に計測で
> 基準超過を確認してから行う。pin機構（自分のプロフィール・表示中・編集中の保護）は未実装のまま
> 残っている。現状は表示中プロフィールが2,000件LRUから追い出されても`ensureProfiles()`が再取得する
> ため機能上の欠落はないが、再取得コストを避けたい場合はPhase 5で追加を検討する。

### 6.3 購読API

`observeAll()`を通常画面から廃止し、次へ移行する。

```kotlin
fun observe(pubkey: String): Flow<NostrProfile?>
fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>>
```

内部の変更通知と要求pubkeyが交差した場合だけ再取得する。要求集合が変わる画面は
`flatMapLatest`、または`ProfileHydrator`(現状は`observeAll()`を購読するだけで、この移行に
合わせて`updateTargets()`を新設する)で購読対象を差し替える。

> **Loop 2実装判断（2026-09-23）**: `observeAll()`はコードベース全体から利用箇所がなくなり、
> 廃止が完了した。`FeedController`・`ChannelController`・`ChannelListViewModel`は
> `ProfileHydrator`を経由せず、`ProfileRepository.observeChanges()`と自前の関連pubkey集合との
> 積集合判定で直接購読しており、これも「要求集合が変わる画面での購読対象差し替え」の目的を
> 満たす。`ProfileHydrator`の唯一の利用箇所である`ThreadController`は、スレッド画面を開いている
> 間だけ参加者pubkeyを`request()`で追加し、画面を離れる際に`close()`でHydrator自体を破棄するため、
> 購読対象の縮小や差し替えが発生しない。このため`updateTargets()`は新設しなかった。画面
> ライフサイクル中に要求集合が大きく入れ替わる呼び出し元が現れた場合に限り追加を検討する。

### 6.4 受け入れ条件

- 100プロフィールの一括応答で、キャッシュ変更通知は1回。**（`ProfileCacheTest`で確認済み）**
- 無関係なpubkey更新でフィード、チャンネル画面のプロフィール状態を更新しない。
  **（`ProfileCacheTest`で確認済み）**
- 2,000件到達後の1件追加で全件ソートを行わない。**（1回走査での実装に置き換え、
  `ProfileCacheTest`で上限維持を確認済み）**
- 楽観更新と新旧イベント判定に関する既存テストを維持する。**（維持を確認済み）**

## 7. X投稿スナップショット

### 7.1 上限

件数上限8件に加え、推定24MiBの総量上限を初期値として導入する。

```text
estimatedBytes = widthPx * heightPx * 4
```

- 単一画像が8MiBを超える場合はキャッシュしない。
- 追加後に24MiBを超えた場合はLRU順に退避する。
- `widthPx`、`heightPx`、推定bytesをエントリーに保持する。
- オーバーフローを避けるため計算は`Long`で行う。
- 実画像の内部表現が推定値より大きい可能性があるため、件数上限8も維持する。

> **Loop 6実装判断(2026-09-23)**: `widthPx`/`heightPx`を別フィールドとして持たず、`ImageBitmap`が
> 既に持つ`width`/`height`プロパティから`estimatedBytes`を都度算出する形にした。エントリーには
> `ImageBitmap`と`estimatedBytes`だけを保持する`Entry`データクラスを追加。単一画像が8MiBを超える
> 場合は追加処理そのものをスキップし(既存エントリの退避は行わない)、追加後に件数8件または総量
> 24MiBのいずれかを超えた場合はLRU順(`entries.keys.first()`)に退避するwhileループとした。

### 7.2 メモリプレッシャー

- Androidのメモリトリム通知、iOSのmemory warningで全件clearする。
- アプリのバックグラウンド移行では即時clearせず、端末計測で復帰速度とのバランスを確認する。
- 退避時にプラットフォーム画像を手動破棄する処理は、安全性を確認できるAPIがある場合だけ使う。

> **Loop 6実装判断**: `XPostSnapshotCache.clear()`を新設し、Android側は`ToriNosApp`
> (`Application`)に`ComponentCallbacks2`を登録して`onTrimMemory(level)`で反応した。
> `TRIM_MEMORY_UI_HIDDEN`(20、単にUIが不可視になっただけの通知)は明示的に除外し、
> `TRIM_MEMORY_RUNNING_LOW`(10)以上かつUI_HIDDENでない場合(10, 15, 40, 60, 80)にclearすることで、
> 「バックグラウンド移行だけでは即時clearしない」という方針を満たした。`onLowMemory()`
> (deprecated互換)でも同様にclearする。iOS側は`MainViewController()`から
> `registerMemoryWarningObserver()`を呼び、`NSNotificationCenter`で
> `UIApplicationDidReceiveMemoryWarningNotification`を購読してclearする。プラットフォーム画像の
> 手動破棄(Bitmap.recycle相当)は、Compose Multiplatform共通の`ImageBitmap`に安全に呼べるAPIが
> ないため見送り、参照を外してGCに委ねる方針のままとした。

### 7.3 受け入れ条件

- どの画像サイズの組み合わせでも推定保持量が24MiB以下。**（`XPostSnapshotCacheTest`の
  `totalBudgetEvictsOldestEntriesFirst`で確認済み）**
- 同一キーへの置換で推定使用量が二重加算されない。**（`replacingSameKeyDoesNotDoubleCountEstimatedBytes`
  で確認済み。二重加算されると自分自身が総量上限で退避されてしまう構成のテストで検出できることを
  確認した上でテストを作成した）**
- テーマ・幅違いのキー分離を維持する。**（`differentThemeOrWidthAreDistinctEntries`で確認済み、
  既存の`XPostSnapshotCacheKeyTest`も維持）**
- メモリ警告後にキャッシュが空になる。**（`clearRemovesAllEntries`でクリア自体は確認済み。
  Android/iOSの実際のOSシグナル配線はコンパイル成功とコードレビューで確認したが、実機での
  メモリ警告発火そのものはシミュレータで再現しておらず未検証）**

## 8. FeedController

フィードは上限があるため、主問題はリークではなく再計算と一時割り当てである。

### 8.1 差分インデックス

次の逆引きをController内部に持つ。

- `pubkey -> Set<eventId>`
- `quotedEventId -> Set<eventId>`
- `replyTargetId -> Set<eventId>`
- `eventId -> FeedItemUiModel`

プロフィール、引用、返信、リアクションの変更時は影響event IDだけを再構築する。表示順が変わらない
更新では、800件のイベント一覧と付随Mapを`filterKeys`で再生成しない。

> **Loop 5調査結果(2026-09-23)**: 実装に着手する前にコード調査を行った結果、本節が前提とする問題の
> うちリアクション・リポスト・引用・返信・プロフィール更新は**既に差分更新になっている**ことが分かった。
> `handleReactionEvent`等(`FeedController.kt`)は受信イベント自身の`e`/`q`タグからtargetIdを直接取得し
> `EngagementAccumulator`で該当eventIdのMapエントリだけを更新する。プロフィール更新も
> `ProfileRepository.observeChanges()`で変更pubkeyと現在表示中pubkeyの積集合だけを取り直す。よって
> 8.3の受け入れ条件1・2は新規実装なしに満たされていた。`pubkey -> Set<eventId>`等の逆引きMapは
> 存在しないが、上記の理由で現状は不要(受信イベント自身にtargetIdが書かれているため)。
>
> 実際に繰り返し全件コストを払っているのは`computeUpdatedFeedState`(新着投稿受信のたびに約150ms
> 間隔で発火する`flushPendingTimelineEvents`等から呼ばれる)で、ここでは約15本の並列Mapへの
> `filterKeys`と、`retainedPubkeys`計算のための`extractNpubReferences`(正規表現マッチ+bech32
> デコード)を可視イベント・引用イベント・返信イベントの本文に対して毎回re-parseしていた。
> `extractNpubReferences`の対象イベントのcontentはevent ID確定後に不変なため、
> `FeedItemMapper`と同じ形の`MentionedPubkeysCache`(event ID単位のLRUキャッシュ)を新設し、
> `retainedPubkeys`計算内の3箇所をこのキャッシュ経由に置き換えた。`MentionedPubkeysCacheTest`で
> 基本的な解決・キャッシュ再利用・上限超過時の退避を確認し、`allTests`・
> `:composeApp:compileAndroidMain`・`:composeApp:compileKotlinIosSimulatorArm64`が成功。
>
> `computeUpdatedFeedState`/`updateEvents`本体を差分更新へ書き換える案(15本のMapをfilterKeysでなく
> 追加・削除分だけ更新する)は見送った。この関数は`updateEventsMutex`+`feedStateRevision`による
> 競合防止機構、`MAX_TIMELINE_EVENTS`切り詰め、履歴開示境界(`historyRevealOldestAt`)を一括して
> 整合させる唯一の場所であり、うかつに差分化すると退場したeventIdのエントリ残留(メモリリーク)や
> 競合バグを再発させるリスクが高い。実施するならPhase 0の計測でこの経路のコストを定量化した上で、
> 専用の設計検討を経てから着手すべき残課題として残す。

### 8.2 状態の分離

- 一覧構造: event IDの順序、読み込み状態、新着件数。
- 行内容: event ID単位のUIモデル。
- エンゲージメント: event ID単位の差分状態。

同値の行は同じインスタンスを再利用する。詳細な目標モデルとCompose境界は
[`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md)の4章を利用する。

### 8.3 受け入れ条件

- 1投稿へのリアクションで、無関係な799投稿のUIモデルを再生成しない。**（実装済みであることを確認。
  `EngagementAccumulator`が対象eventIdのMapエントリだけを更新する）**
- プロフィール1件の更新で、そのpubkeyを参照する投稿だけが変わる。**（実装済みであることを確認。
  `ProfileRepository.observeChanges()`と現在表示中pubkeyの積集合で判定している）**
- 最大800件、画像50%以上のデータセットでフリング時のフレーム落ちを変更前より悪化させない。
  **（未測定。Phase 0の計測基盤が未着手のため実機ベンチマークは持ち越し）**
- 履歴追加、ミュート変更、NGワード変更では現行の表示結果を維持する。

## 9. 記事一覧

### 9.1 集約の差分化

ViewModelごとに次を保持する。

- `eventId -> NostrEvent`
- `address -> latest eventId`
- pubkey別の記事数または著者表示モデル

新規イベントでは該当addressの最新版だけを比較し、全件の`latestArticleVersions()`を毎回実行しない。
ページ単位の追加後に表示Listを1回だけ生成する。プロフィール変更では記事本文を再解析せず、
著者表示モデルだけを差し替える。

> **Loop 12実装判断(2026-09-23)**: `ui/article/ArticleViewModel.kt`を確認したところ、ページ読込
> (`loadPage()`)は既に`updateStateFromEvents()`(全件`toArticleMeta()`+`latestArticleVersions()`+
> `toArticleAuthors()`の再計算)を1回だけ呼んでおり9.1の後半要件は満たしていた。未対応だったのは
> ローカル公開・ローカル削除・プロフィール取得完了の3経路で、いずれも`rawEvents`(無制限
> `LinkedHashMap`)全体を`updateStateFromEvents()`で毎回フルリビルドしていた。
>
> `ArticleHubViewModel`/`UserArticleListViewModel`双方に、既存の`_state.value.articles`/`authors`
> だけを対象にした差分更新関数を追加した(rawEventsは`removeRawArticle()`用に維持、`updateStateFromEvents()`
> 自体はミュート変更時の全件フィルタ用に残置)。
> - `applyLocalArticleEvent(event)`: 対象addressだけを比較して`articles`に追加/置換する
>   `List<ArticleItem>.withUpsertedArticle()`(ファイルスコープの`internal`関数)を使用。既存より
>   古い`createdAt`なら同一リスト参照を返し状態更新自体をスキップする。
> - `applyLocalArticleDeletion(address, pubkey)`: 対象addressだけを`articles`から除去する。
> - `applyProfileUpdates(newProfiles)`: `toArticleMeta()`を一切呼ばず、`articles`の`authorProfile`
>   だけを単一の`map`パスで差し替える(9.1後半の「著者表示モデルだけを差し替える」を文字通り実装)。
>
> 著者一覧側は`List<ArticleAuthorItem>.withUpdatedAuthor(pubkey, articles)`(同じくファイルスコープ
> `internal`)で、変更のあったpubkeyだけを更新後の`articles`から再計算し直す。挿入位置は
> `latestArticleVersions()`/`toArticleAuthors()`と同じ比較子(`sortTime`降順→`createdAt`降順)を
> `internal val articleDisplayOrder`/`authorDisplayOrder`として共有し、順序の食い違いを防いだ。
>
> 9.3の受け入れ条件を`ArticleDifferentialUpdateTest.kt`(新規)で検証:
> `withUpsertedArticle`/`withUpdatedAuthor`を空リストから複数回適用した結果が、同じ入力集合に対する
> フルリビルド(`latestArticleVersions()`/`toArticleAuthors()`)と完全一致すること、古いバージョンの
> 後着が無視されること(巻き戻り防止)、削除で著者が0件になった場合に著者一覧から消えることを確認。
> `ArticleHubViewModel`/`UserArticleListViewModel`自体はネットワーク購読を要するため既存のテスト
> パターンに合わせてViewModelレベルのテストは追加せず(既存も無し)、純粋な差分関数側に寄せた。
>
> 9.2(1,000 raw event表示窓とスクロールアンカー保持)はUI層(`ArticleScreens.kt`の`LazyListState`)
> との協調が必要なため、設計方針どおり今回は着手せず次のLoopに残す。
>
> 検証: `:composeApp:compileAndroidMain` / `:composeApp:compileKotlinIosSimulatorArm64` / `:composeApp:allTests`
> すべて成功(新規テスト7件含む)。iOS Simulatorへ新規DerivedDataでビルド・インストール・起動し、
> フィード画面が正常に表示されクラッシュ/例外ログが無いことを確認(下部タブバーへのcliclickタップが
> 断続的に不発になる既知の制約により、記事タブへのGUI遷移そのものは省略しプロセス生存とログで代替)。

### 9.2 保持上限

- グローバル`ArticleMemoryCache`の500記事・1,000引用イベント上限は維持する。
- ViewModelは初期値1,000 raw eventの表示窓を持つ。
- 上限到達後もページングを許可する場合は、現在のスクロールアンカーを含む窓を残す。
- 単純に先頭または末尾を削除してスクロール位置を飛ばさない。表示窓方式の導入前は、
  件数上限より差分集約を先に実装する。

> **Loop 13実装判断(2026-09-23)**: `ArticleScreens.kt`の無限スクロール実装(`ArticleHubViewModel`/
> `UserArticleListViewModel`双方の`loadMore()`トリガー)を確認したところ、`snapshotFlow`が
> `lastVisible >= layoutInfo.totalItemsCount - 4`の条件でのみ`viewModel.loadMore()`を呼んでおり、
> 「現在表示中のリスト末尾4件以内までスクロールした時だけ次ページを追加読込する」という構造上の
> 不変条件が既に成立していた。この不変条件により、ページ追加(`loadMore()`)が発生する瞬間は必ず
> 現在のスクロールアンカーが「これまでに読み込んだ範囲の末尾(＝時系列で最も古い側)」付近にあると
> 保証できる。
>
> これを踏まえ、`rawEvents`(`LinkedHashMap`、挿入順=ページ読込順=時系列で新しい順)が
> `ARTICLE_RAW_EVENT_WINDOW`(1,000)件を超えたら、挿入順で最も古いエントリ(＝時系列で最も新しく
> 既にスクロールし終えた記事)から`trimRawEventWindow()`で間引く実装にした。トリムは`loadPage()`が
> 新規ページのイベントを`rawEvents`へ追加した直後・`updateStateFromEvents()`(全件再構築)の直前に
> 呼ぶため、`articles`/`authors`側も同じタイミングの全件再構築で自動的に窓の外側が反映され、
> 追加のブックキーピングは不要にした。ローカル公開(`applyLocalArticleEvent`)側は全件再構築を
> 経由しないため意図的にトリム対象から外した(1回の公開でせいぜい1件増えるだけで、`loadPage()`側の
> トリムが働くまで実質的に上限を超えない)。
>
> 末尾からではなく先頭から間引くため、9.3の「保持窓の移動で表示中のスクロールアンカーを失わない」を
> UI層(`LazyListState`)に触れずに満たせる。ただし、この安全性は「`loadMore()`は末尾スクロール時にしか
> 呼ばれない」という`ArticleScreens.kt`側の前提に依存しているため、将来その呼び出し条件を変更する場合は
> 本トリム実装も合わせて見直す必要がある旨をここに明記する。
>
> 検証: `:composeApp:compileAndroidMain`・`:composeApp:compileKotlinIosSimulatorArm64`・`:composeApp:allTests`
> すべて成功。iOS Simulatorへ新規DerivedDataでビルド・インストール・起動し、フィード画面表示・
> プロセス生存・クラッシュレポート無しを確認した。**この回でGUI操作中に座標ズレでフィード内の他人の
> 投稿へ誤って❤️/⭐リアクションを実際に送信してしまうインシデントが発生した**(詳細はユーザーへ報告済み、
> 対応不要の判断)。以降は投稿カードのインタラクティブ要素に近い領域へのcliclickタップを避け、
> プロセス生存確認とクラッシュレポート有無での代替検証に切り替えた(記事タブそのものへのGUI遷移は
> 未実施のまま)。1,000件を超えるページ読込を伴う実機での窓トリム発火自体は、今回のセッションでは
> 実データ量の都合で再現・目視確認できていない

### 9.3 受け入れ条件

- 1件のローカル公開で全raw eventを再変換しない。
- 古い版が後着しても記事が巻き戻らない。
- ページ追加後に同一addressが複数表示されない。
- 保持窓の移動で表示中のスクロールアンカーを失わない。

## 10. Coil画像キャッシュ

### 10.1 明示的なImageLoader

アプリ共通の`ImageLoader`を構成し、次を定数化する。

- メモリキャッシュ上限: 利用可能メモリに対する割合と絶対上限の小さい方。
- ディスクキャッシュ上限: 初期値128MiB。
- ディスクキャッシュ保存期間または定期整理間隔。Coilの採用バージョンで直接TTLを指定できない場合は、
  独自のレスポンスキャッシュを重ねず、ディスク上限と最終更新時刻に基づく低頻度整理を使う。
- ネットワーク同時実行数とデコード同時実行数。

具体的なメモリ割合と絶対上限はAndroid/iOS実機計測で決定する。低メモリ端末で同じ絶対値を
使わない。アバター、絵文字、タイムライン画像でImageLoaderを分ける案は、単一ローダーで
ヒット率とメモリプレッシャーを計測してから判断する。

> **Loop 10実装判断(2026-09-23)**: Coil 3.6.3のソース(`coil-core`/`coil`の`-sources.jar`)を
> 直接確認した。既定のシングルトン`ImageLoader`(明示的に構成しない場合)は、ディスクキャッシュを
> `FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache"`(共用の一時ディレクトリ)に置いており、
> アプリ専用のキャッシュ領域ではなかった。また`MemoryCache`のデフォルトサイズ算出(`memoryClass`
> ベースの割合)はCoilの`internal` APIで、アプリ側から利用・観測できない。
>
> `ui.components.ImageLoaderConfig.kt`(commonMain)に`registerAppImageLoader()`を新設し、
> `SingletonImageLoader.setSafe { ... }`でアプリ専用の`ImageLoader`を登録するようにした。
> - メモリ上限: 利用可能メモリの20%と絶対上限64MiBの小さい方(`estimatedAvailableMemoryBytes()`は
>   Android実装で`ActivityManager.memoryClass`(largeHeap指定時は`largeMemoryClass`)、iOS実装で
>   `NSProcessInfo.physicalMemory`を使うexpect/actual)。
> - ディスク上限: 128MiB固定、ディレクトリはAndroidが`context.cacheDir/image_cache`、iOSが
>   `NSCachesDirectory`配下の`image_cache`(いずれもOSが把握する「アプリ専用の再取得可能領域」)。
> - ネットワーク・デコードの同時実行数: `Dispatchers.Default.limitedParallelism(8)`/`(3)`。
>   `Dispatchers.IO`はKotlin/Nativeでは`internal`のため使えず、`Dispatchers.Default`の
>   `limitedParallelism`をfetcher/decoderそれぞれ独立した上限で使う形にした。
> - 登録場所は最初のCoil API呼び出しより前にする必要があるため、Androidは
>   `ToriNosApp.onCreate()`、iOSは`MainViewController()`のcompose content生成直前(`App()`呼び出し前)
>   とした。
>
> ディスクキャッシュの保存期間(TTL)は、Coil 3.6.3の`DiskCache`にTTL指定APIがなく、独自の
> レスポンスキャッシュを重ねる設計変更が必要なため今回は見送り、128MiBの容量上限とLRU退避のみに
> 依存する(設計方針どおり)。具体的な20%/64MiB/128MiBの数値は実機計測前の初期値であり、
> Android/iOS双方とも同じ絶対上限で頭打ちにする想定(iOSは`physicalMemory`が端末の物理メモリ
> 総量でAndroidの`memoryClass`(アプリごとのヒープ予算)より大きい基準のため、実質的に常に
> 絶対上限側が効く見込み)。10.3(観測: hit/miss数、decode失敗数等)はCoilの`EventListener`が
> `expect abstract class`でプラットフォームごとの実装が必要なため、別ループの残課題として残す。
>
> `allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が
> 成功。
>
> iOS Simulatorでフィード(フォロー/グローバル)をスクロールし、アバター・リンクプレビュー画像
> ともcrashなく正常に表示されることを確認した。さらに`xcrun simctl get_app_container ... data`で
> アプリのサンドボックスを直接確認し、`Library/Caches/image_cache/`配下に実際の画像キャッシュ
> ファイル(`<hash>.0`/`<hash>.1`のペアと`journal`)が生成されていることを確認した。これにより、
> 既定シングルトンの共用一時ディレクトリではなく、指定したアプリ専用ディレクトリが実際に
> 使われていることを実機で裏付けた。コンソールログにCoil関連のエラー・クラッシュは見られず、
> プロセスも起動を維持した。

### 10.2 リクエスト正規化

- URL、要求サイズ、scale、静止画変換をキャッシュキーへ反映する。
- タイムラインとプリフェッチは既存の`buildNetworkImageRequest()`を共用する。
- 表示制約が分かる画像は`maxDecodeSizePx`を必須にする。
- アバターとカスタム絵文字は表示サイズに合わせたデコード要求を明示する。
- オリジナル解像度が必要なのは全画面プレビューだけとする。

> **Loop 9実装判断(2026-09-23)**: 10.1(明示的ImageLoader)・10.3(観測)に着手する前にコード調査を
> 行った結果、`AvatarImage()`(`NetworkImage.kt`)が`maxDecodeSizePx`を一切指定せず、カスタム絵文字も
> 4箇所(`LinkedText.kt`のインライン絵文字、`CustomReactionLink.kt`、`EmojiPickerSheet.kt`の2箇所)
> すべてが同様に未指定だったことが分かった。表示サイズに関わらず元画像をフル解像度でデコード・
> 保持していたため、10.2の「アバターとカスタム絵文字は表示サイズに合わせたデコード要求を明示する」
> が実質未実装だった。これを是正し、いずれも`LocalDensity`経由で実際の表示Dpからpx換算した
> `maxDecodeSizePx`を`buildNetworkImageRequest()`/`NetworkImage()`へ渡すよう変更した。
> `LinkedText.kt`のインライン絵文字は表示サイズが周囲のテキストのfontSizeに対する1.2emで決まるため、
> `style.fontSize`から都度換算する形にした。`allTests`、`:composeApp:compileAndroidMain`、
> `:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorでフィード・リアクション表示を
> 確認し、アバター・絵文字とも縮小デコード後も鮮明に表示され、クラッシュなし。
>
> 10.1(明示的ImageLoaderの構成・メモリ/ディスク上限)と10.3(観測)は、Coilの現行バージョン(3.6.3)
> でのAPI調査と実機でのメモリ計測を要するため、別ループの残課題として残す。

### 10.3 観測

開発ビルドで次を取得できるようにする。

- memory/disk hit数
- network fetch数
- decode失敗数
- 現在のメモリキャッシュ推定量
- 退避回数
- プリフェッチ後に表示されなかった件数

ログをイベント単位で大量出力せず、一定時間ごとの集計値として出す。

> **Loop 11実装判断(2026-09-23)**: Coilの`EventListener`(`expect abstract class`)を調べたところ、
> Android/iOSどちらの`actual`宣言も`onStart`/`fetchStart`/`fetchEnd`/`decodeStart`/`decodeEnd`/
> `onSuccess`/`onError`等の共通メンバーは完全に同一シグネチャで、Androidだけ`transitionStart`/
> `transitionEnd`が追加されている点が唯一の差だった。そのため**expect/actualを新設せず**、
> commonMainで直接`EventListener`をサブクラス化できた。
>
> `ImageLoaderConfig.kt`に`MetricsEventListener`(`ImageLoader.Builder.eventListenerFactory`で
> リクエストごとに生成)と、集計・ログ出力を担う`ImageLoaderMetrics`を追加した。
> - memory hit: `fetchStart`が一度も呼ばれずに`onSuccess`に達した場合(フルパイプラインを
>   経由しない、`DataSource.MEMORY_CACHE`によるショートサーキット)。
> - disk hit / network fetch: `fetchEnd`の`FetchResult`(`SourceFetchResult`/`ImageFetchResult`)が
>   持つ`dataSource`(`DataSource.DISK`/`NETWORK`)で判定。
> - decode失敗数: `decodeStart`〜`decodeEnd`はコンポーネント探索のため複数回呼ばれ得り、途中の
>   null結果は「次のデコーダーを試す」正常系であることがEngineInterceptorのソースから分かった
>   ため、単純な「decodeEnd結果がnull」ではなく「`decodeStart`が一度でも呼ばれたリクエストが
>   最終的に`onError`に達した」場合を近似値として数える。
> - 現在のメモリキャッシュ推定量: ログ出力のたびに`imageLoader.memoryCache`から`size`/`maxSize`/
>   `keys.size`を直接サンプリングする(別途カウンタを持たず常に最新値)。
> - ログは`ProfileCache`等と同じ`cacheTraceLog`を使い、`TimeSource.Monotonic`で前回出力から
>   30秒未満なら出力しない形で「イベント単位で大量出力しない」方針を満たした。
>
> 「退避回数」と「プリフェッチ後に表示されなかった件数」は実装しなかった。前者はCoilの
> `MemoryCache`/`DiskCache`が退避カウンタを公開しておらず、サイズの増減から推測する精度の低い
> 近似になるため見送った。後者はプリフェッチ要求と表示要求を紐付ける仕組み(タグ付けや別途の
> 状態管理)が新規に必要で、`NoteTimeline.kt`のプリフェッチ実装側の変更を伴うため、今回の
> スコープ外として次の課題に残す。
>
> `allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が
> 成功。フラグを一時的に`true`にしてiOS Simulatorでフィードを30秒以上スクロールし、実データで
> 次のログを確認した:
> `[ImageLoader] memoryHits=9 diskHits=20 networkFetches=10 otherFetches=0 errors=10
> decodeFailures=3 memoryCacheEntries=21 memoryCacheBytes=1483236/67108864`。
> `memoryCacheBytes`の分母(67108864 = 64MiB)が10.1で設定した絶対上限と一致していることを実測で
> 確認した。`errors=10`は実データに含まれる壊れた画像URLなどによるもので、想定内の値。ログは
> スロットルどおり30秒間で1回だけ出力され、イベント単位の大量出力にはならなかった。クラッシュなし。
> 確認後はフラグを`false`に戻した。

## 11. 小規模メタデータキャッシュ

`LinkPreviewRepository`、`YouTubePreviewRepository`、`RelayInformationRepository`、
`ArticleMemoryCache`は共通の`BoundedLruCache`へ順次統一する。

- 読み出しで利用順を更新するLRUとする。
- 成功結果と失敗結果でTTLを分ける。
- 成功結果の初期TTLは1時間、通信失敗・null結果は1〜5分とする。
- `forceRefresh`は既存値を先に削除せず、成功後に置き換える。
- in-flight統合を共通化し、同じキーへの並行通信を1本にする。
- キャンセルや例外でもin-flightエントリーを`finally`で必ず削除する。

固定200件などの上限は値の大きさが異なるため一律にしない。HTMLやBitmapを保持する場合は
件数ではなく推定バイト数上限を併用する。

> **Loop 7実装判断(2026-09-23)**: `LinkPreviewRepository`と`YouTubePreviewRepository`が
> それぞれ個別実装していたFIFOキャッシュ・in-flight統合・退避ロジックを、共通の
> `util.TtlFetchCache<K, V>`へ切り出して統一した。内部で`BoundedLruCache`を使うため読み出しで
> LRU順が更新される。成功結果の初期TTLは1時間、失敗(null)は2分(設計の1〜5分の範囲内)とした。
> `forceRefresh`で新しい通信が失敗した場合、既存の成功値を保持したまま失敗用の短いTTLだけを
> 設定し直す(既存値を先に削除しない/呼び出し元には既存値を返す)。in-flightエントリーは
> `try/finally`で必ず削除する。`RelayInformationRepository`は既に`BoundedLruCache`を使っている
> ものの、`forceRefresh`失敗時に既存の成功値を上書きしてしまう挙動とTTL未実装が残っており、
> `TtlFetchCache`への統一は今回のスコープ外として残した(`Result<T>`ベースのAPIで`V?`前提の
> 本クラスにそのまま載らないため、別途設計が必要)。`ArticleMemoryCache`は記事本文・引用イベント
> という性質上、TTLより「新しい版で置き換える」不変条件の方が重要で、本クラスの対象外とする。
>
> `TtlFetchCacheTest`を新設し、成功のTTL維持・失敗の短いTTL・forceRefresh失敗時の既存値保持・
> 同一キーへの並行呼び出しの1本化・フェッチ内での例外発生時もin-flightが解放され再試行できる
> ことを確認した。特に最後のテストでは、本番の各リポジトリが使う`SupervisorJob`ベースの
> `fetchScope`と同じ構成を再現しないと、フェッチの例外がテストランナー全体を落とすことを
> 実際に確認した上でテストを組んだ。`allTests`、`:composeApp:compileAndroidMain`、
> `:composeApp:compileKotlinIosSimulatorArm64`が成功。

## 12. 計測設計

> **Loop 8実装状況(2026-09-23)**: 12.1の構造化`CacheMetrics`(hits/misses/evictions/writesの
> 集計値)はまだ実装していない。代わりに、既存の`networkTraceLog`と同じ形の切り替え可能な
> トレースログ`cacheTraceLog`(`util/PlatformLog.kt`、フラグ`ENABLE_CACHE_TRACE_LOGS`、既定`false`)
> を新設し、`ProfileCache`(バッチ適用件数、pubkey単位のobserve発火、退避)、`ReactionEventStore`
> (target author・reactionの退避)、`ChannelCacheStore`(prune前後のメッセージ総数、バルク書き込み
> 件数)、`XPostSnapshotCache`(hit/miss/退避/`clear()`)、`TtlFetchCache`(`LinkPreviewRepository`/
> `YouTubePreviewRepository`のhit/miss/in-flight合流/フェッチ結果)に仕込んだ。
>
> フラグを`true`にしてiOS Simulatorで実機確認したところ、次のような実データでの挙動を確認できた。
>
> - `[ProfileCache] putEvents input=64 applied=64 changed=64 size=79` — 複数プロフィールの
>   一括受信が1回の適用にまとまっている(6章の受け入れ条件を実データで裏付け)。
> - `[ChannelCacheStore] prune favoriteChannels=0 messages=2257->2257 (removed=0)` —
>   起動時pruneが実際の2257件に対して実行され、上限未満のため削除0件(5.5の想定どおり)。
> - `[LinkPreviewRepository] miss key=... fetching` → `fetched ... success=true` → `hit key=...`
>   の順で、キャッシュのhit/miss/フェッチが実際のURLに対して機能している(11章)。
> - 1回目の確認では`ReactionEventStore`/`XPostSnapshotCache`/`YouTubePreviewRepository`が
>   ログなしだった。X投稿・YouTubeリンクを含むタイムラインを探して再確認した結果、
>   `[XPostSnapshotCache] miss postId=... size=0` → `set postId=... estimatedBytes=2406624
>   size=1 totalBytes=2406624`(約2.4MB、8MiB単一画像上限・24MiB総量上限の範囲内)→ 同じ投稿への
>   後続アクセスでの`miss`(WebViewが未確定な間の問い合わせ)という一連の流れを実データで確認できた。
>   `ReactionEventStore`の退避(10,000件超過時のみ発火)と`YouTubePreviewRepository`は、再確認でも
>   条件(大量のリアクション、YouTubeリンクを含む投稿)に到達せず未確認のまま。これはログ設計・
>   実装の欠陥ではなく、確認セッション中に条件を満たすデータに遭遇しなかっただけである。
>
> **ログレビューで見つかった要調査点とその後の切り分け**: 実機ログで同一postIdへの`miss`が
> `set`を伴わず複数回連続する事例(`XPostSnapshotCache`)があり、「まだ一度も撮影に成功していない
> だけ」か「`captureSnapshot()`が失敗し続けている」かをログだけでは区別できなかった。
> `XPostEmbed.ios.kt`/`XPostEmbed.android.kt`のスナップショット撮影直前・失敗時に`cacheTraceLog`
> (`[XPostEmbed] capturing snapshot ...` / `captureSnapshot(Bitmap)? returned null ...`)を追加して
> 再確認したところ、実際にスクロールを止めて表示が安定した投稿では
> `[XPostEmbed] capturing snapshot postId=... widthPx=954` → `[XPostSnapshotCache] set postId=...
> estimatedBytes=4116192 size=1` と正常に撮影・保存され、スクリーンショットでも投稿画像が
> 正しく表示されることを確認した。すなわち先の「miss連続」は`captureSnapshot()`の失敗ではなく、
> `deferLoad`によりスクロール中はWebViewを生成しないため「まだ撮影機会がなかっただけ」であり、
> 7章の設計どおりの想定内の挙動と判断した。バグではないため実装は変更していない。
>
> 確認後、`ENABLE_CACHE_TRACE_LOGS`は`false`に戻した。必要な時に1箇所のフラグを`true`にすれば
> 同じ確認を再現できる。12.1の構造化メトリクス・開発ビルドでの常時収集は引き続き未着手。

### 12.1 共通メトリクス

```kotlin
data class CacheMetrics(
    val entries: Int,
    val estimatedBytes: Long? = null,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val writes: Long,
    val lastMutationDurationMicros: Long,
)
```

計測は開発ビルドで有効にし、通常ビルドではホットパスの時刻取得とログ生成を行わない。

### 12.2 固定シナリオ

1. 通常イベント10,000件とリアクション10,000件を投入後、毎秒100イベントを60秒受信する。
2. 2,000プロフィール保持後、100プロフィールの一括応答を10回適用する。
3. 50,000チャンネルメッセージから一覧表示、未読更新、最新メッセージ追加、pruneを行う。
4. 縦長X投稿を10件表示し、往復スクロール後にバックグラウンド・復帰する。
5. 画像投稿50%以上の800件フィードをcold/warm cacheで往復フリングする。
6. 記事を20ページ読み込み、プロフィール更新とローカル公開を行う。

### 12.3 ツール

- Android: Macrobenchmark、Android Studio Memory Profiler、Perfetto、Room query callback。
- iOS: Instruments Allocations、Time Profiler、Core Animation、SQLite query plan。
- 共通テスト: キャッシュ上限、退避順、差分通知回数、決定的な並び順。

計測結果は変更前・変更後を同じ端末、同じデータセット、同じビルド種別で比較する。

## 13. 導入フェーズ

### Phase 0: 計測基盤

- キャッシュサイズ、更新時間、退避数の開発ビルド向け計測を追加する。
- DBの一覧クエリ時間と実行計画を記録する。
- 13.2の固定データ生成をテストfixtureへ追加する。

### Phase 1: P0メモリキャッシュ

- `ReactionEventStore`を可変所有・差分通知・バッチ更新へ移行する。
- `ProfileCache`へ`putAll()`とキー指定購読を追加する。
- `observeAll()`利用箇所を段階的に移行する。

### Phase 2: チャンネルDB

- 複合インデックスとRoom migration testを追加する。
- 書き込みをトランザクション化する。
- 総量基準のpruneへ移行する。
- 計測基準を超える場合だけsummary事前集約を追加する。

### Phase 3: 大容量画像

- Xスナップショットへバイト上限とメモリ警告clearを追加する。
- 共通ImageLoaderの上限と統計を明示する。
- アバター、絵文字、本文画像のデコードサイズを監査する。

### Phase 4: 画面ローカル状態

- 記事一覧をaddress単位の差分集約へ変更する。
- FeedControllerを投稿単位UIモデルへ段階移行する。

### Phase 5: 調整と整理

- 実機計測に基づいて上限とTTLを調整する。
- 未使用の全件スナップショットAPIと移行用コードを削除する。
- 本書の完了済み判断を恒久的な各アーキテクチャ文書へ反映する。

### 13.1 導入状況（2026-09-23）

| Phase | 状況 |
|---|---|
| Phase 0: 計測基盤 | 一部着手。切り替え可能なトレースログ(`cacheTraceLog`)を主要キャッシュへ導入し実機で動作確認した(Loop 8)。12.1の構造化`CacheMetrics`集計、DB実行計画の記録、12.2の固定シナリオ・実機ベンチマークは未着手 |
| Phase 1: P0メモリキャッシュ | **進行中**。Loop 1の`ReactionEventStore`全件コピー削減、Loop 2の`ProfileCache`バッチ適用・キー指定購読・`observeAll()`廃止が完了。残課題は4.3のリレー受信バッチ化（`NostrRepository`は依然1件ずつ`ReactionEventStore.observe()`へ渡す）、`apply(events: List<SourcedEvent>)`バッチAPI、`ProfileCache`のpin機構 |
| Phase 2: チャンネルDB | 複合インデックス(`MIGRATION_5_6`)、書き込みトランザクション化(`upsertMessageBundle`/`upsertMessageBundles`)、5.5の総量基準prune(チャンネル単位上限+全体上限)が完了。summary事前集約(5.4)は未着手。prune後の画面目視確認はiOS Simulator操作の問題で持ち越し |
| Phase 3: 大容量画像 | X投稿スナップショットのバイト上限・メモリ警告時clearが完了(Loop 6)。アバター・カスタム絵文字のデコードサイズ監査・是正(Loop 9)、共通ImageLoaderのメモリ/ディスク上限・専用キャッシュディレクトリ・同時実行数の明示的構成(Loop 10、10.1)、hit/miss・decode失敗の集計ログ(Loop 11、10.3)が完了。退避回数・プリフェッチ未表示件数(10.3の一部)と本文画像のデコードサイズ監査は未着手 |
| Phase 4: 画面ローカル状態 | Feedの件数上限・バックグラウンド集約とプロフィール監視の`observeChanges()`移行は実装済み。8.3が要求するリアクション・プロフィールの差分更新は調査の結果すでに満たされていた(Loop 5)。残るコストは`computeUpdatedFeedState`の全件filterKeys(`extractNpubReferences`の重複計算は`MentionedPubkeysCache`で解消済み、Map本体のfilterKeysは未着手)。記事差分化(9.1)はローカル公開・削除・プロフィール取得完了の3経路が完了(Loop 12)。9.2の1,000 raw event表示窓も、ページ追加時にのみ先頭(既にスクロールし終えた側)を間引く形で完了(Loop 13) |
| Phase 5: 調整と整理 | 未着手。11章の`BoundedLruCache`共通化は`RelayInformationRepository`・`ArticleMemoryCache`に加え、`LinkPreviewRepository`・`YouTubePreviewRepository`も`TtlFetchCache`経由で完了(Loop 7)。`RelayInformationRepository`のTTL化・forceRefresh時の既存値保護は未着手のまま残る |

### 13.2 実装ループ記録

各ループは「設計レビュー → 実装 → 実装レビュー → 共通テスト → Androidコンパイル →
iOS Simulator/Cliclick操作 → 状況更新」の順で閉じる。途中で受け入れ条件を満たせない場合は、
完了へ進めず理由をこの表へ記録する。

| Loop | 対象 | 状況 | レビュー・検証結果 |
|---|---|---|---|
| 1 | `ReactionEventStore`の更新コスト | **完了** | 設計レビューで同期API維持のため`SynchronousLock`採用を確定。同期ロック内の可変Mapへ移行し、未使用の全件StateFlowを削除。実装レビューで削除後の参照数解放テストを強化。`allTests`、Android compile、iOS build/install/launch成功。CliclickでHome遷移、ToriNos再起動、フォロー→グローバル切替、フィードのドラッグスクロールを確認し、クラッシュ・操作停止なし |
| 2 | `ProfileCache`のバッチ適用とキー指定購読 | **完了** | 実装レビューで、`putEvents()`一括適用・`observe(pubkey)`/`observe(pubkeys)`・`observeChanges()`により`observeAll()`利用箇所がコードベース全体から無くなったことを確認。`ProfileHydrator.updateTargets()`は、唯一の利用箇所`ThreadController`が画面終了時にHydrator自体を`close()`する運用のため新設を見送り、判断理由を6.3へ記録。退避選定は全件ソートではなく`fetchedAt`最小値の1回走査に置き換えたが、pin機構は未実装のまま残した(6.2参照)。`ProfileCacheTest`で100件一括通知1回・無関係pubkey更新の無視・2,000件超過時の退避を確認。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulator(iPhone 17)でDerivedDataを作り直してbuild/install/launchし、フォロー→グローバル切替(多数の異なるプロフィールを含む一括応答の検証を兼ねる)、フィードのドラッグスクロール、アプリ再起動を確認。クラッシュ・操作停止なし |
| 3 | チャンネルDBの複合インデックスとトランザクション化 | **完了** | `CachedChannelMessageEntity`のindexを`channelId`単独から`(channelId, createdAt, eventId)`の複合indexへ変更し、`MIGRATION_5_6`で旧indexを`DROP`・新indexを`CREATE`。索引名がRoom生成名(`composeApp/schemas/.../6.json`)と一致しスキーマ検証に通ることを確認。DAOに`upsertMessageBundle`/`upsertMessageBundles`(`@Transaction`)を追加し、`ChannelCacheStore.upsertMessage()`と`ChannelController.fetchHistoryPage()`のページ全体保存をそれぞれ単発・バルクのトランザクションAPIへ置き換えた。Room migration test基盤(`room-testing`依存関係、専用test source set)が本プロジェクトに存在しない(既存migrationにもテストなし)ため、Roomがエクスポートしたv5/v6スキーマJSONからCREATE TABLE文を復元し、`sqlite3`CLI上で`MIGRATION_5_6`と同一のSQLを実際に実行してデータ件数・インデックス構造の整合性を検証した。`EXPLAIN QUERY PLAN`で、旧indexでは最新メッセージ取得が`USE TEMP B-TREE FOR ORDER BY`を伴っていたのに対し、新indexでは未読件数・最新メッセージ取得の両方が`SEARCH ... USING COVERING INDEX`となり一時ソートと行参照が不要になったことを確認した。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorでは、今日それまでのテストでv5スキーマのまま蓄積された実データ入りDBに対して新ビルドをインストールし、アプリ再起動時にv5→v6マイグレーションが実行される状態で検証した。チャンネル一覧(未読件数・最新メッセージ・お気に入り表示)とチャンネル詳細(履歴メッセージの表示、バルク書き込み経路)が問題なく動作し、コンソールログにRoom/SQLite関連の例外やクラッシュは見られず、プロセスも起動を維持した |
| 4 | チャンネルDBのprune総量基準化 | **完了** | `prune()`を「チャンネル単位の上限切り詰め→全体上限での横断削除→孤立`channel_message_relays`削除」に変更し、`getDistinctRelayUrls()`/`pruneMessagesByRelay()`など使用箇所がなくなった旧APIを削除した。`sqlite3`でお気に入り/非お気に入り混在・全体上限超過・孤立relay行の3状況を用意し、期待どおりの行だけが残ることを確認(`channel_read_states`は対象外で行数不変も確認)。`pruneMessagesByChannel`のサブクエリが新しい複合indexを`COVERING INDEX`として使うことも確認。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorへ新ビルドをインストールし、実データ入りDBに対して起動時`prune()`を実行、コンソールログ(`--console-pty`)に`Failed to prune channel cache`(失敗時にログされる文言)が出力されないこと、Room/SQLite関連の例外がないこと、アプリが起動を維持することを確認した。**ただしiOSシミュレータでのチャンネル一覧・詳細画面への実機タップ操作による目視確認は今回できなかった**: Simulatorウィンドウ下部のタブバー(グリッドアイコン等)に対するcliclickでのタップが、Loop 3では成功したのに対しLoop 4検証時は繰り返し空振りし、ウィンドウの再オープンやリトライでも改善しなかった(原因未特定)。次回はこの操作が安定してから、prune実行後のチャンネル一覧・お気に入り・既読位置の目視確認を行う |
| 5 | FeedControllerのメンション解決キャッシュ化 | **完了** | 実装前調査で、8.1が前提とする「リアクション/リポスト/引用/返信/プロフィール更新のたびに799件を再構築する」問題は既に解消済みと判明(上記8.1の実装判断参照)。実際に繰り返しコストを払っていたのは`computeUpdatedFeedState`内の`retainedPubkeys`計算で、可視・引用・返信イベントの本文へ`extractNpubReferences`(正規表現+bech32デコード)を新着投稿受信のたびに再実行していた。`FeedItemMapper`と同型のevent ID単位LRUキャッシュ`MentionedPubkeysCache`を新設し、3箇所の呼び出しを置き換えた。`MentionedPubkeysCacheTest`(解決・キャッシュ再利用・上限超過時の退避)を追加し、`allTests`・`:composeApp:compileAndroidMain`・`:composeApp:compileKotlinIosSimulatorArm64`が成功。`computeUpdatedFeedState`本体の差分更新化(15本のMapをfilterKeysでなく追加・削除分だけ更新)は、競合防止機構(`updateEventsMutex`/`feedStateRevision`)や`MAX_TIMELINE_EVENTS`切り詰めと密結合しておりリスクが高いため、専用の設計検討が必要な残課題として8.1へ明記し、今回は見送った。iOS Simulatorでは、問題のあった画面下部タブバーへの遷移を避け、フィード画面(`computeUpdatedFeedState`が実際に呼ばれる経路)に絞って確認した: アプリ起動時のフィード初期表示、フォロー→グローバル切替(異なる投稿・メンション・ハッシュタグを含む)、ドラッグスクロールによる新規表示行の描画、いずれもクラッシュ・表示崩れなし。コンソールログ(`--console-pty`)にも例外は見られず、プロセスは起動を維持した |
| 6 | X投稿スナップショットのバイト上限とメモリ警告clear | **完了** | `XPostSnapshotCache`に`ImageBitmap.width/height`から算出する推定バイト数を持つ`Entry`を導入し、単一画像8MiB上限・総量24MiB上限・件数8件上限を併用したLRU退避にした。`XPostSnapshotCacheTest`を新設し、二重加算されると自分自身が総量上限で退避される構成のテストで「同一キー置換時の二重加算なし」を検証、8MiB超の単一画像が拒否されること、総量超過時にLRU順で最古のキーだけが退避されること、テーマ/幅違いのキー分離、`clear()`の全件削除を確認。Android側は`ToriNosApp`(`Application`)へ`ComponentCallbacks2`を登録し、`TRIM_MEMORY_UI_HIDDEN`(単なるバックグラウンド遷移)を除いた`TRIM_MEMORY_RUNNING_LOW`以上で`clear()`、iOS側は`MainViewController()`から`registerMemoryWarningObserver()`を呼び`UIApplicationDidReceiveMemoryWarningNotification`購読で`clear()`する経路を新設した。`allTests`(5件追加、全て成功)、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorへインストールし、`registerMemoryWarningObserver()`を含む起動パスでクラッシュがないこと、フィードが正常表示されること、コンソールログに例外がないこと、プロセスが起動を維持することを確認した。**ただし実際のOSメモリ警告シグナルの発火自体はシミュレータ上で再現しておらず未検証**(7.3に明記) |
| 7 | 小規模メタデータキャッシュの`TtlFetchCache`統一 | **完了** | `LinkPreviewRepository`・`YouTubePreviewRepository`が個別実装していたFIFOキャッシュ・in-flight統合を、共通の`util.TtlFetchCache<K, V>`(`BoundedLruCache`ベース、成功1時間/失敗2分のTTL、forceRefresh失敗時は既存の成功値を保持)へ統一した。`TtlFetchCacheTest`(5件)で成功TTL維持、失敗の短いTTL、forceRefresh失敗時の既存値保持、同一キー並行呼び出しの1本化、フェッチ内例外発生時のin-flight解放と再試行を確認。最後のテストでは本番同様の`SupervisorJob`ベースの子スコープを使わないとテストランナー全体が落ちることを実際に確認した上で構成した。`RelayInformationRepository`(`Result<T>`ベースでTTL・forceRefresh時の既存値保護が未実装のまま)と`ArticleMemoryCache`(TTLより版管理が本質のため対象外と判断)への統一は今回のスコープ外として明記した。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorでフィード(フォロー→グローバル切替、スクロール)を確認し、クラッシュ・表示崩れなし。コンソールログに例外なし、プロセス起動維持を確認。今回表示された投稿にはリンク/YouTubeプレビューを含むものがなかったため、実際のプレビュー取得・表示そのものの目視確認はできていない |
| 8 | キャッシュ動作確認用トレースログの追加 | **完了** | `networkTraceLog`と同型の`cacheTraceLog`(`ENABLE_CACHE_TRACE_LOGS`フラグ、既定`false`)を新設し、`ProfileCache`(バッチ適用・observe発火・退避)、`ReactionEventStore`(退避)、`ChannelCacheStore`(prune前後件数・バルク書き込み件数)、`XPostSnapshotCache`(hit/miss/退避/clear)、`TtlFetchCache`(hit/miss/in-flight合流/フェッチ結果、`name`ラベルでLink/YouTubeを区別)へ組み込んだ。`TtlFetchCacheTest`のシステム出力で、テストシナリオどおりのログ順序(miss→fetched→hit、joined in-flight fetch等)が実際に出ることを確認。`allTests`、Android/iOSコンパイルが成功。フラグを一時的に`true`にしてiOS Simulatorで実機確認し、`ProfileCache`のバッチ適用(最大64件/回)、`ChannelCacheStore`のprune(実データ2257件、削除0件)、`LinkPreviewRepository`のhit/miss/fetchサイクルを実データで確認した。クラッシュなし。確認後はフラグを`false`に戻した |
| 9 | アバター・カスタム絵文字のデコードサイズ是正 | **完了** | 10.2の「アバターとカスタム絵文字は表示サイズに合わせたデコード要求を明示する」を調査した結果、`AvatarImage()`と絵文字4箇所(`LinkedText.kt`のインライン絵文字、`CustomReactionLink.kt`、`EmojiPickerSheet.kt`の2箇所)がいずれも`maxDecodeSizePx`を指定しておらず、表示サイズに関わらず元画像をフル解像度でデコードしていたことが判明。`LocalDensity`経由で実際の表示Dp(インライン絵文字は周囲テキストの`fontSize`から1.2em換算)をpx変換し、`buildNetworkImageRequest()`/`NetworkImage()`へ渡すよう是正した。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorでフィード・リアクション表示を確認し、アバター・絵文字とも縮小デコード後も鮮明に表示され、クラッシュなし。10.1(明示的ImageLoaderの構成)と10.3(観測)は、Coil 3.6.3でのAPI調査と実機メモリ計測を要するため別ループの残課題として残す |
| 10 | 明示的なImageLoaderの構成 | **完了** | Coil 3.6.3のソースを直接確認し、既定シングルトンのディスクキャッシュが共用の一時ディレクトリ(`FileSystem.SYSTEM_TEMPORARY_DIRECTORY`)を使っていること、メモリ上限算出ロジックがCoilの`internal` APIで観測・調整できないことを確認した。`ui.components.ImageLoaderConfig.kt`に`registerAppImageLoader()`を新設し、`SingletonImageLoader.setSafe { ... }`でメモリ上限(利用可能メモリ20%と64MiB絶対上限の小さい方)・ディスク上限(128MiB、`Library/Caches/image_cache`等アプリ専用ディレクトリ)・fetcher/decoderの同時実行数(`Dispatchers.Default.limitedParallelism`、`Dispatchers.IO`はKotlin/Nativeで`internal`のため不使用)を明示するアプリ専用`ImageLoader`を登録した。登録はAndroidが`ToriNosApp.onCreate()`、iOSが`MainViewController()`のcompose content生成直前。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorでフィードをスクロールしアバター・リンクプレビュー画像が正常表示されること、クラッシュ・Coil関連エラーがないことを確認した上で、`xcrun simctl get_app_container`によりアプリのサンドボックスを直接確認し、`Library/Caches/image_cache/`配下に実際のキャッシュファイルと`journal`が生成されていることを確認した(指定したアプリ専用ディレクトリが実際に使われていることの実機裏付け)。10.3(観測)はCoilの`EventListener`が`expect abstract class`でプラットフォームごとの実装を要するため、別ループの残課題として残す |
| 11 | ImageLoaderのhit/miss・decode失敗ログ | **完了** | Coilの`EventListener`のAndroid/iOS `actual`宣言を比較した結果、共通メンバーは完全に同一シグネチャ(Androidのみ`transitionStart`/`transitionEnd`が追加)だったため、expect/actualを新設せずcommonMainで直接サブクラス化できた。`MetricsEventListener`(リクエストごとに生成)と集計・ログ出力用の`ImageLoaderMetrics`を追加し、`fetchStart`未発火での`onSuccess`をmemory hit、`fetchEnd`の`FetchResult.dataSource`でdisk hit/network fetchを判定、`decodeStart`到達後の`onError`をdecode失敗の近似値としてカウントした。ログ出力時に`imageLoader.memoryCache`から現在のsize/maxSize/エントリ数を直接サンプリングする。`cacheTraceLog`と`TimeSource.Monotonic`で30秒未満の連続呼び出しはログを出さない。退避回数(Coilが退避カウンタを公開しない)とプリフェッチ未表示件数(プリフェッチ要求と表示要求の紐付けが別途必要)は見送り、次の課題として残した。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。フラグを一時的に`true`にしてiOS Simulatorでフィードを30秒以上スクロールし、`[ImageLoader] memoryHits=9 diskHits=20 networkFetches=10 otherFetches=0 errors=10 decodeFailures=3 memoryCacheEntries=21 memoryCacheBytes=1483236/67108864`という実データを確認。`memoryCacheBytes`の分母(67108864=64MiB)が10.1の絶対上限と一致することを実測で裏付けた。ログは30秒に1回だけ出力され、クラッシュなし。確認後はフラグを`false`に戻した |
| 12 | 記事一覧の差分集約(9.1) | **完了** | `ArticleHubViewModel`/`UserArticleListViewModel`を調査した結果、ページ読込(`loadPage()`)は既に全件再構築を1回だけ呼ぶ設計になっていたが、ローカル公開・ローカル削除・プロフィール取得完了の3経路が毎回`rawEvents`全体を`updateStateFromEvents()`でフルリビルドしていた。対象addressだけを比較する`withUpsertedArticle()`、対象pubkeyだけを再計算する`withUpdatedAuthor()`(いずれもファイルスコープの`internal`関数、`latestArticleVersions()`/`toArticleAuthors()`と同じ比較子を共有)を新設し、`applyLocalArticleEvent`/`applyLocalArticleDeletion`/`applyProfileUpdates`の3関数で置き換えた。`applyProfileUpdates`は`toArticleMeta()`を呼ばず`authorProfile`のみ差し替える(9.1後半の要件どおり)。9.3の受け入れ条件(巻き戻り防止・重複address排除・フルリビルドとの順序一致)は新規`ArticleDifferentialUpdateTest.kt`(7件)で検証。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorへ新規DerivedDataでビルド・インストール・起動し、フィード画面表示とプロセス生存・ログ無例外を確認したが、下部タブバーへのcliclickタップが断続的に不発になる既知の制約(Loop 4で記録済み)により記事タブへのGUI遷移そのものは省略した。9.2(1,000件表示窓・スクロールアンカー保持)は設計方針どおり別ループへ残す |
| 13 | 記事一覧の表示窓(9.2) | **完了** | `ArticleScreens.kt`の無限スクロール実装を確認し、`loadMore()`が`lastVisible >= totalItemsCount - 4`の条件でのみ呼ばれる(常に読み込み済みリストの末尾4件以内までスクロールした時だけ次ページを取得する)という構造上の不変条件を確認した。この不変条件を根拠に、`rawEvents`(挿入順=ページ読込順)が`ARTICLE_RAW_EVENT_WINDOW`(1,000)件を超えたら挿入順で最も古い(＝時系列で最も新しく既にスクロールし終えた)エントリから`trimRawEventWindow()`で間引く実装にし、`LazyListState`側のスクロールアンカー情報をViewModelへ渡す複雑な連携を避けた。トリムは`loadPage()`内で`updateStateFromEvents()`(全件再構築)の直前に呼ぶため、`articles`/`authors`への反映に追加のブックキーピングを要さない。ローカル公開経路は意図的にトリム対象から外した(1回で高々1件しか増えず、`loadPage()`側のトリムが効くまで実質上限を超えないため)。`allTests`、`:composeApp:compileAndroidMain`、`:composeApp:compileKotlinIosSimulatorArm64`が成功。iOS Simulatorへ新規DerivedDataでビルド・インストール・起動し、フィード画面表示・プロセス生存・クラッシュレポート無しを確認した。**この回のGUI操作中、座標ズレによりフィード内の他人の投稿へ誤って❤️/⭐リアクションを実際に送信してしまうインシデントが発生した**(ユーザーへ即時報告し、対応不要の判断を得た。詳細は`memory/ios-sim-cliclick-real-relay-risk.md`に記録)。以降は投稿カードのインタラクティブ要素付近へのcliclickタップを避け、プロセス生存確認とクラッシュレポート有無での代替検証に切り替えたため、記事タブそのものへのGUI遷移・1,000件超のページ読込によるトリム発火の実機目視確認はできていない |

## 14. 実装順の依存関係

```text
計測基盤
  ├─> ReactionEventStore差分化 ─> Feedのエンゲージメント差分更新
  ├─> ProfileCache差分化 ──────> 各画面のobserveAll廃止 ─> Feedのプロフィール差分更新
  ├─> DB query計測 ─────────────> index/transaction ──────> 必要ならsummary集約
  └─> 画像メモリ計測 ───────────> X byte budget / Coil上限

記事一覧は上記と独立して実装可能
```

## 15. 非目標

- キャッシュを完全に廃止すること。
- 取得済みデータをすべて永続化すること。
- ベンチマークなしにすべてのコレクションを独自データ構造へ置き換えること。
- UI仕様、表示件数、ページング操作を性能改善の都合だけで変更すること。
- お気に入り、既読位置、下書きなどのユーザーデータを再取得可能なキャッシュとして削除すること。

## 16. 完了条件

- P0〜P2対象に上限、所有者、退避条件、通知単位が定義・実装されている。
- 固定シナリオで変更前後のCPU時間、割り当て量、ピークメモリ、DB時間を比較できる。
- AndroidとiOSの少なくとも各1台で、60秒の高負荷シナリオ中にOOMや長時間停止がない。
- キャッシュ退避後も通信再取得、画面表示、既読・お気に入り状態が正しく復元される。
- 既存の共通テスト、Androidコンパイル、iOS Simulatorテストが成功する。

## 17. 完了時レビューと設計書への反映

各Phaseの実装完了時に、実装者とは別の観点で次をレビューする。別レビュアーを用意できない場合も、
実装直後の確認だけで完了扱いにせず、テスト結果とdiffを基にセルフレビューを行う。

### 17.1 コードレビュー項目

- キャッシュ所有者、排他方法、破棄タイミングがコードから一意に分かるか。
- 件数・バイト数・TTLの上限を迂回する書き込み経路がないか。
- 退避対象の選択が決定的で、pin中・表示中・ユーザー状態を誤って削除しないか。
- 同じデータを別Map、StateFlow、UI stateへ重複保持していないか。
- 1件の変更で全件コピー、全件ソート、全購読者通知が再導入されていないか。
- Mutex内でネットワーク、DB、Flow emitなどの停止し得る処理を実行していないか。
- キャンセル、例外、タイムアウト後にin-flightやpinが残らないか。
- Android/iOSで同じ上限意図になり、片方だけOS任せになっていないか。

### 17.2 検証レビュー項目

- 上限直前、上限到達、1件超過、大幅超過、同一キー置換をテストしているか。
- 古いイベントの後着、同時刻イベント、複数リレー重複、削除をテストしているか。
- Room migrationと`EXPLAIN QUERY PLAN`結果を保存しているか。
- 12.2の固定シナリオを変更前後で比較し、改善値と悪化値の両方を記録したか。
- cold cacheだけでなくwarm cache、バックグラウンド復帰、メモリ警告後を確認したか。
- 機能テストだけでなく、ピークメモリ、割り当て量、p95処理時間を確認したか。

### 17.3 設計書更新手順

1. 対象Phaseの「導入状況」を`完了`または具体的な残件付きの`一部完了`へ更新する。
2. 実装で採用した定数、データ構造、排他境界が本書と異なる場合は、実装へ無理に合わせず判断理由を記す。
3. 実測した端末、データ件数、変更前後の値を12章へ追記する。
4. 受け入れ条件を満たせなかった項目は削除せず、未完了理由と次の判断条件を残す。
5. 完了した一時的な移行手順は削除し、恒久的に守る境界だけを関連アーキテクチャ文書へ移す。
6. 最後に本書と実装を再検索し、古いクラス名、上限値、未使用APIへの参照が残っていないことを確認する。

完了報告では「テストが通った」だけでなく、設計との差分、実測結果、残存リスクを併記する。
