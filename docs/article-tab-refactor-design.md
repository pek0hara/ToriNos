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
| 著者 | フォロー（既定） / すべて / 自分 | `authors`なし / フォローリストの公開鍵 / 自分の公開鍵 |
| トピック | 未選択（既定） / 1件選択 | `#t`（`NostrFilter.tTags`） |

- **著者: フォロー** はフォローフィードと同様に、フォロー中の公開鍵をすべて1つのフィルターの`authors`へ入れる。
  フォローが0人のときはリレーへ問い合わせず、結果なしとして扱う。フォローリストが更新されたら読み込み直す。
- **トピック** は、記事カード・詳細画面の`#タグ`をタップすると選択される。選択中のトピックはチップとして表示し、
  チップのタップで解除できる。トピックの自由入力欄は設けない。
- 詳細画面の`#タグ`は、詳細をどこから開いたかに関わらず記事タブへ戻ってそのトピックで絞り込む。
  著者フィルターは直前の選択を維持する。記事タブがナビゲーションのスタックにあればそこまで戻る。
- 著者とトピックは同時に指定できる（AND条件）。
- 未ログイン時はフローティングのセグメントを表示せず、著者は「すべて」に固定する。トピックのチップは表示する。
- ログイン中にアカウントを切り替えた場合、著者フィルターは既定の「フォロー」へ戻す。未ログイン時は「すべて」に固定する。
- 選択状態はリレー切り替え後も維持し、アプリ再起動では既定値へ戻す。
- ユーザー別記事一覧では著者フィルターを表示せず、トピックフィルターだけを使える。
  このトピックはユーザー別記事一覧の画面内だけで保持し、記事タブの選択とは独立させる。

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
- 記事一覧のカードにも、リアクション状態とコメント数を表示する（0.7）。当初は取得コストを理由に
  表示しない決定としていたが、画面に見えている記事だけを対象に取得する方式で見直した。

#### 取得先リレー

記事を取得した記事リレーに加え、自分の読み取りリレーにも問い合わせ、結果をイベントIDで重複除去する。
他クライアントのコメントは記事リレー以外に投稿されていることが多いためである。

### 0.5 決定事項

| 項目 | 状態 | 内容 |
| --- | --- | --- |
| ユーザータブ廃止 | 決定 | 廃止する（0.1） |
| 記事検索 | 決定 | 追加しない（0.3） |
| kind `1`の`a`タグ返信の扱い | 決定 | コメントとして表示する（0.4） |
| 一覧カードへの反応数表示 | 案 | 見えている記事だけ取得して表示する（0.7） |
| 著者フィルターの既定値 | 決定 | ログイン中は「フォロー」（0.2） |
| 記事詳細のリアクション・コメントの配置 | 決定 | 下部バーとコメントシート（0.8） |
| Zap対応 | 対象外 | 本変更では扱わない |
| 著者フィルターの形 | 決定 | フローティングのセグメントを「すべて／フォロー／自分」へ置き換える（0.2） |
| 未ログイン時の著者フィルター | 決定 | セグメントを非表示にし「すべて」に固定する（0.2） |
| 詳細画面の`#タグ`のタップ | 決定 | 記事タブへ戻り、そのトピックで絞り込む（0.2） |

仕様変更の実装時は、`app-requirements.md`の「記事」節とNIP対応表（NIP-22、NIP-23）を同じ変更セットで更新する。

### 0.7 記事一覧のリアクション・コメント表示

本節は設計案であり、表示内容（0.7.1）は利用者の確認後に確定する。

#### 0.7.1 表示

- 記事カードの著者行の右端に、リアクション数とコメント数を並べる。
  - 例: `♡ 12  💬 3`
  - 自分がリアクション済みの場合、ハートの代わりに自分のリアクション（`❤️`や絵文字）を強調色で表示する。
  - コメント数は詳細画面の「コメント N」と同じ、記事へ直接付いたコメントの数とする。
  - 件数0の項目は表示しない。取得前も何も表示しない。
- 著者行の中に置き、カードの高さを変えない。件数が後から届いても一覧の位置がずれない。
- 件数部分のタップは記事詳細を開く（カード本体と同じ）。一覧からはリアクションを送らない。
- 取得件数が上限に達した記事は`99+`のように下限として表示する（0.7.2）。

#### 0.7.2 データ取得

一覧全件ではなく、画面に見えている記事だけを取得する。

- **対象の選び方**: `LazyListState.layoutInfo`を`snapshotFlow`で監視し、可視範囲の記事と直後の3件を対象にする。
  スクロール中は取得を始めず、スクロールが止まって300ms後に未取得・期限切れの記事を集める。
