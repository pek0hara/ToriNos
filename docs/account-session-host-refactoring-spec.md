# アカウントセッション境界リファクタリング仕様

## 1. 文書の目的

本仕様は、アカウント切り替え時の状態初期化を、画面ごとの手動リセットと ViewModel key の変更から、`AccountSession` 単位のライフサイクル管理へ移行するための実装仕様を定める。

上位方針は [`account-session-viewmodel-architecture.md`](./account-session-viewmodel-architecture.md) に従う。本書では、現在の実装から移行するために必要なクラス、責務、処理順序、変更対象および受け入れ条件を具体化する。

## 2. 解決する問題

現在はアカウント切り替え時に、次の仕組みが併存している。

- `App` が `ownPubkey`、プロフィール、投稿シート、返信・引用、ドロワーなどを個別に初期化する。
- `accountStateResetKey` を更新して一部の Compose state を再生成する。
- `accountScopedViewModelKey()` に `sessionId` を含めて ViewModel を再生成する。
- 一部の ViewModel がデフォルト引数から `AccountSessions.manager.currentSession` を取得する。
- アカウント依存 Repository がアプリ全体のシングルトンScopeで動作する。
- `FollowRepository`、`PrivateMuteListStore`、`RelayListSynchronizer` などが処理中に現在の鍵を再取得する。

この構成では、リセット対象の追加漏れ、旧 ViewModel の残留、旧アカウント向け通信結果の反映、切り替え途中での鍵の取り違えを構造的に防げない。

## 3. 完了条件

本リファクタリングは、次の状態になった時点で完了とする。

1. アカウントまたは匿名状態ごとに一意な `sessionId` が発行される。
2. セッションごとに専用の `ViewModelStore`、`CoroutineScope`、アカウント依存 Repository が存在する。
3. セッション終了時に上記リソースと署名権限が一括で無効化される。
4. アカウント依存 ViewModel は `AccountSession` または `AccountContext` をコンストラクタで受け取る。
5. ViewModel およびアカウント依存 Repository が、グローバルな現在アカウントや `KeyStorage` を直接参照しない。
6. `accountStateResetKey`、`accountScopedViewModelKey()`、画面固有のアカウント切り替え停止処理を削除できる。
7. 旧セッションの非同期結果が新セッションの状態へ反映されない。

## 4. 対象範囲

### 4.1 対象

- `AccountSessionManager` のセッション生成・終了処理
- `AccountSessionHost` とセッション専用 `ViewModelStoreOwner`
- セッション内で使用する `AccountContext`
- アカウント依存 CoroutineScope と Repository の所有権
- `App` のセッション依存 UI state とナビゲーション
- アカウント依存 ViewModel の生成方法
- 起動、追加、切り替え、ログアウト、アカウント削除
- セッション切り替え時の署名権限失効

### 4.2 対象外

- 各画面固有の表示デザイン変更
- 公開プロフィール、公開イベント、チャンネル情報など共有キャッシュの再設計
- Nostr の購読プロトコル自体の変更
- 永続キャッシュ形式の全面変更

ただし、アカウント別キャッシュのキーには公開鍵を含める。

## 5. ライフサイクルモデル

### 5.1 セッション状態

公開状態は次の4種類を維持する。

```kotlin
sealed interface AccountSessionState {
    data object Loading : AccountSessionState
    data class Active(val session: AccountSession) : AccountSessionState
    data class Anonymous(val session: AnonymousSession) : AccountSessionState
    data class Switching(
        val fromPubkey: String?,
        val toPubkey: String?,
    ) : AccountSessionState
}
```

匿名状態も `sessionId` を持つセッションとして扱う。これにより、ログアウト時にもナビゲーション、Compose state、匿名閲覧用 ViewModel を同じ仕組みで再生成できる。

### 5.2 AccountSession

```kotlin
data class AccountSession(
    val sessionId: String,
    val context: AccountContext,
    internal val resources: AccountSessionResources,
) {
    val pubkey: String get() = context.pubkey
}

interface AccountContext {
    val sessionId: String
    val pubkey: String
    val signer: AccountSigner
    val followRepository: AccountFollowRepository
    val muteRepository: AccountMuteRepository
    val relaySettingsRepository: AccountRelaySettingsRepository
}
```

ViewModelには、必要な依存だけを個別に渡してもよい。画面から `AccountSessionResources` へ直接アクセスしてはならない。

### 5.3 セッションリソース

```kotlin
internal class AccountSessionResources(
    val scope: CoroutineScope,
    val lease: AccountSessionLease,
    val repositories: AccountRepositories,
) {
    suspend fun close()
}
```

