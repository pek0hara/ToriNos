# 記事タブ リファクタ設計

## 0. 仕様整理

構造リファクタの前提として、記事タブの画面仕様を次のとおり見直す。本章の項目はすべて確定仕様である。

### 0.1 ユーザータブの廃止

**決定: 廃止する。**

現行のユーザータブには次の問題があり、維持コストに見合う価値が小さい。

- 記事数は取得済みの記事（最大1,000件）の範囲で数えた値で、実際の投稿数ではない。
- 「自分／フォロー／グローバル」の区分は、記事一覧側の著者フィルター（0.2）でまかなえる。
- 著者行に表示するのは最新記事のタイトルだけで、記事一覧のカードより情報が少ない。
- 自動追加読み込みの条件がセクションの開閉状態に依存し、一覧の実装を複雑にしている。

廃止に伴い、次のとおり変更する。

- 記事タブ上部のフローティングのセグメント切り替え（記事一覧／ユーザー）は、0.2の著者フィルター（すべて／フォロー／自分）へ置き換える。
- 記事カードの著者行（アバター・名前）をタップすると、その著者の記事一覧（`UserArticleListScreen`）を開く。
  - 現行では著者行のタップでも記事詳細が開くため、著者行とカード本体のタップ領域を分ける。
- 詳細画面の著者行は、現行どおりプロフィールを開く。著者の記事一覧へは、ユーザー別記事一覧のヘッダーから
  プロフィールへ移動できるようにし、相互に行き来できるようにする。
- ユーザー別記事一覧のヘッダーに、アバター・表示名・自己紹介の先頭行を表示する。
- 記事タブ内の横スワイプは、サービスタブの切り替えだけを担う。

失われるものは「記事を書いているユーザーの一覧を眺める」導線である。これは0.2の著者フィルターと、
記事カードの著者行からユーザー別記事一覧へ移る導線で代替する。

### 0.2 一覧フィルター

現行のフローティングのセグメント切り替え（記事一覧／ユーザー）を、同じ位置・同じ見た目のまま
著者フィルター「すべて／フォロー／自分」の3区分へ置き換える。トピックは選択中のときだけ、セグメントの直下に
解除できるチップとして表示する。フィルターはリレーへ送る購読フィルターへ直接変換できる条件だけに限定し、取得済み記事をクライアント側で絞り込むことはしない（件数が少なく見える問題を避けるため）。

| フィルター | 選択肢 | 購読フィルター |
| --- | --- | --- |
| 著者 | すべて（既定） / フォロー / 自分 | `authors`なし / フォローリストの公開鍵 / 自分の公開鍵 |
| トピック | 未選択（既定） / 1件選択 | `#t`（`NostrFilter.tTags`） |

- **著者: フォロー** はフォロー数が多い場合に`authors`を分割して複数フィルターで購読する。
  分割単位はフォローフィードの既存実装に合わせる。
- **トピック** は、記事カード・詳細画面の`#タグ`をタップすると選択される。選択中のトピックはチップとして表示し、
  ×で解除できる。トピックの自由入力欄は設けない。
- 著者とトピックは同時に指定できる（AND条件）。
- 未ログイン時はフローティングのセグメントを表示せず、著者は「すべて」に固定する。トピックのチップは表示する。
- ログイン中にアカウントを切り替えた場合、著者フィルターは「すべて」へ戻す。
- 選択状態はリレー切り替え後も維持し、アプリ再起動では既定値へ戻す。
- ユーザー別記事一覧では著者フィルターを表示せず、トピックフィルターだけを使える。

### 0.3 記事検索

**決定: 記事検索は追加しない。** 検索画面への「記事」タブ追加、記事タブ内の検索入力、NIP-50による
kind `30023`の検索はいずれも行わない。記事の絞り込みは0.2の著者・トピックフィルターだけで行う。

### 0.4 リアクションとコメント

記事詳細画面で、記事へのリアクションとコメントを表示・送信できるようにする。

