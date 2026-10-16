# 全体レビューに基づくリファクタリング設計

作成日: 2026-10-16

状態: R1〜R7の主要経路を優先度順に実装・移行済み。実装上の判断、検証結果、残る項目は[実装記録](./refactor-details/implementation-report.md)を参照。

以下のレビュー結果・件数・行番号は実装前の調査記録である。

基準: HEAD `edfc218` と、調査時点の未コミット・未追跡ファイルを含む作業ツリー。

実装単位の型・責務・処理フロー・移行と検証は、[リファクタリング詳細設計](./refactor-details/README.md)に記載する。

詳細設計に対する5回のレビューと19件の指摘反映を完了した。[レビュー記録と受け入れ条件](./refactor-details/review-cycles.md)に結果を記載する。優先順位は維持し、取得結果・入力の寿命・配送と監視の所有権・競合の契約を具体化した。

## 1. 結論

最初に直すべきなのは、有限取得の結果と同期位置を管理する境界である。旧購読APIを使う記事・通知には、最初の1リレーの応答や購読拒否を正常完了と同じ形で扱う経路が残る。

その次に、投稿入力と画面遷移の調停、画面ごとに異なるエンゲージメント集計を整理する。ファイルの大きさだけではなく、利用者データ・取得結果の正しさ、変更の波及、既存の回帰防止手段を基準に選定した。

| 順位 | 優先度 | 対象 | 判断理由 | 作業規模の目安 |
| --- | --- | --- | --- | --- |
| 1 | P1 | 有限取得の結果型・同期位置の統一 | 部分取得や拒否を完了扱いにする経路があり、記事の取りこぼし・通知差分の欠落につながりうる | 中〜大。呼び出し元ごとに移行 |
| 2 | P1 | `AppSessionCoordinator` の入力操作・画面調停の分離 | 保存トランザクション、入力復元、非同期返信解決、ナビゲーションが1つのComposableに集中 | 大。入力操作から段階移行 |
| 3 | P1 | チャンネル・検索のエンゲージメント集計の統一 | フィード・詳細の共有ストアと、独自カウンタ方式が並存し、同一投稿の表示がずれる余地がある | 大。検索、チャンネルの順に分割 |
| 4 | P1 | 通信管理の責務分離とライブ信号の欠落対策 | 全画面で共有する可変状態・排他・送信・再接続・互換APIが集中。ライブ信号の満杯時はログのみ | 大。配信契約の確定を先行 |
| 5 | P2 | `FeedController` の状態更新・表示投影の分離 | 履歴、ライブ補完、保持範囲、表示範囲、集計投影の整合性を1クラスで守っている | 大。既存テストを保って抽出 |
| 6 | P2 | チャンネル作成・編集・送信の分離 | 一覧と詳細がそれぞれ画像アップロード、署名、部分成功、ダイアログ状態を管理 | 中〜大 |
| 7 | P2 | `NoteCard` の表示モデル・構成要素の整理 | 共通カードに多数の引数、描画、メニュー、解析、画像拡大が集まり、画面横断変更が多い | 中 |

P1は次の改善サイクルで着手するもの、P2はP1の契約を定めてから進めるもの。本レビューでは緊急修正を要求するP0は確定していない。規模は相対評価であり、日数の見積もりではない。

## 2. 調査範囲と根拠の読み方

- Kotlinソースの全体構成・行数、直近100コミットの変更対象、既存の設計書・テスト配置を調べた。
- 主に共通コードの通信、アカウントセッション、投稿、フィード、チャンネル、記事、検索、通知、共通カードの主要経路を読んだ。ジャーナル・ステータス・絵文字・キャッシュについては、分離済みの構成と既存設計の導入状況を照合した。
- Android / iOS / Wasmのソースセット、鍵保存の共通契約、ビルド・CI構成も確認した。ただし、プラットフォーム実装の全行レビューや暗号実装の監査ではない。
- 実装コードは425ファイル・77,410行。そのうち `commonMain` は335ファイル・73,072行。`commonTest` は208ファイル。いずれも `.kt` のみで、テストケース数ではない。
- 行番号と件数は調査時点の値。以下の「確認できた事実」と「想定される影響」を区別する。実機再現・性能計測・テスト実行は今回行っていない。
- 既存の作業ツリーには記事下書き、ジャーナル、取得失敗方針などの変更がある。本書はそれらを含めて評価し、既存変更を修正していない。

