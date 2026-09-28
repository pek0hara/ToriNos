# Web 版（Kotlin/Wasm）秘密鍵ログイン・投稿 設計

作成日: 2026-09-28
状態: W1〜W3 を実装済み（2026-09-28）。実リレーへの投稿は未確認。実装で設計から変えた点は8章。

## 0. 現状

- Web ターゲットは無い。以前の wasmJs 対応は `7a6888b`（2026-08-21）で削除された。
  当時の Web 実装は閲覧専用で、`Crypto` の actual はすべて `UnsupportedOperationException`、`KeyStorage` は何も保存しない空実装だった。
- 署名・公開鍵導出・NIP-44 の ECDH は `secp256k1-kmp`（ACINQ）に依存している。
  このライブラリは JVM / Android / iOS 向けだけを配布していて、JS / Wasm 版は無い。
- 秘密鍵を使うのは `AccountSession.kt` の `PrivateKeyAccountSigner` と `KeyStorageAccountStorage`、
  それに `KeySetupScreen` だけ。どれも共通コードなので、Web で必要なのは expect の actual 実装になる。
- `AccountSigner` は同期 API（`sign` / `encryptToSelf` / `decrypt`）。
  Web でも秘密鍵を Kotlin 側に持つ限り、同期のまま実装できる。
- NIP-44 の HKDF・HMAC・ChaCha20 は `Nip44.kt` の Kotlin 実装なので、プラットフォームへの依存は `sha256` と `secp256k1PublicKeyTweakMul` だけ。

## 1. 目的

- ブラウザで nsec（または hex）を入力してログインし、kind 1 の投稿・リアクション・フォローなど、既存の書き込み機能をそのまま使えるようにする。
- 新規の鍵生成と、複数アカウントの保存・切り替え・ログアウト・削除も、Android / iOS と同じ動きにする。
- 共通コードの設計（`AccountSigner` など）は変えず、Web の差分は `wasmJsMain` の actual に閉じ込める。

## 2. 決定事項

### D1. 署名は Kotlin 側に秘密鍵を持ち、`@noble/curves` で行う

| 案 | 採否 | 理由 |
| --- | --- | --- |
| `@noble/curves` / `@noble/hashes` を npm 依存にし、Kotlin/Wasm の JS 相互運用で呼ぶ | **採用** | 監査済みの純 JS 実装。同期 API なので既存の `expect fun` をそのまま実装できる |
| secp256k1 を Kotlin で自前実装する | 不採用 | 定数時間性の確保と検証コストが大きい |
| WebCrypto | 不採用 | secp256k1 に対応していない。`subtle.digest` は非同期で、同期 API の `sha256` に合わない |
| NIP-07（ブラウザ拡張） | 今回は範囲外 | `AccountSigner` の suspend 化が必要。将来の追加候補（7章） |

- バージョンは `2.4.0` に固定する（`npm("@noble/curves", "2.4.0")`）。v2 は ESM 専用で、import パスは `@noble/curves/secp256k1.js` と `@noble/hashes/sha2.js`。
- 乱数は `crypto.getRandomValues`。
- Schnorr 署名の aux_rand は **32バイトのゼロ** を渡す。
  Android / iOS は `Secp256k1.signSchnorr(data, key, null)` で、libsecp256k1 は null をゼロとして扱う。
  そのため全プラットフォームで同じ入力から同じ署名になり、BIP340 のベクタで照合できる。
- `secp256k1PublicKeyTweakMul` は `secp256k1.Point.fromBytes(pub).multiply(tweak)` の圧縮形式（33バイト）を返す。
  スカラーの BigInt 変換は JS 側で行う（`js()` のインライン関数）。

### D2. 秘密鍵はブラウザの `localStorage` に保存する

- 保存キーは Web 専用の接頭辞 `torinos_web_` を付ける。
  - `torinos_web_accounts`: pubkey のカンマ区切り
  - `torinos_web_active_account`: アクティブな pubkey
  - `torinos_web_logged_out_accounts`: ログアウト済みの pubkey
  - `torinos_web_private_key_<pubkey>`: 正規化済み hex 秘密鍵
- 暗号化はしない。WebCrypto の抽出不可鍵で暗号化しても、同じオリジンで XSS が起きれば復号関数ごと使われるので、効果に対して複雑さが見合わない。
- リスク（XSS、悪意ある拡張機能、共有PC）への対策:
  - `index.html` に CSP を置き、スクリプトは自オリジンだけにする（`script-src 'self' 'wasm-unsafe-eval'`）。外部スクリプトは読み込まない。
  - 同意ダイアログとアカウント削除の文言で、秘密鍵が「このブラウザ」に保存されることを明示する。
  - ログアウトは既存と同じく鍵を残す（再ログイン用）。完全に消すには既存の「保存済みアカウントを削除」を使う。
- 保存ロジック（アカウント一覧・アクティブ・ログアウト済みの整合性）は iOS の `KeyStorage` と同じ規則にする。
  テストしやすいように、`localStorage` を差し替えられる `BrowserKeyStore(store: KeyValueStore)` に実装し、
  `actual object KeyStorage` はそれに委譲する。