#### 対象イベント

| 種類 | kind | 記事との関連付け | 取得フィルター |
| --- | --- | --- | --- |
| リアクション | `7` | `a`（記事address）、`e`（参照した版のID）、`k`=`30023`、`p` | `#a`=address、補助的に`#e`=表示中の版ID |
| コメント | `1111`（NIP-22） | ルート: `A`=address、`E`=版ID、`K`=`30023`、`P`=著者 | `#A`=address |
| 旧形式の返信 | `1` | `a`タグで記事を参照 | `kinds=[1]`、`#a`=address |

- 記事は置換可能イベントのため、版IDではなくaddressで集計する。これにより、編集後も過去の版への
  リアクション・コメントが引き継がれる。
- `NostrFilter`に`#A`（ルートaddress）を追加する。
- 現行のリアクション送信（`NoteEngagementService`）は`e`タグのみを付与するため、記事向けに
  `a`・`k`タグを付与する対象型を追加する。kind `1`向けの既存動作は変えない。
- Zap（kind `9735`）は対象外とする。

#### 旧形式の返信（kind `1`）

**決定: コメントとして表示する。**

- 他クライアントが記事への返信としてkind `1`に`a`タグを付けて投稿したものを、kind `1111`のコメントと
  同じ一覧に作成日時順で混在させて表示する。種別の区別は画面に出さない。
- 記事addressと一致する`a`タグのマーカーが`mention`のイベントは、返信ではなく言及とみなして除外する。
  マーカーなし、`root`、`reply`は返信として扱う。
- 本文中の`nostr:naddr`参照だけで`a`タグを持たない言及は対象外とする。
- ToriNosから送信するコメントは常にNIP-22のkind `1111`とし、kind `1`の返信は送信しない。
- kind `1`返信へのリアクション・返信は、既存の通常投稿と同じ操作・スレッド画面で扱う。

#### 表示

- 詳細画面の本文の下に、リアクション集計（絵文字ごとの件数と自分のリアクション状態）を表示する。
- その下にコメント一覧を表示する。並び順は古い順、1階層目のコメントだけを表示し、
  コメントへの返信は件数表示とスレッド画面への遷移で扱う。
- コメント入力は既存の返信投稿シートを流用し、ルートを記事addressとするNIP-22コメントを送信する。
- 記事一覧のカードにはリアクション数・コメント数を表示しない（決定）。一覧全件分の集計購読が必要になり、
  取得コストに見合わないためである。

#### 取得先リレー

記事を取得した記事リレーに加え、自分の読み取りリレーにも問い合わせ、結果をイベントIDで重複除去する。
他クライアントのコメントは記事リレー以外に投稿されていることが多いためである。

### 0.5 決定事項

| 項目 | 状態 | 内容 |
| --- | --- | --- |
| ユーザータブ廃止 | 決定 | 廃止する（0.1） |
| 記事検索 | 決定 | 追加しない（0.3） |
| kind `1`の`a`タグ返信の扱い | 決定 | コメントとして表示する（0.4） |
| 一覧カードへの反応数表示 | 決定 | 表示しない（0.4） |
| Zap対応 | 対象外 | 本変更では扱わない |
| 著者フィルターの形 | 決定 | フローティングのセグメントを「すべて／フォロー／自分」へ置き換える（0.2） |
| 未ログイン時の著者フィルター | 決定 | セグメントを非表示にし「すべて」に固定する（0.2） |

仕様変更の実装時は、`app-requirements.md`の「記事」節とNIP対応表（NIP-22、NIP-23）を同じ変更セットで更新する。

### 0.6 リファクタへの影響

- ユーザータブの廃止により、3.10の著者振り分け・自動追加読み込み条件の純粋関数化、
  `ArticleAuthorItem`・`toArticleAuthors()`・`withUpdatedAuthor()`は不要になり、削除する。