直近100コミットでは、`AppSessionCoordinator.kt` が16回、`FeedController.kt` と `NostrRepository.kt` が各10回、`NoteCard.kt` が9回変更されている。これは変更の集中を示す補助指標であり、不具合件数ではない。

## 3. 優先度1: 有限取得と同期位置

### 確認できた事実

- [`NostrRepository.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/network/NostrRepository.kt) の `eose()`（1225行付近）は、EOSEとCLOSEDをどちらも `Unit` に変換する。どのリレーが、どう終わったかを呼び出し側が判断できない。
- [`ArticleViewModel.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/article/ArticleViewModel.kt) の `fetchArticleEvents()`（903行付近）は `eose().first()` で待機を終え、イベント一覧だけを返す。複数リレーへの取得でも最初の通知で結果を返し、タイムアウトも結果型に残さない。
- 同ファイルの `fetchQuotedEvents()`（827行付近）は、EOSEまたはタイムアウト後も `awaitCancellation()` で購読を保持する。単発の取得と継続監視の境界が明示されていない。
- [`NotificationsViewModel.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/notification/NotificationsViewModel.kt) の `syncOnce()`（153行付近）は、activityとfollowsでそれぞれ最初の `eose()` を受けると `completed` とし、`followSyncedAt` を保存する。各購読の全リレー成功を確認していない。
- [`ChannelListFetch.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/channel/ChannelListFetch.kt) の `fetchChannelEvents()` は、待ち打ち切り後に1リレーでもEOSEがあれば `true` を返せる。`ChannelController.fetchHistoryPage()` はこの真偽値を履歴ページの `complete` へ渡す。
- `SubscriptionSpec` / `SubscriptionSignal.FetchCompleted` / `RelayOutcome` と、未応答リレーを打ち切る `finishPendingRelays()` はすでに存在する。共通の失敗分類も `RelayFetchPolicy.kt` にある。

### 影響と設計

早いリレーの空結果や拒否で記事取得を終える可能性、未応答リレーがある通知同期でも差分取得の基準時刻を進める可能性がある。チャンネルでは「表示してよい」と「全対象を取得し終えた」が同じ真偽値になる。いずれもコード経路からのリスク評価であり、今回、実機で欠落を再現したものではない。

有限取得の結果を、少なくとも次の情報で返す。

| 情報 | 用途 |
| --- | --- |
| 受信イベントと観測元リレー | 部分結果の即時表示、リレー別の重複排除・集計 |
| 対象リレーのスナップショット | 取得開始後の設定変更と完了判定を分離 |
| リレー別 `RelayOutcome` | EOSE、拒否、未接続、時間切れを区別 |
| 打ち切り理由 | 全応答、表示用の待ち時間終了、期限終了を区別 |
| リレー別の継続位置・取得範囲 | 正常に取得できた範囲だけ同期位置を更新 |

画面は部分結果を表示してよい。一方、同期位置と取得済み範囲は成功を確認したリレーだけ進める。通知の位置を当面1つの時刻で持つなら、必要な全リレーが成功した場合だけ進め、失敗時は以前の位置を維持する。ページ上限に到達した場合は、EOSEが来ても対象期間の全取得とは区別する。

移行は記事の単発取得 → 通知の初回同期 → チャンネル履歴の順とする。引用の継続監視が必要ならLiveとして明示し、単発ならFetch完了時に閉じる。キャンセル時は `finally` と `NonCancellable` で購読を閉じる。既存の `RelayFetchPolicy` を拡張・再利用し、画面固有の表示待ち時間やページングまで一律にしない。

### 完了条件

- 早いリレーが0件、遅いリレーが記事を返す場合に、早い応答だけを全体完了にしない。
- CLOSED、無応答、部分成功、ページ上限到達が正常な全取得と区別される。
- 一部リレー失敗時に通知の同期位置を進めず、再試行で残りを取得できる。
- チャンネルの部分表示で、未取得範囲を取得済みにしない。
- 即時応答・キャンセル・画面終了でイベントと購読枠を取り残さない。

既存の `SubscriptionSessionDeliveryTest`、`ChannelListFetchTest`、`NotificationFollowSyncTest` を利用する。通知の既存テストは主にフィルターとsinceの計算を対象としており、複数リレーの同期完了を確認するテストを追加する。

## 4. 優先度2: アプリ調停と投稿入力

### 確認できた事実

