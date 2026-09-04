# 通知対象イベントの Kind 非依存取得設計

作成日: 2026-09-04
状態: 通知・プロフィールのリアクション一覧へ初版を実装済み。a タグのみの解決など、別段階とした機能は未実装。

## 1. 決定事項

イベント ID で通知対象を取得するときは Kind を指定しない。取得できたイベントの Kind を使って、プレビューと遷移先を決める。

「取得できない」と「取得できたが専用画面がない」は別の状態にする。どちらも無期限の「読み込み中」にしない。

今回の対象は通知の参照先取得・表示・遷移。通知そのものの購読 Kind、タイムラインの対象 Kind、各詳細画面の返信購読 Kind は維持する。Kind 42 の返信通知や Kind 16 の通知追加は別件とする。

## 2. 現行実装との対応

- `ui/notification/NotificationsViewModel.kt`: 対象取得で `kinds = listOf(1)` を指定し、受信時も Kind 1 以外を破棄している。
- `ui/notification/NotificationsDrawer.kt`: 対象が null なら理由を問わず「対象ポストを読み込み中」。タップ時は ID だけを渡す。
- `AppSessionCoordinator.kt`: 通知から `ThreadRoute(eventId)` に遷移するため、チャンネル情報が渡らず通常ポストの詳細になる。
- `ui/profile/MyProfileReactionsViewModel.kt`: 同じ Kind 1 固定処理がある。共通取得処理の次の適用先とする。
- `ui/post/JournalController.kt`: `fetchReferencedContentNow` はすでに ID 指定・Kind 制限なし。Kind 制限撤廃のための変更は不要。
- `network/SubscriptionSession.kt`: 有限取得、リレー別完了理由、タイムアウトを扱える。独自の EOSE 待機機構を新設しない。

## 3. 責務分割

新設する名前は仮称とする。

1. `NotificationTargetResolver`: 通知イベントから対象参照を抽出する純粋関数。
2. `EventByIdFetcher`: ID 集合を Kind 非依存で有限取得する。通知、画面遷移、プロフィールの知識は持たない。
3. `NotificationsViewModel`: 取得キュー、対象ごとの状態、再試行、画面のライフサイクルを管理する。
4. `NotificationTargetPresentation`: 取得済みイベントから安全なプレビューと型付き遷移先を決める純粋関数。
5. `AppSessionCoordinator`: 型付き遷移先を既存 Route に変換する。

取得処理に「対応 Kind 一覧」を持たせない。対応 Kind 一覧は表示・遷移層だけに置く。

## 4. 対象参照の解決

### 4.1 ID 参照

- Like は最後の `e` タグを対象とする現行の選択規則を維持する。64 桁の hexadecimal ID であることを検証する。
- 最後の対象タグが不正な場合、前の `e` タグへ勝手に戻らず、参照不正とする。
- Reply の対象判定は返信用の既存ヘルパーを利用し、Like と共通の「最後の e」へ無条件に集約しない。
- Repost は参照 ID と埋め込みイベントを照合する。ID がない場合の埋め込みフォールバックは、イベント検証に成功した場合のみ利用する。
- `k` タグがあっても ID 取得フィルターの `kinds` には転用しない。取得後の Kind はイベント本体を正とする。
- 複数通知が同じ ID を参照する場合は一度だけ取得し、結果を共有する。

参照抽出結果は `EventId(id)` / `AddressOnly(rawAddress)` / `InvalidReference` / `NoReference` に分ける。Follow は対象参照なしが正常で、エラー表示しない。

### 4.2 a タグの境界

今回の実装では `a` タグだけの参照をネットワーク解決する機能は追加しない。`AddressOnly` と認識し、「この参照形式は未対応」と表示する。読み込み中にも、不正な ID 検索にも進めない。

有効な `e` と `a` が両方ある場合は、通知が指す特定イベント ID を優先する。ID が見つからないからといって同じアドレスの最新版へ黙って置換しない。

将来アドレス解決を追加する場合は別 API とし、Kind・作者・識別子をアドレスの構成要素として指定する。この場合の Kind 指定は「Kind 1 への制限」ではなく、対象を特定する条件である。最新版と通知当時の版が異なり得るため、ID 解決結果と区別する。

## 5. Kind 非依存の有限取得

既存 API を使う概念例:

```kotlin
val session = repository.openSubscription(
    SubscriptionSpec(
        id = uniqueBatchId,
        filters = listOf(
            NostrFilter(ids = requestedIds.toList(), limit = requestedIds.size),
        ),
        target = RelayTarget.AllEnabled,
        behavior = SubscriptionBehavior.Fetch(timeoutMillis = 5_000),
    ),
)
```