- 一覧の取得条件は`ArticleQuery(authors, topic)`へ一般化し、ユーザー別記事一覧は
  「著者が1人に固定されたクエリ」として同じ`ArticleListViewModel`で扱う。
- 詳細画面の状態に、リアクション集計とコメント一覧の取得状態を追加する（5.3）。
- 移行手順（6章）では、構造整理の段階を先に完了させ、仕様変更は後段の独立した段階として追加する。

## 1. 目的

記事タブ（NIP-23 長文記事）の一覧取得、置換可能イベントの集約、プロフィール付与、詳細表示、
Markdown描画、削除・投稿後のローカル反映を、責務ごとに分離し単体テスト可能な構造へ整理する。

本リファクタでは、特に次の性質を保証する。

1. 記事一覧（全体）とユーザー別記事一覧が、同じ取得・集約・差分反映の実装を共有する。
2. kind `30023`のparameterized replaceable eventを、受信順に依存せず決定的に集約する。
3. ローカルで削除した記事が、後続のページ取得で復活しない。
4. 取得のタイムアウト・リレー未応答を「記事がありません」と区別して表示する。
5. 削除完了などの一時的なUIイベントを、永続状態のカウンタで表さない。
6. 記事画面のViewModelの所有者を、アカウントセッション単位に統一する。
7. Markdownの解析を副作用のない純粋処理として分離し、描画と独立してテストできる。

## 2. 対象範囲

### 2.1 対象

- `ui/article/ArticleViewModel.kt`（`ArticleHubViewModel`、`UserArticleListViewModel`、`ArticleDetailViewModel`、`ArticleMemoryCache`、取得関数群）
- `ui/article/ArticleScreens.kt`（ハブ画面、ユーザー別一覧、詳細、削除ダイアログ、カード、Markdown描画、引用プレビュー）
- `ui/article/ArticleEditorViewModel.kt`のうち、記事取得・タグ生成・投稿後のローカル反映
- `model/Article.kt`の集約関数（`latestArticleVersions`、`toArticleAuthors`）
- 記事用のcommon test

### 2.2 非目標

本リファクタでは次を行わない。

- 記事タブの大幅なデザイン変更
- Markdown記法の対応範囲拡大（表、番号付きリスト、ネストしたリストなど）
- 他クライアントが発行したkind `5`削除イベントを一覧取得時に反映する仕様追加
- 複数リレーをマージした記事タイムラインへの変更
- リレーの`OK`応答を待つ投稿・削除仕様への変更
- 記事の永続キャッシュ追加
- 記事エディタ画面のUI再設計

上記のうち、他クライアント由来の削除反映とリレー受理確認は、構造整理の完了後に独立した変更として扱う。

## 3. 現状と課題

### 3.1 一覧ViewModelの重複

`ArticleHubViewModel`と`UserArticleListViewModel`は、フィルタの`authors`指定とプロフィール取得対象
（未取得の全著者／単一著者）を除き、次の処理がほぼ同一のコピーになっている。

- `refresh()` / `loadMore()` / `loadPage()`
- `updateStateFromEvents()`（ミュート除外、`ArticleItem`化、最新版抽出、キャッシュ格納）
- `applyLocalArticleEvent()` / `applyLocalArticleDeletion()`
- `trimRawEventWindow()` / `removeRawArticle()`
- `applyProfileUpdates()`
- ローカル公開・削除・ミュート変更の購読

片方だけ修正されて挙動が分岐する危険がある。

### 3.2 置換可能イベントの新旧判定が不完全

`latestArticleVersions()`、`withUpsertedArticle()`、`fetchLatestArticleByAddress()`はいずれも
`created_at`だけで比較し、同時刻のバージョンは先着順・既存優先で採用する。NIP-01の置換可能イベントは、
`created_at`が同じ場合にイベントIDが辞書順で小さいものを採用する必要がある。
ステータスタブでは`StatusEventReducer`で対応済みであり、記事側だけ規則が異なる。

### 3.3 ローカル削除が後続取得で復活する