`close()` は冪等とし、次の順序で終了する。

1. `AccountSessionLease` を無効化して新規署名を拒否する。
2. セッション所有の購読を閉じる。
3. セッションScopeをキャンセルする。
4. 実行中ジョブの終了を待つ。
5. アカウント依存 Repository のインメモリ状態を破棄する。

共有キャッシュおよび共有ネットワーク接続は終了対象に含めない。ただし、セッション所有の購読は必ず閉じる。

## 6. 署名権限の管理

`AccountSigner` は `AccountSessions.manager` を直接参照しない。代わりに、生成時にセッション専用の `AccountSessionLease` を受け取る。

```kotlin
internal class AccountSessionLease {
    val isActive: Boolean
    fun ensureActive()
    fun invalidate()
}
```

`sign()`、`encryptToSelf()`、`decrypt()` は処理前に `ensureActive()` を実行する。セッション終了後のSignerは、現在のアカウントが同じ公開鍵であっても再利用できない。

同じアカウントへ再ログインした場合は、新しい `sessionId`、Lease、Signerを生成する。

## 7. AccountSessionManager の責務

`AccountSessionManager` は次の処理の唯一の入口とする。

- 起動時の復元
- 保存済みアカウントへの切り替え
- 鍵追加後のアクティブ化
- ログアウト
- 現在アカウントの削除
- セッションリソースの生成と終了
- 遷移の直列化
- 切り替え失敗時のロールバック

画面および ViewModel から `KeyStorage.switchAccount()`、`logout()`、`deleteKey()` を呼び出してはならない。

### 7.1 切り替え正常系

1. `transitionMutex` を取得する。
2. 切り替え先が現在の公開鍵と同じ場合は no-op で成功を返す。
3. 状態を `Switching` にする。
4. 旧セッションのLeaseを無効化する。
5. 旧セッションリソースを終了し、完了を待つ。
6. `AccountStorage.switchAccount()` を実行する。
7. 切り替え先の資格情報を読み、公開鍵の一致を検証する。
8. 新しい `sessionId`、Signer、Repository、Scopeを生成する。
9. 新アカウントのローカルキャッシュを読み込む。
10. 状態を `Active(newSession)` にする。
11. `AccountSessionHost` が新しい UI と ViewModelStore を生成する。

ネットワーク同期の完了は `Active` への遷移条件にしない。

### 7.2 切り替え失敗時

旧セッションは一度無効化した後に復活させない。

1. 可能であれば `AccountStorage` を元の公開鍵へ戻す。
2. 元アカウントの資格情報を再読み込みする。
3. 元アカウント用の新しい `sessionId` とセッションリソースを生成する。
4. 復元できた場合は `Active(restoredSession)` にする。
5. 復元できない場合は新しい `AnonymousSession` を生成する。
6. 利用者向けの切り替えエラーを通知する。

破棄済みの旧Scope、ViewModel、Signerを再有効化してはならない。

### 7.3 ログアウト・削除

ログアウトと削除でも、最初に状態を `Switching` にして現在セッションを終了する。

- ログアウト成功後、ほかにログイン中の保存済みアカウントがある場合は、そのアカウント用の新セッションを開始する。
- 利用可能なアカウントがない場合は、新しい匿名セッションを開始する。
- 削除後に保存済みアカウントが残る場合は、そのアカウント用の新セッションを開始する。
- 残存アカウントがない場合は、新しい匿名セッションを開始する。
- 失敗時は切り替え失敗時と同じロールバック処理を使用する。

## 8. AccountSessionHost

### 8.1 配置

```text
AppRoot
├── Theme、年齢確認、共有キャッシュ初期化
└── AccountSessionStateHost
    ├── LoadingContent
    ├── SwitchingContent
    └── AccountSessionHost(sessionId)
        └── SessionAppContent
            ├── NavController
            ├── Drawer state
            ├── Composer state
            └── アカウント依存 ViewModel
```

`NavController`、ドロワー、投稿・返信・引用、スクロール位置など、切り替え時に破棄するUI状態は `AccountSessionHost` の内側に置く。

テーマ、年齢確認、公開プロフィールキャッシュなど端末共通または共有状態は外側に置く。

### 8.2 ViewModelStoreOwner

`AccountSessionHost` は `sessionId` ごとに専用の `ViewModelStoreOwner` を生成し、`LocalViewModelStoreOwner` で子Composableへ提供する。

```kotlin
@Composable
fun AccountSessionHost(
    sessionId: String,
    content: @Composable () -> Unit,
) {
    val owner = remember(sessionId) { SessionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        key(sessionId) { content() }
    }
}
```