- `kinds = null` のデフォルトを使い、送信 JSON に `kinds` キーを含めない。空リストは指定しない。
- ID の空集合では購読を開始しない。作者・日時などの余計な制約も追加しない。
- `SubscriptionSignal.Event` は要求 ID 集合との完全一致を確認する。リレーが返した無関係なイベントは破棄する。
- ネットワーク受信・埋め込み・再利用キャッシュとも、イベント ID と署名の検証を満たすイベントだけ採用する。既存 `isValidEvent` を利用し、メインスレッドを塞がない。基盤で検証済みと保証できる経路は重複検証を省略できる。
- 正常な未知 Kind も取得成功として保持する。同じ ID のイベントは重複排除する。
- 実装では対象取得セッションだけ `deduplicateEvents = false` を指定し、Fetcher が署名検証後に重複排除する。署名不正の先着応答が、別リレーからの正常応答を基盤側で抑止しないためである。他の購読の重複排除は維持する。
- 有効な結果は到着次第通知する。遅いリレーの終了までカード表示を待たせない。
- 全要求 ID が揃った場合は早期終了してよい。未解決 ID が残る場合は `FetchCompleted` まで待つ。最初のリレーの EOSE だけでは終了しない。
- 終了・例外・キャンセル時は `finally` でセッションを close する。キャンセル下でも解放が完了する構造とする。

Fetcher は取得イベントの逐次更新と、未解決 ID に対する終了理由を返す。実装時は購読インターフェースを注入し、リレーを使わないテストを可能にする。

### 5.1 未解決 ID の終了理由

- 対象リレーが 1 件以上あり、全対象が EOSE: `NotFoundInQueriedRelays`。
- リレーが 0 件: `Unavailable(NoRelay)`。
- 一部でもタイムアウト、CLOSED、接続不能がある: `Unavailable`。正常に完了した他リレーの有無も診断情報として保持する。
- 要求 ID と同じでも検証不正のイベントしか届かなかった: `Unavailable(InvalidResponse)`。存在しないと断定しない。
- 呼び出し元キャンセル: 未解決分は `Idle` に戻す。検索完了扱いにしない。

一部 ID を取得できていれば、それらの `Resolved` は維持する。バッチ全体の失敗で成功済みカードを消さない。「見つからない」は今回問い合わせたリレーでの結果であり、削除済み・世界中に存在しないという意味にしない。

## 6. 状態とライフサイクル

ID ごとに次の状態を保持する。

```kotlin
sealed interface TargetLoadState {
    data object Idle : TargetLoadState
    data object Loading : TargetLoadState
    data class Resolved(val event: NostrEvent) : TargetLoadState
    data object NotFoundInQueriedRelays : TargetLoadState
    data class Unavailable(val reason: TargetFetchFailure) : TargetLoadState
}
```

参照なし・参照不正・a タグのみは参照抽出結果で管理し、ID の状態 Map に偽のキーを作らない。未知 Kind は `Resolved` のままで、表示層が専用画面未対応と判断する。

### 6.1 キューと購読数

- 現在の最大通知数 100 件に合わせ、初期値はバッチ最大 50 ID、同時取得最大 2 バッチ、集約待ち 400 ms とする。
- `pending`、`inFlight`、`resolved` を区別する。取得開始時に pending から inFlight へ移す。
- 集約待ちは先頭の追加から最大 400 ms とし、通知の連続到着で取得開始が無期限に延びないようにする。
- 実行中のバッチは ID 集合を固定する。後着 ID は次のバッチへ積み、進行中の取得をキャンセル・上書きしない。
- セッション ID は ViewModel インスタンス識別子と連番で一意にする。現在の固定 `notif-target-*` の使い回しを廃止する。
- ViewModel の状態更新は直列化する。セッション世代を付け、終了した画面や旧アカウントからの遅延結果を無視する。

### 6.2 開閉と再試行

- 起動時の通知同期と、対象イベント取得の完了は別々に扱う。活動通知の EOSE 後 1 秒で対象取得を一括終了しない。
- 起動時に開始した対象取得には有限取得の期限まで待つ独立した所有権を与える。通知ドロワーを開いた場合は既存取得を共有し、二重起動しない。
- 起動時同期の所有権もドロワー表示の所有権もなくなった場合、pending を停止し、inFlight を close して未解決分を Idle に戻す。
- 再オープン時は Idle を取得する。失敗済みは最終試行から 30 秒以上経過した場合に一度だけ再試行する。表示中の自動ポーリングはしない。
- カードの「再試行」は待機時間を迂回できるが、inFlight の重複取得はしない。
- 成功結果は ViewModel 内で共有し、現在の通知が参照しなくなった ID は解放する。失敗結果は永続化しない。
- 初版では新しい永続キャッシュや任意リレー探索を追加しない。通知内のリレーヒントへの自動接続も行わない。

## 7. 表示と遷移

### 7.1 状態ごとの UI

| 状態 | 表示・操作 |
| --- | --- |
| Idle | 対象を取得待ち。必要に応じて取得開始 |
| Loading | 対象を読み込み中 |
| NotFoundInQueriedRelays | 接続先のリレーでは対象が見つかりませんでした／再試行 |
| Unavailable | 対象を取得できませんでした／再試行 |
| InvalidReference / NoReference | 対象への参照を確認できません |
| AddressOnly | この参照形式は未対応 |
| Resolved | Kind に応じたプレビューと操作 |