`ArticleMemoryCache.deleteArticle()`はキャッシュと`rawEvents`から該当addressを取り除くだけで、
削除済みaddressを記録しない。NIP-09に対応していないリレーは削除後も旧イベントを返すため、
`loadMore()`や`refresh()`で同じ記事が再び一覧に現れる。

### 3.4 取得結果の完了状態が失われる

`fetchArticleEvents()`は旧来の`subscribe` / `events` / `eose` APIを手動で組み合わせており、次の問題がある。

- `withTimeoutOrNull`でタイムアウトしても、取得できた分を正常結果として返す。
- `relayUrl == null`の場合、最初に届いた1リレーのEOSEで打ち切る。
- 0件のタイムアウトは`canLoadMore = false`かつ「記事がありません」と表示され、接続障害と区別できない。
- 購読IDを`Clock`と`Random`から生成し、終了処理は`close`と`closeSuspending`が混在している。

既存の`NostrRepository.openSubscription`と`SubscriptionBehavior.Fetch`（`EventByIdFetcher`が使用）を
使えば、リレー別の完了結果とタイムアウトを明示的に扱える。

また、`fetchEventsByIds()`はどこからも呼ばれていない。

### 3.5 ページングとイベント窓の意味が一致しない

- ページングの`until`は`created_at`基準、一覧の並び順は`published_at`優先の`sortTime`基準である。
  編集で`created_at`だけ新しくなった記事は、ページ境界で並び位置が飛ぶ。
- `trimRawEventWindow()`は`LinkedHashMap`の挿入順で間引くが、挿入順は時系列ではない。ローカル公開イベントは
  最後に挿入されるため「最も古い」と扱われず、逆に先頭ページの記事は窓超過時に一覧上部から消える。
  消えた記事は`LazyListState`のインデックスをずらす。
- ユーザータブの記事数は取得済み窓内の件数であり、実際の投稿数ではないが画面上は区別されない。

### 3.6 プロフィール状態をViewModelごとに複製している

各ViewModelが`ArticleListState.profiles`へ取得済みプロフィールを蓄積し、`ArticleItem.authorProfile`にも
コピーしている。

- `profiles`は上限なく増える。
- `ProfileRepository`側でプロフィールが更新されても一覧へ反映されない。
- ローカル公開イベントを受け取るたびに`rawEvents`全体を走査して未取得著者を探す。
- 取得件数が`PROFILE_FETCH_LIMIT`（200）を超えた分は、次のイベント受信まで取得されない。

### 3.7 ViewModelの所有者が画面ごとに異なる

`ArticleDetailScreen`と`ArticleEditorScreen`は`accountSessionViewModel`を使う一方、
`ArticleHubScreen`と`UserArticleListScreen`は`viewModel(key = ...)`と`LocalAccountSession`を使う。
後者のキーにはアカウントが含まれないため、アカウント切り替え後も旧アカウントの`muteStore`を保持した
ViewModelが再利用され得る。また、リレーを切り替えるたびに旧リレー用ViewModelと最大1,000件の
`rawEvents`がナビゲーションエントリ終了まで残る。

### 3.8 ローカル公開の反映先が閲覧リレーとずれる

`ArticleMemoryCache.publishArticle()`は書き込み可能リレー集合で`matches(relayUrl)`を判定する。
記事タブの閲覧リレーが書き込み対象外の場合、自分が公開した記事が一覧に現れない。
これが意図した仕様か未定義であり、コード上の判断根拠も残っていない。

### 3.9 詳細画面の状態と一時イベント

- `deleteCompletedCount`を`StateFlow`に保持し、`LaunchedEffect`で画面を閉じている。
  ステータスタブで解消済みの「一時イベントを永続カウンタで表す」問題と同じ構造である。
- `error`が読み込み失敗と「記事が読み込まれていません」を兼ねており、削除操作の失敗で本文が消える。
- `ArticleMemoryCache.article()`がヒットすると再取得しないため、他クライアントで編集された新しい版を
  表示できない。