### D3. Web の書き込み可否と表示分岐

- `isWriteSupported = true`。
- 文言の分岐のため `expect val isWebPlatform: Boolean` を追加する（Android / iOS は false）。
  使う場所は `KeySetupScreen` の同意ダイアログとアカウント削除ダイアログ。

### D4. Web で対応しない機能（スタブ）

今回の目的（ログインと投稿）に不要なものは、落ちない空実装にする。

| expect | Web の実装 |
| --- | --- |
| `rememberImagePickerLauncher` / `rememberOptimizedImagePickerLauncher` / `rememberClipboardImageReader` | 何もしない（画像添付は未対応） |
| `readImageDimensions` / `cropImageForUpload` | null / 入力をそのまま返す |
| `PlatformMediaPlayer` / `XPostEmbed` | 何も表示しない（呼び出し側のリンク表示が残る） |
| `rememberPasswordManagerSaver` | 何もしない（生成画面の nsec 表示とコピーで保管してもらう） |
| `dismissPlatformKeyboard` / `*BackHandler` | 何もしない |
| `deleteLegacyChannelCacheDatabase` | 何もしない |
| `Clipboard.setPlainText` | `navigator.clipboard.writeText`（nsec のコピーに必要なので実装する） |
| `LocalSettingsStorage` | `localStorage` |
| `SynchronousLock` | 何もしない（Wasm はシングルスレッド） |
| `platformLog` | `console.log` |
| `createHttpClient` | `HttpClient(Js) { install(WebSockets) }` |

ブラウザの CORS 制約で、リンクプレビューなど任意サイトへの HTTP 取得は失敗することがある。今回は対応しない。

### D5. 画像ローダー

`registerAppImageLoader` はディスクキャッシュ（okio のファイルシステム）を使うので、Web では呼ばず Coil の既定シングルトンを使う。
`ImageLoaderConfig.kt` が wasmJs でコンパイルできない場合は、ファイルを `mobileMain`（Android と iOS の中間ソースセット、既存）へ移す。

### D6. テスト

- `commonTest` に暗号の公式ベクタテストを追加する。Android host / iOS シミュレータ / wasmJs のすべてで同じテストを通すことで、noble 経由の実装を libsecp256k1 と照合する。
  - BIP340 `test-vectors.csv`: aux_rand がゼロの行は署名の完全一致、全行で検証結果の一致、公開鍵導出の一致。
  - NIP-44 `nip44.vectors.json`: `get_conversation_key` と `encrypt_decrypt` の一部。
  - `signEvent` の往復（署名 → id 再計算 → `schnorrVerify`）。
- `BrowserKeyStore` のテストは `wasmJsTest` に置き、メモリ上の `KeyValueStore` で動かす。
- wasmJs のテストはブラウザ（Karma + ChromeHeadless）で実行する。

## 3. 構成

```text
composeApp/src/
  commonMain/  crypto/Platform.kt に isWebPlatform を追加
  commonTest/  crypto/CryptoVectorsTest.kt, crypto/Nip44VectorsTest.kt
  wasmJsMain/
    kotlin/main.kt                          ComposeViewport(document.body) { App() }
    kotlin/com/nostr/torinos/crypto/
      Noble.kt                              @JsModule の external 宣言と ByteArray⇔Uint8Array 変換
      Crypto.wasmJs.kt                      expect fun の actual
      BrowserKeyStore.kt                    保存ロジック（KeyValueStore に依存）
      KeyStorage.wasmJs.kt                  BrowserKeyStore(LocalStorageKeyValueStore) へ委譲
      Platform.wasmJs.kt / PasswordManagerSaver.wasmJs.kt
    kotlin/... D4 の各 actual
    resources/index.html                    CSP 付き
  wasmJsTest/  crypto/BrowserKeyStoreTest.kt
```

Gradle:

```kotlin
@OptIn(ExperimentalWasmDsl::class)
wasmJs {
    outputModuleName = "composeApp"
    browser { commonWebpackConfig { outputFileName = "composeApp.js" } }
    binaries.executable()
}
// sourceSets
val wasmJsMain by getting {
    dependencies {
        implementation(libs.ktor.client.js)
        implementation(npm("@noble/curves", "2.4.0"))
        implementation(npm("@noble/hashes", "2.4.0"))
    }
}
```

## 4. 段階（Loop）

各段階で「実装 → 差分レビュー → テスト・ビルド」を回し、通ったらコミットする。
各段階で `:composeApp:allTests`、`compileAndroidMain`、`compileKotlinIosSimulatorArm64`、`wasmJsBrowserDistribution` を通す。

- **W1: Web ターゲットの復活（閲覧専用）**
  wasmJs ターゲット、`main.kt`、`index.html`、D4 のスタブと `LocalSettingsStorage` / HTTP を追加する。
  `Crypto` は仮に例外を投げ、`isWriteSupported = false`、`KeyStorage` は空実装にする。
  確認: ビルドが通り、ブラウザでフィード（匿名閲覧）が表示される。