[`AppSessionCoordinator.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/AppSessionCoordinator.kt) は2,145行あり、主要Composableが191〜2024行付近に及ぶ。

その中に、保留入力の復元・破棄、Matometrへの引き継ぎ、`articleDraftStore.transaction`、入力の排他、画面遷移、返信元の非同期解決、秘密鍵要求、ドロワー・シート・タブの管理がある。既存の `ComposerCoordinator` と `AccountSession` で分離した後にも、永続化と状態遷移の一部がComposableに残っている。

### 設計

- 入力復元・保存・引き継ぎを、セッション所有の `ComposerInputCoordinator`（仮称）へ移す。Store操作、排他、開始世代、切り替え中の書き込み防止をここで管理する。
- 返信元の解決と古い結果の破棄を `ReplyComposerResolver`（仮称）へ移す。結果にはセッションと要求世代を結び付ける。
- ルート・フッター・ドロワーの遷移判断を、イベントと遷移結果を扱うロジックへ分ける。`NavController` の操作とComposableの配置はUIに残す。
- すでに存在する `ComposerCoordinator`、`PendingPostInputs`、`AccountSession` を土台とし、同じ入力状態を新しい層へ複製しない。

入力操作を先に移し、その後に画面構成を分割する。Composableから切り出した巨大なクラスに全責務を移すだけでは完了としない。

### 完了条件

入力保存とアカウント切り替えの競合、二重引き継ぎ、復元中の画面終了、返信解決中の別投稿開始を、Composeを起動せずテストできること。既存の `ComposerCoordinatorTest` と `AccountSessionManagerTest`、下書き永続化テストを維持する。戻る操作、簡易投稿から全画面への展開、ドロワーは実機でも確認する。

## 5. 優先度3: エンゲージメントの正本

### 確認できた事実

- フィードと通常の投稿詳細は `EngagementStore` のイベント集合と、セッションごとの `PendingEngagementOperations` から表示を導出する構成になっている。
- [`ChannelController.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/channel/ChannelController.kt) の `onReactionEvent()` / `onRepostEvent()`（1248〜1310行付近）は、既読ID集合と複数のMapを持ち、件数を直接加算する。送信中の操作もチャンネル内で保持する。
- [`SearchEngagementAccumulator.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/search/SearchEngagementAccumulator.kt) は検索専用の既読IDと加算集計を持つ。
- `ReactionEventStore` は通知・キャッシュ再生などで引き続き利用される。記事のアドレス参照、ジャーナルの期間集計も、それぞれ別の契約を持つ。

### 設計

まず検索のkind 1投稿について、取得方式を保ったまま表示集計を共有ストアへ移す。その後、チャンネルのkind 42について、リレー範囲・文脈の分離を確認して移す。共有するのはイベントの同一性、削除、集計、送信中の操作であり、通信先や購読まで全画面で共通にする必要はない。

チャンネルの表示は `noteContext` と対象リレーで絞り、非表示kind 43はチャンネル固有の表示規則として残す。ストアを共有しても、未取得イベントを自動的に取得できるわけではないため、画面間で同じ対象・同じ範囲のイベントがストアへ届いた場合の整合性をまず保証する。

記事はアドレス参照、ジャーナルは期間と取得完了範囲の契約を先に整理する必要があり、この段階で一括統合しない。`ReactionEventStore` も通知と再生の利用先を移す前に削除しない。

### 完了条件

同一イベントの複数リレー受信、削除の先着・後着、検索→詳細→検索、チャンネル→スレッド→チャンネル、送信の成功・失敗を確認する。同じ観測範囲とイベント集合では件数・自分の状態が一致し、画面終了時にretainを解除する。`EngagementStoreTest`、`EngagementSummaryTest`、`CrossScreenEngagementTest` の方式を拡張する。

## 6. 優先度4: 通信管理とライブ信号

### 確認できた事実

`NostrRepository.kt` は1,437行のobjectで、通常・一時接続、購読の状態機械との調停、同時購読枠、再試行、有限取得の完了、キャッシュ投入、送信の受理確認、旧APIをまとめて持つ。`SubscriptionSessionImpl` からもこのobjectを直接呼ぶ。

同ファイル1281〜1302行付近では、ライブ購読に512件のChannelを使い、`trySend()` 失敗時はログだけを出す。有限取得はすでに無制限キューで欠落を防いでおり、同じ問題として扱わない。

### 設計

1. ライブ信号が満杯になったときの契約を先に決める。欠落を検知したら専用状態を残して再取得へつなぎ、復旧通知自体も満杯のキューだけに依存させない。全体の排他中に待機送信して全リレーを止める方式は避ける。
2. 接続の生成・破棄と接続世代、購読レコードと枠の調停、公開送信の受理待ちを、それぞれ独立して検証できる単位へ抽出する。既存の `SubscriptionStateMachine` と `RelayOutbox` を再利用する。
3. UI側には小さい通信インターフェースを渡す。`NostrRepository` は移行期間の窓口として残し、Sessionの終了コールバックも所有者へ注入できるようにする。
4. 旧APIは優先度1の移行後に利用箇所を減らす。通常接続と一時接続の規則をそろえる際も、接続寿命の違いは保持する。

### 完了条件

満杯にしたライブ購読がログだけで取りこぼしを終えず、再同期できること。再接続、購読更新、close、枠待ち、送信受理の競合でも、古い世代のREQを送らず、購読枠を回収すること。`NostrRelayTest`、`SubscriptionStateMachineTest`、`RelaySubscriptionSlotTest`、`TemporaryRelaySubscriptionTest`、`SubscriptionSessionDeliveryTest` を維持・拡張する。

## 7. 優先度5: フィードの更新モデル

[`FeedController.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/feed/FeedController.kt) は1,997行。履歴調停と付随情報取得はすでに別クラスだが、履歴の確定、ライブ復旧、投稿保持、表示窓、リポスト展開、プロフィール・引用、楽観更新、表示状態の公開が残る。