- 引用イベントの受信コールバック内で`fetchProfiles()`を直列に待つため、複数引用の反映が遅れる。
- 削除イベントのタグ生成と送信が`ArticleDetailViewModel`に直接書かれ、kind `5`定数も
  `ChannelHiddenMessages`、`ReactionEventStore`と重複している。

### 3.10 画面ファイルの肥大化とComposableに残るロジック

`ArticleScreens.kt`（約1,400行）に、ハブ、ユーザー別一覧、詳細、削除ダイアログ、一覧、カード、
著者行、Markdown解析、Markdown描画、引用プレビュー、スワイプ操作が同居している。

- `parseMarkdownBlocks()`は`MarkdownBody`の再コンポーズごとに実行され、`remember`されていない。
- 詳細本文は`LazyColumn`の単一itemとして全ブロックを描画するため、長文記事で初回描画が重い。
- ユーザータブの「自分／フォロー／グローバル」の振り分けを、再コンポーズごとにComposable内で計算している。
- 自動追加読み込みの条件式（開閉状態とタブの組み合わせ）がComposableに埋め込まれている。
- リレー未選択時の`RelaySelectionPendingContent`分岐が3画面に重複している。
- `articleHubSwipe`は`ServiceTabs.kt`の横スワイプと独立に実装され、しきい値や優先順位が共有されていない。

## 4. 設計原則

1. 記事ドメインの規則（解析、新旧判定、タグ生成、削除表現）をUIから独立したパッケージへ置く。
2. イベント集約・一覧導出・Markdown解析は副作用のない純粋処理とし、common testで固定する。
3. 取得処理は`openSubscription`の`Fetch`挙動を使い、完了／タイムアウト／未応答を結果型で返す。
4. 生イベントの保持、削除済みaddressの記録、表示用リストの導出を分ける。
5. プロフィールは`ProfileRepository`を唯一の情報源とし、表示直前に結合する。
6. ViewModelの所有者はアカウントセッションに統一する。
7. Composableには表示状態と利用者操作だけを残す。
8. 構造整理と仕様変更を同じ変更へ含めない。仕様が変わる箇所は段階を分けて明示する。

## 5. リファクタ後の構造

```text
ArticleHubScreen / UserArticleListScreen / ArticleDetailScreen / ArticleEditorScreen
            |
            v
ArticleListViewModel(query) / ArticleDetailViewModel / ArticleEditorViewModel
            |
            +-- ArticleFeedLoader        (ページング取得)
            +-- ArticleAddressFetcher    (address指定の最新版取得)
            +-- ArticlePublisher         (公開・削除イベント生成と送信)
            +-- ArticleLocalEvents       (ローカル公開・削除の通知)
            |
            v
ArticleEventCodec + ArticleEventReducer + ArticleMarkdownParser
            |
            v
NostrRepository.openSubscription / ProfileRepository / AccountSigner
```

### 5.1 共通ドメイン `com.nostr.torinos.article`

UIから独立した次の要素を置く。ステータスタブの`com.nostr.torinos.status`と同じ粒度にそろえる。

- `ArticleEventCodec`
  - `NostrEvent.toArticleMeta()`、`articleAddress()`を`model/Article.kt`から移す（`model`側は型定義のみ残す）。
  - `buildArticleTags()`、`parseArticleTopics()`、`containsHtml()`をエディタViewModelから移す。
  - 削除イベントのタグ生成（`a`、`e`、`k`、`client`）を置く。
- `ArticleVersionOrder`
  - 同一address内の新旧判定。`created_at`降順、同値ならイベントID昇順を新しい版とする。
  - `latestArticleVersions()`、`withUpsertedArticle()`、`fetchLatestArticleByAddress()`はすべてこれを使う。