- **W2: noble による暗号実装とベクタテスト**
  `Noble.kt` と `Crypto.wasmJs.kt` を実装し、`commonTest` にベクタテストを追加する。
  確認: 3プラットフォームでベクタテストが通る。
- **W3: ブラウザの鍵保存とログイン**
  `BrowserKeyStore`、`KeyStorage.wasmJs.kt`、`isWebPlatform`、文言分岐、CSP、クリップボード、`isWriteSupported = true`。
  確認: `BrowserKeyStoreTest` が通る。ブラウザで鍵生成とログインができ、再読み込み後もログインが続き、ログアウト・切り替え・削除ができる。
- **W4: 投稿の確認と文書の更新**
  ブラウザで投稿画面から署名済みイベントが作られることを確認する。
  実リレーへの送信は公開操作なので、ユーザーの了承を得てから行う。
  `app-requirements.md` の対応プラットフォームと未確定事項、`docs/README.md` を更新し、本書に実装結果を追記する。

## 5. 範囲外

- NIP-07 / NIP-46 による外部署名。
- 画像添付・アップロード、動画再生、X 埋め込み、パスワードマネージャ連携。
- Web 版の公開（ホスティング、ドメイン、CI）。
- プッシュ通知。

## 6. 未確定事項

- Web 版を公開する場所（GitHub Pages の `docs/site` と同居させるか、別ドメインにするか）。CSP の最終形はここで決まる。
- 秘密鍵を保存しない「このセッションだけ」ログインを選べるようにするか。

## 7. 将来の拡張

- NIP-07 対応: `AccountSigner` を suspend にしたうえで、`window.nostr` に委譲する実装を追加する。
  秘密鍵をブラウザに保存しないで済むので、Web 版の既定のログイン方法にする候補。

## 8. 実装で設計から変えた点と検証

### 8.1 変更点

- **W1 の確認範囲**: `RelayMessage` が受信イベントをすべて `isValidEvent` で検証するため、暗号が未実装の W1 ではフィードが空になる。フィード表示の確認は W2 へ移した。
- **テストの置き場所**: `runBlocking` と実スレッドを前提にするテスト8件（`AccountSessionManagerTest` など）は Wasm でコンパイルできない。
  挙動を変えないよう書き換えずに、Android / iOS 用の中間ソースセット `mobileTest` へ移した（iOS シミュレータでは従来どおり実行される）。
- **タイムゾーン**: kotlinx-datetime の Wasm 実装は名前付きタイムゾーンの DB を同梱しないため、`@js-joda/timezone` 2.3.0 を追加した
  （kotlinx-datetime が使う `@js-joda/core` 3.2.0 に合う版）。無いと `Asia/Tokyo` などで例外になる。
- **画像ローダー**: `ImageLoaderConfig.kt` を `mobileMain` へ移した。Web は Coil の既定シングルトンを使う。
- **CSP**: Coil などが blob URL から Worker を作るため `worker-src 'self' blob:` を追加した。
  開発ビルドの webpack が `eval` を使うソースマップを出さないよう、`devtool = "source-map"` にした。
- **文言**: `isWebPlatform` を使う箇所に、ログアウトとアカウント完全削除の確認文（「このブラウザに残ります」など）も加えた。
  保存済みアカウント削除の文言は `KeySetupScreen` と `SettingsScreen` で重複していたため `KeyStorageMessages.kt` にまとめた。
- **iOS の修正**: ベクタテストで、iOS の `sha256(ByteArray(0))` が範囲外アクセスで落ちることが分かったので直した（アプリ内に空データをハッシュする経路は無かった）。

### 8.2 検証

次のコマンドが成功した（iOS シミュレータ 819件、wasmJs（ChromeHeadless）798件）。

```text
./gradlew :composeApp:allTests \
  :composeApp:compileAndroidMain \
  :composeApp:compileKotlinIosSimulatorArm64 \
  :composeApp:wasmJsBrowserDistribution
```

追加したテスト: `CryptoVectorsTest`（共通。BIP340・NIP-44 のベクタ、`signEvent` の往復）、`BrowserKeyStoreTest`（wasmJs）。

ヘッドレス Chrome で本番ビルドを操作し、次を確認した。クライアントが送る `EVENT` は検証スクリプトの WebSocket 中継で止め、実リレーには送っていない。

- 匿名でグローバルフィードが表示される（受信イベントの署名検証が通る）。
- 鍵の生成、nsec のコピー、生成した鍵でのログイン。同意ダイアログに Web 向けの注意が出る。
- 既存 nsec のインポートでログインし、期待どおりの公開鍵がアクティブになる。
- 再読み込み後もログインが続く。ログアウトすると鍵を残したままログアウト済みになり、保存済みアカウントの削除で `torinos_web_*` がすべて消える。
- 投稿画面から送った kind 1 イベントを捕捉し、id と署名が正しいことを Node + noble で別途検算した。
- 開発サーバー（`wasmJsBrowserDevelopmentRun`）でも CSP に止められずに起動する。

未確認: 実リレーへの投稿と、他クライアントでの表示。Safari / Firefox での動作。複数タブで同時にアカウントを切り替えたときの挙動（タブ間の同期はしない）。