`updateEvents()`（1524行付近）はMutexとrevisionでバックグラウンド計算の巻き戻しを防ぎ、変化があれば再計算する。これは必要な保護であり削除しない。一方、`computeUpdatedFeedState()` は「純粋関数」とするコメントに対して、マッパー、キャッシュ、`ProfileRepository.getCached()` を参照する。入力と外部参照の境界をさらに明示できる。

保持イベントと履歴確定を扱うモデル、表示窓と公開条件を扱うモデル、スナップショットから表示状態を作る投影処理へ順に分ける。プロフィール取得結果も明示した入力にし、投影側で通信・可変フィールドを参照しない。付随情報更新でタイムライン全体の再計算が必要かを測定し、必要な場合のみ差分更新へ進む。

`FeedControllerAsyncRegressionTest` と `FeedRelayMergeGapTest` を維持し、遅いリレー、同秒境界、ライブ補完、削除・ミュート、800件の表示窓の移動を確認する。挙動維持と性能改善は別の変更にする。性能は固定データで更新回数・計算時間・スクロール時のフレーム時間を変更前後で比較し、今回は改善量を推定しない。

## 8. 優先度6: チャンネルの作成・編集・送信

`ChannelController.kt` は1,500行、`ChannelListViewModel.kt` は1,052行。一覧側に作成・削除・お気に入り・未読・活動取得、詳細側にメッセージ履歴・非表示・メタデータ編集・画像アップロード・集計がある。すでに `ChannelHistory`、`ChannelPublishState`、返信関連クラスは分離されている。

次の抽出対象は、一覧側 `createChannel()`（670行付近）と、詳細側 `saveThreadMeta()`（703行付近）の公開処理とする。署名、対象リレー、部分成功、再送、ローカル反映を、UIのダイアログから切り離す。`ChannelPublishState` と `SignedEventPublisher` を利用し、kind 40作成・kind 41更新・最初の発言の意味の違いは保つ。

ダイアログを閉じた後に届くアップロード結果を別の編集へ反映しないこと、部分成功後の再送で受理済みの内容を重複生成しないこと、成功したリレーとローカル反映の対応を確認する。`ChannelPublishStateTest`、`ChannelEditDraftTest`、メタデータ解決テストに加え、公開処理に偽の通信先を注入した統合テストを用意する。

## 9. 優先度7: 共通投稿カード

[`NoteCard.kt`](../composeApp/src/commonMain/kotlin/com/nostr/torinos/ui/components/NoteCard.kt) は2,391行。多数の引数を持つ `NoteCard()`、集計詳細、クイックリアクション、通報、本文解析、引用、画像グリッド・拡大、日時整形が同居する。

先に本文解析と表示モデルを分離し、次に本文、メディア、集計、メニューを部品へ分ける。引数は表示データ・表示方針・操作コールバックのまとまりを定義し、呼び出し側を1画面ずつ移す。単に巨大な引数オブジェクトに置き換えるだけでは完了としない。部品内でアカウントやネットワークへの依存を増やさず、既存の事前解析と表示抑制を保つ。