- `ArticleEventReducer`
  - 入力: 生イベント（address単位の最新版）、削除済みaddress集合、ミュート判定。
  - 出力: 表示順に並んだ`ArticleItem`一覧と`ArticleAuthorItem`一覧。
  - 差分反映（upsert／remove）と全件再構築が同じ結果になることをテストで保証する
    （既存の`ArticleDifferentialUpdateTest`を移設・拡張）。
- `ArticleMarkdownParser`
  - 現行の`parseMarkdownBlocks()`と`MarkdownBlock`、インライン記法の分解を移す。
  - インライン記法は`AnnotatedString`を直接組み立てず、中間表現（テキスト／強調／斜体／コード／リンク）を返す。
    `AnnotatedString`への変換だけをUI側に残す。
  - 各ブロックに含まれる`nostr:`参照IDと、末尾に出す未参照の引用IDを解析結果に含める。
- `NIP09_DELETION_KIND`を共通定数として1か所に定義し、チャンネル・リアクション側からも参照する。

### 5.2 データアクセス

- `ArticleFeedLoader`
  - `suspend fun loadPage(query: ArticleQuery, until: Long?): ArticlePageResult`
  - `ArticleQuery`は`Global`と`Author(pubkey)`の2種。
  - `openSubscription(SubscriptionSpec(behavior = Fetch(...)))`で取得し、
    `ArticlePageResult`に`events`、`timedOut`、`respondedRelayCount`を含める。
  - 購読IDの採番とセッション終了はローダー内で完結させる。
- `ArticleAddressFetcher`
  - 現行`fetchLatestArticleByAddress()`（`d`タグ指定→著者の直近100件へのフォールバック）を移す。
  - 詳細とエディタが共有する。
- 引用イベント取得は既存の`EventByIdFetcher`を使い、記事専用の`fetchQuotedEvents`・`fetchEventsByIds`は削除する。
- `ArticleLocalEvents`
  - 現行`ArticleMemoryCache`の`SharedFlow`部分を切り出す。
  - `LocalArticleDeletion`を受けた一覧は削除済みaddressへ記録し、以後の取得結果からも除外する。
- `ArticleMemoryCache`は記事と引用イベントのLRUだけを持つ。詳細画面は
  「キャッシュを即時表示し、裏で`ArticleAddressFetcher`で最新版を確認して新しければ差し替える」方式にする。

### 5.3 画面状態

#### `ArticleListViewModel(query)`

`ArticleHubViewModel`と`UserArticleListViewModel`を1つにまとめる。

```kotlin
data class ArticleListState(
    val articles: List<ArticleItem> = emptyList(),
    val authors: List<ArticleAuthorItem> = emptyList(),
    val loadState: ArticleListLoadState = ArticleListLoadState.InitialLoading,
    val canLoadMore: Boolean = false,
)

sealed interface ArticleListLoadState {
    data object InitialLoading : ArticleListLoadState
    data object Idle : ArticleListLoadState
    data object LoadingMore : ArticleListLoadState
    data class Failed(val message: String, val hasPartialResult: Boolean) : ArticleListLoadState
}
```

- `profiles`マップは状態から外し、`ProfileRepository`の観測結果を`ArticleItem`へ結合して導出する。
  プロフィール取得要求は、新しく現れた著者だけを差分で投げる。
- ページングカーソルは`created_at`の最小値を維持する（リレーのフィルタが`created_at`基準のため）。
  並び順とのずれは仕様として明記し、ページ追加で既存項目の相対順が崩れないことをテストで確認する。
- 生イベントの上限は「挿入順」ではなく「`sortTime`が最も古い側」を基準にし、
  上限到達時は`canLoadMore = false`として以後の追加読み込みを止める（表示中の上部を消さない）。
- 自動追加読み込みの判定を`shouldAutoLoadMore(tab, expandedSections, layout)`のような純粋関数にする。
- ユーザータブの「自分／フォロー／グローバル」振り分けを`partitionArticleAuthors(authors, ownPubkey, followed)`
  としてViewModel側（`combine`で`followedPubkeys`と結合）で計算する。