- **バッチ**: 対象を最大20件ずつまとめ、1つの有限購読（`SubscriptionBehavior.Fetch`）で次の4フィルターを送る。
  ```kotlin
  NostrFilter(kinds = listOf(7), aTags = addresses, limit = 500)
  NostrFilter(kinds = listOf(1111), rootAddressTags = addresses, limit = 500)
  NostrFilter(kinds = listOf(1), aTags = addresses, limit = 500)
  NostrFilter(kinds = listOf(7), authors = listOf(ownPubkey), aTags = addresses, limit = addresses.size)
  ```
  - 4本目は自分のリアクションだけを引く。1〜3本目が上限で打ち切られても、自分のリアクション状態は正しく出る。
  - 版IDを`e`タグだけで参照するリアクション（詳細画面の`#e`フィルター）は一覧では取らない。
    フィルターを小さく保つためで、一覧の件数は詳細より少なくなる場合がある。詳細を開くと正確な値で上書きする。
  - 1本のフィルターの受信件数が`limit`に達したら、そのバッチの該当件数を下限扱い（`isLowerBound`）にする。
- **並列度**: 同時に動かす購読は1本だけとし、前のバッチの完了後に次を始める。
- **送信先**: 詳細画面と同じく有効な全リレーへ送る。バッチ化で購読回数を抑える。
- **集計**: 受信したイベントはバッチ内でイベントIDの重複を除いて集計し、件数と自分のリアクションだけを残す。
  判定は詳細画面と同じ`isReactionToArticle`・`isTopLevelArticleComment`を使う。
- **キャンセル**: 記事タブが見えなくなったら、実行中と待機中のバッチを破棄する。

#### 0.7.3 キャッシュと詳細画面との連携

- 集計結果はアカウントセッション単位の`ArticleEngagementSummaryStore`に、addressをキーとして保持する。
  - 上限500件のLRUとし、各要素は取得時刻を持つ。
  - 取得から5分以内なら再取得しない。フィルターやリレーを切り替えても再利用する。
- 詳細画面で取得した結果、リアクションの送信・取り消し、コメント投稿は、このストアへ書き戻す。
  一覧へ戻ると、再取得を待たずに最新の状態が見える。
- 一覧の読み込み直し（`refresh`）では、可視範囲の記事を期限に関係なく取り直す。

#### 0.7.4 UI性能

- 件数は`ArticleItem`や`ArticleListState.articles`に含めない。件数が1件届くたびに一覧全体を作り直し、
  すべてのカードを再描画することを避ける。
- ストアはaddressごとの`StateFlow<ArticleEngagementSummary?>`を返し、各カードは自分の分だけを購読する。
  1件の更新で再描画されるのは、その記事のカードだけである。
- 集計はメインスレッド外（`Dispatchers.Default`）で行い、完了した結果だけをストアへ反映する。
- 著者行の中で横幅だけが変わる配置とし、行の高さとカードの高さを変えない。

#### 0.7.5 テスト

- 集計: 重複除去、上限到達時の`isLowerBound`、自分のリアクションの優先、詳細画面と同じ判定。
- 対象の選び方: 可視範囲と先読み、取得済み・期限内の除外、20件ずつのバッチ分割。
- ストア: LRUの上限、期限切れ判定、詳細画面からの書き戻し。

### 0.8 記事詳細のリアクション・コメントの配置

コメントを見るために本文の末尾までスクロールしなくて済むよう、リアクションとコメントを
画面下部のバーとボトムシートへ移す（決定）。本文末尾に並べていた表示（0.4）は置き換える。

#### 0.8.1 下部バー

- 記事詳細の画面下部に、常に次のバーを表示する。
  ```text
  ┌───────────────────────────────┐
  │ ❤️ 12   ＋   💬 3      コメントする │
  └───────────────────────────────┘
  ```
  - `❤️ 12`: いいねの切り替えとリアクション合計数。自分が絵文字でリアクション済みなら、その絵文字を強調色で表示し、
    タップで取り消す。
  - `＋`: 既存のクイックリアクションメニューと絵文字ピッカーを開く。リアクション済みのときは表示しない。
  - `💬 3`: コメントシートを開く。
  - `コメントする`: 既存の投稿シートを記事へのコメントとして開く（11cと同じ）。
- 本文を下へ読み進めている間はバーを隠し、上へ戻すと再表示する。
- 未ログイン時は件数だけを表示し、`＋`を出さない。`❤️`と`コメントする`は鍵の設定画面へ誘導する（既存の返信と同じ）。
- リアクションとコメントの取得中は件数を空欄にし、取得に失敗した場合は`💬`のシート内で理由と再試行を示す。