失敗中・未取得の対象を通常の `ThreadRoute` に渡さない。ただし Reply の「返信自体を開く」は通知に保持している返信イベントへの既存導線なので、親イベントの取得失敗とは分離して維持する。

### 7.2 取得済みイベントの扱い

| 対象 Kind | プレビュー | タップ先 |
| --- | --- | --- |
| 1 | 投稿本文 | `ThreadRoute(eventId)` |
| 42 | チャンネル本文 | 有効な `channelRootId()` を取得できればチャンネル用 `ThreadRoute(eventId, source = channel, channelId)` |
| 30023 | 既存 Article メタデータのタイトル・要約 | 既存パーサーで識別子を解決できれば `ArticleRoute(pubkey, identifier)` |
| 30311 | 既存 Live メタデータのタイトル | 必須情報を解決できれば `LiveRoute(pubkey, identifier)` |
| 1311 / 30315 / その他 | Kind・作者・日時を含む汎用カード | 初版は専用画面なし。イベント ID コピーのみ |

Kind 42 のチャンネル ID 不明、記事・ライブのメタデータ不正も汎用カードに落とす。Kind 1 へフォールバックしない。Live チャットを配信画面へ紐付けて開く機能は初版に含めない。

未知 Kind の `content` は JSON、暗号文などの可能性があるため、無条件に本文表示・HTML 解釈・URL 読み込みをしない。既知 Kind のプレビューも既存の安全なテキスト表示処理を利用する。

記事・ライブの既存 Route は作者＋識別子で開くため、通知が指す版ではなく最新版を表示し得る。カードは取得した対象イベントの内容を表示し、操作は「記事の最新版を開く」「ライブを開く」と区別する。今回、過去版ビューアーは新設しない。

`onOpenThread(String)` だけで全 Kind を処理せず、`onOpenTarget(NotificationTargetDestination)` に変更する。遷移先は Thread / ChannelThread / Article / Live を型で表し、汎用カードには専用遷移先を作らない。

## 8. 保存互換性と展開順

- 既存 `NotificationItem.targetEventId` と保存 JSON は維持する。新しい参照抽出結果・取得状態は保存済み `event` から再構築する。
- 旧データで `event` がない場合のみ既存 `targetEventId` を検証して利用する。参照を持たない Follow はそのまま復元する。
- 既存の `targetEvents` は互換アクセサーとして新しい状態 Map の `Resolved` から導出できる。両方を独立に更新する二重管理は避ける。
- 取得済みの通知イベント数、既読状態、ミュート設定は変更しない。共有対象イベントのキャッシュには、表示許可の判断を持ち込まない。

推奨実装順:

1. Kind 非依存の Fetcher、参照 Resolver、状態遷移の単体テストを追加。
2. 通知 ViewModel の固定 Kind・固定対象購読を置換し、有限取得・再試行を導入。
3. 通知カードと AppSessionCoordinator に Kind 別表示・型付き遷移を導入。
4. 同じ制限を持つプロフィールのリアクション一覧へ共通処理を適用。通知修正とは分けて検証する。
5. 必要に応じて別タスクで a タグの解決、1311 の専用導線、リレーヒント探索を追加。

全リポジトリの `kinds = listOf(1)` を一括削除しない。ThreadController は渡された NoteContext に従う既存動作を維持する。

## 9. 受け入れテスト

1. 要求 JSON に `ids` があり `kinds` がない。空 ID では REQ を送らない。
2. Kind 1 / 42 / 30023 / 30311 / 1311 / 30315 / 未知 Kind を同じバッチで受け取り、すべて Resolved になる。
3. 要求外 ID、ID 改ざん、署名不正を採用しない。k タグの有無や対象 Kind との食い違いで有効な ID 取得を止めない。
4. 複数リレーの重複は一度だけ反映する。先着リレーに対象がなくても後着リレーの対象を表示する。
5. 全 EOSE・一部タイムアウト・CLOSED・接続不能・リレーなしを区別する。部分成功を保持する。
6. 全件受信時に早期 close、キャンセル時に必ず解放。終了後の結果で状態を戻さない。
7. 同一 ID の複数通知は重複取得しない。取得中の新規通知で旧バッチが失われない。連続到着でも取得開始する。
8. 起動時同期完了後も対象取得の期限が確保される。開閉・アカウント切り替えで取り残された Loading がない。
9. 再試行で未解決から Resolved へ遷移できる。失敗の自動再試行を無限に繰り返さない。
10. Kind 42 は channelId を渡す。未知 Kind やメタデータ不正を Kind 1 の ThreadRoute に渡さない。
11. 記事・ライブの版の扱いを表示で区別する。未知 Kind の content を無条件に描画しない。
12. e タグなし・不正 e・a のみ・e と a の併存・埋め込み対象の ID 不一致を検証する。
13. 保存済み通知、既読、ミュート、Reply 本体を開く導線に回帰がない。

テストは Fake SubscriptionSession と仮想時間を用い、実リレーのデータや応答速度に依存させない。UI テストでは通知カード表示と Route の引数まで確認する。