#### `ArticleDetailViewModel`

```kotlin
data class ArticleDetailState(
    val content: ArticleDetailContent = ArticleDetailContent.Loading,
    val markdown: ParsedArticleMarkdown? = null,
    val quotes: Map<String, QuotedEventState> = emptyMap(),
    val deleteState: ArticleDeleteState = ArticleDeleteState.Idle,
)
```

- 読み込み状態（`Loading` / `Loaded(article)` / `NotFound` / `Failed`）と削除状態を分離する。
- Markdown解析結果をViewModelで保持し、再コンポーズで再解析しない。
- 引用は`QuotedEventState`（`Loading` / `Loaded(event)` / `Unavailable`）で個別に持ち、
  プロフィールは`ProfileRepository`から結合する。
- 削除完了は`Channel`由来の一回限りのイベント（`events: Flow<ArticleDetailEvent>`）で画面へ通知し、
  `deleteCompletedCount`を廃止する。

#### `ArticlePublisher`

- 公開と削除のイベント生成、署名者と記事作成者の一致確認、送信、`ArticleLocalEvents`への通知を担う。
- 送信が例外なく完了した場合にだけローカルへ反映する（現行仕様と同じ）。
- `ArticleEditorViewModel.publish()`と`ArticleDetailViewModel.deleteArticle()`はこれを呼ぶだけにする。

### 5.4 画面ファイルの分割

`ArticleScreens.kt`を次へ分割する。公開APIのシグネチャ（`AppSessionCoordinator`からの呼び出し）は変えない。

| ファイル | 内容 |
| --- | --- |
| `ArticleHubScreen.kt` | ハブ画面、セグメント切り替え、スワイプ |
| `UserArticleListScreen.kt` | ユーザー別記事一覧 |
| `ArticleDetailScreen.kt` | 詳細画面、削除ダイアログ |
| `ArticleListContent.kt` | 一覧・著者セクション・空表示・追加読み込み |
| `ArticleCards.kt` | `ArticleCard`、`ArticleAuthorLine`、`ArticleAuthorRow` |
| `ArticleMarkdownContent.kt` | 解析結果の描画、引用プレビュー |
| `ArticleRelayGate.kt` | リレー未選択・未読込時の共通分岐 |

- 詳細本文はMarkdownブロックを`LazyColumn`の個別itemとして描画する（`key`はブロック番号、`contentType`はブロック種別）。
- 3画面のViewModel生成はすべて`accountSessionViewModel`へ統一する。
- 横スワイプは`ServiceTabs.kt`の実装としきい値を共有し、記事内タブ→サービスタブの順に処理する現行の優先順位を維持する。

## 6. 移行手順

各段階は単独でビルド・テストが通り、個別にコミットできる単位とする。
「挙動変更」がない段階では、画面の見た目と操作結果を変えない。

1. **純粋処理の抽出とテスト追加**（挙動変更なし、完了）
   - `ArticleMarkdownParser`を抽出し、現行出力を固定するテストを先に書く。
   - ユーザータブ廃止（0.1）が決定したため、`partitionArticleAuthors`、`shouldAutoLoadMore`の抽出は行わない。
   - 未使用の`fetchEventsByIds()`を削除する。
2. **画面ファイルの分割**（挙動変更なし）
   - 5.4の表に従って移動する。`MarkdownBody`内の解析に`remember(content)`を付ける。
3. **一覧ViewModelの統合**（挙動変更なし）
   - `ArticleListViewModel(query)`を導入し、2つの一覧ViewModelを置き換える。
   - 生成を`accountSessionViewModel`へ統一する（3.7の修正）。
4. **記事ドメインパッケージの導入**（挙動変更: 同時刻バージョンの採用規則）
   - `ArticleEventCodec`、`ArticleVersionOrder`、`ArticleEventReducer`を導入し、NIP-01のタイブレークを適用する。
   - エディタのタグ生成関数を移設し、既存テストの参照先を更新する。