#### 0.8.2 コメントシート

- `💬`のタップで、Material 3の`ModalBottomSheet`を半分の高さで開く。引き上げると全画面になる。
- シートの中身は上から順に、リアクションの内訳（`ReactionSummaryRow`）、「コメント N」、コメント一覧（古い順）。
- シートの下端に「コメントを書く…」の欄を固定し、タップで投稿シートを開く。
  文字入力をシート内に持たず、下書き・カスタム絵文字・画像添付は既存の投稿シートに任せる。
- コメント投稿後は、シートを開いたままコメント一覧を取り直す。
- シートを閉じると、本文の読んでいた位置にそのまま戻る。

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
6. 記事画面のViewModel生成を`accountSessionViewModel`へ統一する。
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

### 3.7 ViewModelの生成方法が画面ごとに異なる

`ArticleDetailScreen`と`ArticleEditorScreen`は`accountSessionViewModel`を使う一方、
`ArticleHubScreen`と`UserArticleListScreen`は`viewModel(key = ...)`と`LocalAccountSession`を使う。
ナビゲーション全体が`AccountSessionHost`の`key(sessionId)`配下で再生成されるため、どちらの方法でも
アカウント切り替え時にViewModelは破棄される。差は書き方の不統一だけである。

一方、リレーを切り替えるたびに旧リレー用ViewModelと最大1,000件の`rawEvents`が、サービス画面の
ナビゲーションエントリが終了するまで残る。これは段階3では扱わず、ステータスタブと同様の
可視期間に合わせた購読所有へ見直す際に対応する。

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
6. ViewModelの生成は`accountSessionViewModel`に統一する。
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
2. **画面ファイルの分割**（挙動変更なし、完了）
   - 5.4の表に従って移動する。`MarkdownBody`内の解析の`remember`化は段階1で実施済み。
3. **一覧ViewModelの統合**（挙動変更なし、完了）
   - `ArticleListViewModel(query)`を導入し、2つの一覧ViewModelを置き換える。
     `ArticleQuery`は現時点では`Global`と`Author(pubkey)`の2種とし、0.2のフィルター導入時に一般化する。
   - 生成を`accountSessionViewModel`へ統一する（3.7）。
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

9. **ユーザータブの廃止**（仕様変更: 0.1、完了）
   - セグメント切り替えとユーザータブ関連コードを削除し、著者行タップでユーザー別記事一覧を開く。
   - ユーザー別記事一覧にプロフィールヘッダーを追加する。
10. **一覧フィルター**（仕様変更: 0.2、完了）
    - `ArticleQuery(authors, topic)`を導入し、著者チップとトピックチップを追加する。
11. **リアクションとコメント**（仕様変更: 0.4）
    - 11a（完了）: `NostrFilter`へ`#A`を追加し、記事詳細にリアクション集計とトップレベルのコメント
      （kind `1111`と`a`タグ付きkind `1`）を表示する。取得は`openSubscription`の`Fetch`で有効な全リレーへ送る。
      判定と集計は`article/ArticleEngagement.kt`の純粋関数に置く。
      `ReactionEventStore`のキャッシュ再送が`#a`条件を無視していたため、照合に`#a`を追加した。
    - 11b（完了）: 記事へのリアクション送信と取り消し。`NoteTarget`に任意の`address`と`kind`を追加し、
      指定時だけ`a`・`k`タグを付ける（`e`・`p`は従来どおり）。集計は`NoteEngagementState`へ変換し、
      投稿と同じ`EngagementReducer`で楽観更新・巻き戻しを行う。未ログイン時は表示のみ。
    - 11c（完了）: 記事へのコメント投稿。`ReplyEventReference`に任意の`address`を追加し、指定時は
      `A`/`a`タグを付ける。既存の投稿シートを使い、下書きにもaddressを保存する。投稿後はスレッドへ
      遷移せず、記事詳細のコメント一覧を取り直す。
    - 未決: 記事をルートとするコメントへの返信の表示。既存のスレッド画面はルートがkind `1`の返信しか
      取得しないため、コメントのタップでスレッドへ遷移する仕様（0.4）はスレッド側の対応を含めて別途決める。
    - 表示だけを先に出し、送信は後続コミットに分けてもよい。
12. **記事詳細の下部バーとコメントシート**（仕様変更: 0.8、未着手）
    - 本文末尾のリアクション・コメント表示を、下部バーと`ModalBottomSheet`へ移す。
13. **一覧カードのリアクション・コメント表示**（仕様変更: 0.7、未着手）
    - `ArticleEngagementSummaryStore`、可視範囲のバッチ取得、カードの表示、詳細画面からの書き戻しを実装する。

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