`ParsedNoteContentArticleQuoteTest`、`NoteCardTimestampTest`、メディア判定テストを維持し、文字サイズ、内容警告、画像複数枚、絵文字・リアクション詳細、ミュート、記事引用を実機確認する。ファイル分割だけで再Composeや描画が速くなるとは判断しない。

## 10. 今回は上位にしない対象

| 対象 | 判断 |
| --- | --- |
| ステータス・絵文字・ジャーナルの全面再分割 | Codec / Reducer / Publisher / Loaderなどの抽出が進んでいる。既存の導入状況と進行中の変更を優先し、古い設計の課題一覧をそのまま再登録しない |
| 記事下書き・自動保存の全面再設計 | 現在の未コミット変更と重なる。まずその変更の境界と検証を維持する。入力操作の調停だけ優先度2で扱う |
| キャッシュ基盤の全面置換 | `ProfileCache` の上限・キー指定購読、`ReactionEventStore` の可変Map化などは現行コードにある。過去のP0表を現状の障害として引用しない。保持上限内の更新コストは別途計測する |
| `StandardEmojiCatalog.kt` | 2,007行あるが主にカタログデータ。行数だけで上位にしない |
| 新規のDM・Zap・署名方式の追加 | 機能追加の計画として既存設計で管理し、今回の構造整理とは分ける |
| 全面マルチモジュール化・DIフレームワーク導入 | 先に寿命と所有者、通信・表示の境界を定める。モジュールを増やすだけでは現在の状態管理の問題は解けない |

## 11. 導入順序と検証

| 段階 | 実施内容 | 次へ進む条件 |
| --- | --- | --- |
| A | 優先度1の再現テスト、結果型、記事・通知・チャンネルの移行 | 部分表示と同期位置更新を分け、拒否・無応答・上限到達を判定できる |
| B | 優先度2の入力操作と返信解決の抽出 | 保存・切り替え・画面終了の競合をUI外で検証できる |
| C | 優先度3の検索、チャンネルへの順次適用 | 同じ観測範囲の集計と送信中状態が画面間で一致する |
| D | 優先度4の配信欠落対策、通信内部の分離 | 再同期・接続世代・枠回収・終了の契約を保つ |
| E | 優先度5〜7の状態・公開処理・描画の整理 | 既存挙動を維持し、性能改善を主張する場合は計測で示す |

これは大きな書き換えを順に行う計画ではない。各段階を呼び出し元・責務ごとの変更に分け、回帰テストが通る状態で統合する。ライブ欠落が再現された場合は、Dの欠落対策だけAと同じ優先度で前倒しする。

変更対象の共通テストに加え、通信・セッション・コルーチンの変更はiOSシミュレータとWasmで検証する。Androidのビルドと、端末の切断・復旧、アカウント切り替え、画面往復を確認する。Web配布ビルドの成功だけを共通テストの成功とは扱わない。

既存の [`regression-test.md`](./regression-test.md) を実機確認に利用する。実装完了後は、確定した境界を下記の恒久設計へ反映し、タスク固有の本書は `docs/README.md` の管理方針に従って削除する。

## 12. 関連設計との対応

- 取得結果・再試行: [`relay-fetch-failure-policy-design.md`](./relay-fetch-failure-policy-design.md)、[`subscription-architecture-design.md`](./subscription-architecture-design.md)
- アカウントと入力の寿命: [`account-session-viewmodel-architecture.md`](./account-session-viewmodel-architecture.md)、[`article-drafts-matometr-design.md`](./article-drafts-matometr-design.md)
- 集計の正本と移行済み範囲: [`engagement-fetch-redesign.md`](./engagement-fetch-redesign.md) の実装状況・残課題
- フィードの保持・表示: [`feed-relay-loading-fix-plan.md`](./feed-relay-loading-fix-plan.md)、[`feed-scroll-performance-design.md`](./feed-scroll-performance-design.md)
- 記事・検索・ジャーナル: [`article-tab-refactor-design.md`](./article-tab-refactor-design.md)、[`post-search-refactor-design.md`](./post-search-refactor-design.md)、[`journal-refactor-design.md`](./journal-refactor-design.md)
- キャッシュ: [`cache-performance-refactor-design.md`](./cache-performance-refactor-design.md)。冒頭の旧評価ではなく、現行コードと導入状況を再確認する

本書はこれらを置き換えるものではなく、現在のコードから抽出した残課題の優先順位と着手範囲を定める。