5. **取得処理の置き換え**（挙動変更: 取得失敗の表示）
   - `ArticleFeedLoader`、`ArticleAddressFetcher`を`openSubscription`ベースで実装する。
   - 引用取得を`EventByIdFetcher`へ置き換える。
   - タイムアウト・未応答時は「記事がありません」ではなく再試行可能なエラーを表示する。
6. **削除・公開のローカル反映整理**（挙動変更: 削除済み記事の再表示防止）
   - `ArticlePublisher`と`ArticleLocalEvents`を導入し、削除済みaddressを一覧で保持する。
   - 詳細の`deleteCompletedCount`を一回限りのイベントへ置き換える。
7. **プロフィール結合とイベント窓の整理**（挙動変更: 上限到達時の追加読み込み停止）
   - `profiles`マップを廃止し、`ProfileRepository`の観測へ切り替える。
   - 生イベント窓の間引き基準を変更する。
8. **詳細画面の最新版確認と本文の遅延描画**（挙動変更: 他クライアント編集の反映）
   - キャッシュ表示後に最新版を確認して差し替える。
   - 本文をブロック単位の`LazyColumn`描画へ切り替える。

以下は0章の仕様が決定した後に行う仕様変更の段階である。段階3の完了後であれば、段階4〜8と並行してよい。

9. **ユーザータブの廃止**（仕様変更: 0.1）
   - セグメント切り替えとユーザータブ関連コードを削除し、著者行タップでユーザー別記事一覧を開く。
   - ユーザー別記事一覧にプロフィールヘッダーを追加する。
10. **一覧フィルター**（仕様変更: 0.2）
    - `ArticleQuery(authors, topic)`を導入し、著者チップとトピックチップを追加する。
11. **リアクションとコメント**（仕様変更: 0.4）
    - `NostrFilter`へ`#A`を追加し、記事向けリアクション・コメント（kind `1111`と`a`タグ付きkind `1`）の
      取得と送信を実装する。
    - 表示だけを先に出し、送信は後続コミットに分けてもよい。

## 7. テスト方針

common testで次を固定する。UIテストは追加しない。

- `ArticleVersionOrder`: 新しい`created_at`優先、同時刻はID昇順、受信順を入れ替えても同じ結果。
- `ArticleEventReducer`: 差分反映と全件再構築の一致、削除済みaddressの除外、ミュート除外、著者集計。
- `ArticleMarkdownParser`: 見出し、引用、リスト、コード（フェンス・インデント・未閉じ）、画像、
  インライン強調・斜体・コード・リンク、`nostr:`参照の位置と末尾引用の算出。
- `ArticleFeedLoader`: 偽の`openSession`で、全リレー完了・タイムアウト・0件未応答の結果型を確認する
  （`EventByIdFetcher`のテストと同じ注入方式）。
- `ArticleListViewModel`: `refresh`中の`loadMore`無視、ページ追加時の順序維持、上限到達時の停止、
  ローカル公開・削除の反映。
- `partitionArticleAuthors`、`shouldAutoLoadMore`。
- エディタの既存テスト（`ArticleEditorViewModelTest`）は参照先の移動のみで内容を維持する。

## 8. 未確定事項

- **閲覧リレーが書き込み対象外のときの自分の新規記事表示（3.8）**
  現行どおり非表示にするか、閲覧リレーに関係なく一覧先頭へ仮表示するかを決める。
  決まるまでは段階6でも現行の`matches()`判定を維持する。
- **ユーザータブの記事数表記（3.5）**
  取得済み件数であることを表示で示すか（例: 「12+」）、現行表記のままとするか。
- **生イベント窓の上限値**
  現行の1,000件を維持するか、`cache-performance-refactor-design.md`の上限方針に合わせて見直すか。
- **他クライアント由来のkind `5`削除イベントの反映**
  非目標としたが、段階6の削除済みaddress集合がそのまま受け皿になるため、後続変更の候補とする。