実装では、旧ownerの `clear()` が一度だけ呼ばれることを保証する。`clear()` により旧セッション配下の全 ViewModel の `onCleared()` が呼ばれる。

### 8.3 Switching表示

`Switching` 中は旧 `SessionAppContent` を操作可能な状態で残さず、全面の進行表示を出す。これにより、Lease失効後のボタン操作や旧状態の表示を防ぐ。

## 9. ViewModel生成規則

### 9.1 必須規則

アカウント依存 ViewModel は、生成時に依存するセッション情報を固定する。

```kotlin
class FeedViewModel(
    private val account: AccountContext?,
    private val subscriptions: FeedSubscriptions,
    private val publisher: EventPublisher,
) : SafeViewModel()
```

次の実装を禁止する。

- デフォルト引数で `AccountSessions.manager.currentSession` を読む。
- `init` または操作時に `loadPublicKey()` を呼ぶ。
- `KeyStorage.loadPrivateKey()` を呼ぶ。
- 操作途中でグローバルな現在アカウントを再取得する。
- ViewModel key に `sessionId` やリセットカウンターを手動追加する。
- 画面から `stopForAccountChange()` のような終了処理を個別に呼ぶ。

### 9.2 ViewModel key

セッション識別は `ViewModelStoreOwner` が担当する。ViewModel keyには同一セッション内で複数インスタンスを区別する情報だけを含める。

例:

- `thread-{eventId}`
- `channel-{channelId}-{relayUrl}`
- `article-{pubkey}-{identifier}`

`sessionId`、アカウント公開鍵、`accountStateResetKey` は含めない。ただし、公開鍵自体が画面の表示対象を識別する場合は含めてよい。

## 10. Repository の分類

### 10.1 セッション所有

次の状態または処理を持つRepositoryは、セッションごとに生成する。

- フォロー一覧と更新
- ミュート、NGワード
- 通知と既読状態
- アカウント別リレー設定
- NIP-65同期
- 自分のリアクション、リポスト照合
- 署名を伴う書き込み
- アカウント専用購読

各Repositoryはコンストラクタで `sessionId`、`pubkey`、Signer、親Scopeなど必要な依存を受け取る。

### 10.2 アプリ共有

次のデータはアプリ全体で共有してよい。

- 公開プロフィールキャッシュ
- 署名済み公開イベントキャッシュ
- チャンネル情報キャッシュ
- URLプレビューキャッシュ
- WebSocket接続プール

共有層は「自分がフォロー中」「自分がミュート中」などのアカウント依存情報を保持しない。

## 11. App の状態移動

現在 `App` にある状態を次のように移動する。

| 状態 | 移動先 |
| --- | --- |
| テーマ、年齢確認 | `AppRoot` |
| `AccountSessionState` の監視 | `AccountSessionStateHost` |
| `NavController`、現在ルート | `SessionAppContent` |
| 投稿・返信・引用・選択メモ・ローカル下書き | `ComposerCoordinator` または `SessionAppContent` |
| プロフィール・通知ドロワー | `DrawerCoordinator` または `SessionAppContent` |
| フィードタブ、スクロール位置 | `SessionAppContent` |
| 自分の公開鍵 | `AccountContext.pubkey` |
| 自分のプロフィール | 共有 `ProfileRepository.observe(pubkey)` から導出 |
| ミュート状態 | セッション所有の `AccountMuteRepository` |

セッション変更時は `SessionAppContent` 自体がCompositionから外れるため、個別代入による初期化を行わない。

## 12. 削除対象

移行完了後、次を削除する。

- `accountScopedViewModelKey()`
- `accountStateResetKey`
- `clearLocalAccountState()`
- `handleAccountChanged(pubkey)` 内の手動状態初期化
- `NotificationsViewModel.stopForAccountChange()` と同種の終了API
- `App` 内の `ownPubkey` の複製状態
- ViewModelコンストラクタの `AccountSessions.manager.currentSession` デフォルト値
- アカウント依存層からの `loadPublicKey()` と `KeyStorage.loadPrivateKey()` 呼び出し

アカウント操作後に画面へ公開鍵を返すコールバックは廃止し、画面は `AccountSessionState` の変化だけを監視する。

## 13. 実装フェーズ

### Phase 1: セッションリソース基盤

1. `AccountSessionLease` を導入する。
2. SignerからグローバルManager参照を除去する。
3. `AccountSessionResources` と生成Factoryを導入する。
4. 匿名状態を `AnonymousSession` としてモデル化する。
5. 正常切り替え、ロールバック、終了順序のManagerテストを追加する。

### Phase 2: UI所有境界

1. `AccountSessionStateHost` を追加する。
2. セッション専用 `ViewModelStoreOwner` を追加する。
3. `NavController` と切り替え時に破棄するCompose stateをHost内へ移動する。
4. `Switching` 中のブロッキング表示を追加する。

### Phase 3: ViewModel移行

次の順序で、明示的な依存注入へ変更する。

1. `NotificationsViewModel`
2. `FeedViewModel`
3. `ThreadViewModel`
4. `ChannelViewModel`、`ChannelListViewModel`
5. 投稿、記事、ライブ、ステータス系 ViewModel
6. プロフィール、設定系 ViewModel

移行済みViewModelから順に、手動セッションkeyと終了APIを削除する。

### Phase 4: Repository移行

1. `FollowRepository`
2. `PrivateMuteListStore` / `MuteStore` / `NgWordStore`
3. `RelayStore` のアカウント依存部分
4. `RelayListSynchronizer`
5. 通知保存とアカウント専用購読

共有キャッシュとセッション所有状態を分離し、セッション終了時に購読とScopeが終了することをテストする。

### Phase 5: 旧方式撤去

1. `accountStateResetKey` を削除する。
2. `accountScopedViewModelKey()` を削除する。
3. `App` のアカウント変更コールバックと手動リセットを削除する。
4. `KeyStorage` 直接参照が境界層だけに限定されていることを検索で確認する。
5. A→B→A、ログアウト、削除を含む回帰テストを実行する。

## 14. テスト仕様

### 14.1 Manager単体テスト

- 起動時にActiveまたはAnonymousになる。
- A→BでAのLeaseとScopeが終了してからBがActiveになる。
- 同一公開鍵への切り替えがno-opになる。
- Bの資格情報検証失敗時、A用の新しいセッションが生成される。
- ロールバック後の `sessionId` が破棄済みAセッションと異なる。
- ロールバックにも失敗した場合はAnonymousになる。
- ログアウトと削除でも旧Leaseが失効する。
- 多重要求が直列化される。

### 14.2 Host/ViewModelテスト

- `sessionId` 変更時に旧 `ViewModelStore.clear()` が一度だけ呼ばれる。
- 旧ViewModelの `onCleared()` が呼ばれる。
- 新セッションで同じViewModel keyを使用しても別インスタンスになる。
- Switching中に旧画面が操作できない。
- 投稿、返信、引用、ドロワー、スクロール位置が新セッションへ残らない。
- テーマと年齢確認状態はセッション変更後も残る。

### 14.3 非同期処理テスト

- Aの遅延取得結果がBのStateFlowへ反映されない。
- AのSignerはSwitching開始後に署名できない。
- Aで開始したキャッシュ保存がBのキーへ書き込まれない。
- Aの購読がBのActive化後に残らない。
- 同じ公開鍵へ再ログインしても旧Signerを利用できない。

### 14.4 UI回帰テスト

- 切り替え成功後はグローバルフィードの先頭を表示する。
- 切り替え中は書き込み操作を開始できない。
- 切り替え失敗時はエラーを表示し、復元した元アカウントを利用できる。
- ログアウト後は次のログイン中アカウントを表示し、利用可能なアカウントがなければ匿名閲覧状態になる。
- アカウント削除後はManagerが決定した次セッションを表示する。

## 15. 静的確認条件

移行完了時に、次の検索結果が0件になることを確認する。

```text
accountStateResetKey
accountScopedViewModelKey
stopForAccountChange
AccountSessions.manager.currentSession  // UI/ViewModel/Repository配下
loadPublicKey()                         // UI/ViewModel/アカウント依存Repository配下
KeyStorage.loadPrivateKey()             // UI/ViewModel/アカウント依存Repository配下
```

`KeyStorage` の利用は `AccountStorage` 実装、鍵追加・インポート処理、明示的な鍵エクスポート処理などの境界層に限定する。

## 16. 受け入れ条件

- A→B切り替えで、A配下の全ViewModel、Scope、購読、署名権限が破棄される。
- Bの画面にAのフォロー、ミュート、通知、編集中状態が表示されない。
- Aの非同期処理がBのUIまたはキャッシュへ結果を反映しない。
- 切り替え失敗時に破棄済みセッションを再利用しない。
- ViewModel生成がセッション専用Ownerと明示的な依存注入に統一される。
- アカウント変更時の手動リセット処理が `App` からなくなる。
- 既存の起動、投稿、閲覧、ログアウト、削除機能に回帰がない。
