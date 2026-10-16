# 署名の抽象化と外部 signer・Web の鍵保護 設計

作成日: 2026-10-16
状態: 段階1〜3を実装済み（2026-10-17）。段階4（NIP-55）は未着手。実装で設計から変えた点は §13。
関連: `account-session-viewmodel-architecture.md`、`web-private-key-login-design.md`（D2・7章）、`zap-feature-design.md`、`dm-feature-design.md`、`app-requirements.md`（NIP-07/46/55 は未対応）

## 1. 目的と範囲

署名をアプリの外に任せられる形にし、次の3つを実現する。

- Web 版で秘密鍵を平文で `localStorage` に置かなくて済むようにする。
- NIP-46（リモート署名）、NIP-55（Android の署名アプリ）、NIP-07（ブラウザ拡張）でログインできるようにする。
- Zap（kind 9734）・DM（NIP-17）・リレー認証（NIP-42）が、どの署名方式でも同じ経路で動くようにする。

| 段階 | 内容 |
| --- | --- |
| **段階1（土台）** | `AccountSigner` を suspend にし、対応範囲（capabilities）を持たせる。アカウントの保存形式に「署名方式」を足す。動作は変えない |
| 段階2（Web） | NIP-07 ログイン。秘密鍵ログインは、パスキー（WebAuthn PRF）で暗号化して保存する形へ移す |
| 段階3（全プラットフォーム） | NIP-46 ログイン（`bunker://` と `nostrconnect://`） |
| 段階4（Android） | NIP-55 ログイン（Amber など） |

非目標: ネイティブ版の秘密鍵をパスキーで保護すること（Keystore・Keychain で足りている）。ハードウェア署名器。ToriNos 自身をリモート署名器にすること。

## 2. 現状

| 項目 | 現状 | 問題 |
| --- | --- | --- |
| 署名の型 | `AccountSigner`（`account/AccountSession.kt`）。`sign`・`encryptToSelf`・`decrypt` はすべて同期関数 | 外部 signer は「頼んで返事を待つ」ので同期では書けない |
| 実装 | `PrivateKeyAccountSigner` だけ。セッションの `lease` で失効を確かめる | — |
| 呼び出し箇所 | `sign(` が 20 ファイル・30 か所。`encryptToSelf` 3 か所、`decrypt` 3 か所（下書き・非公開ミュート） | suspend 化で呼び出し元も suspend にする必要がある |
| 暗号化 | 自分宛ての NIP-44（`encryptToSelf`）と、相手を指定した復号だけ | DM・NWC・NIP-46 では、相手を指定した暗号化が要る |
| 鍵の保存 | `KeyStorage`（expect/actual）は秘密鍵だけを保存し、アカウント一覧を公開鍵から作る | 秘密鍵を持たないアカウントを表せない |
| Web | `localStorage` に平文の hex（`web-private-key-login-design.md` D2） | 保存データを読まれると、鍵そのものが永久に漏れる |

`web-private-key-login-design.md` D2 は「WebCrypto の抽出不可鍵で暗号化しても、XSS が起きれば復号関数ごと使われるので効果に見合わない」として暗号化を見送った。この判断は**同じセッション中の XSS** については正しい。本設計は、それ以外の経路（保存データの持ち出し、悪意ある拡張機能による後からの読み出し、共有 PC）を塞ぐことを目的にする（§6.2）。XSS に対する本当の対策は、鍵をページに置かない NIP-07・NIP-46 である。

## 3. 他クライアントの実装（2026-10-16 時点）

| 項目 | Nostrism（Compose Multiplatform） | nokakoi（Web） | Lumilumi（Web） | 本設計 |
| --- | --- | --- | --- | --- |
| 署名の型 | `Signer`。すべて suspend。`capabilities` で NIP-44・NIP-04 の対応を表す | 関数の差し替え | `nostr-login` ライブラリ | Nostrism と同じ形 |
| ローカル鍵 | Keystore・Keychain | パスワードまたはパスキーで暗号化 | —（ライブラリ任せ） | ネイティブは現状維持。Web はパスキー |
| パスキー | Android でも対応（Digital Asset Links が必要） | PRF → AES-256-GCM（`prf1:`） | — | Web のみ |
| NIP-46 | 対応 | 対応。通信用の鍵をパスキーで暗号化することもできる | 対応 | 対応。通信用の鍵は安全な保存先へ |
| NIP-55 | 対応 | — | — | 段階4 |
| NIP-07 | Web 版（React）で対応 | 対応 | 対応 | 段階2 |

## 4. 段階1: 署名の型を作り直す

### 4.1 インターフェース

```kotlin
interface AccountSigner {
    val pubkey: String
    val method: SignerMethod
    val capabilities: Set<SignerCapability>

    suspend fun sign(
        content: String,
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
        createdAt: Long? = null,
    ): NostrEvent

    suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String
    suspend fun nip44Decrypt(payload: String, peerPubkey: String): String

    /** 旧 DM（kind 4）・旧 NWC の読み出し用。対応しない実装は capabilities に NIP04 を持たない。 */
    suspend fun nip04Decrypt(payload: String, peerPubkey: String): String =
        throw SignerException.Unsupported(SignerCapability.NIP04)
    suspend fun nip04Encrypt(plaintext: String, peerPubkey: String): String =
        throw SignerException.Unsupported(SignerCapability.NIP04)
}

enum class SignerMethod { PrivateKey, Passkey, Nip07, Nip46, Nip55 }
enum class SignerCapability { NIP44, NIP04 }
```

- `pubkey` は同期のまま残す。ログイン時に確定し、保存しておく値なので待つ必要がない。
- `encryptToSelf(plaintext)` は `nip44Encrypt(plaintext, pubkey)` の拡張関数にする。`decrypt(content, peer)` は `nip44Decrypt` へ名前を変える。
- 署名済みイベントは、外部 signer から受け取った場合も**必ず検証してから返す**（`isValidEvent`、pubkey・kind・content・tags が依頼どおりか）。外部 signer が別の内容に署名して返すのを防ぐ。

### 4.2 例外

```kotlin
sealed class SignerException(message: String) : Exception(message) {
    class Rejected : SignerException("署名が拒否されました")
    class Timeout : SignerException("署名器から応答がありません")
    class Unavailable(reason: String) : SignerException(reason)       // 拡張なし、接続できない、アプリなし
    class Unsupported(val capability: SignerCapability) : SignerException("この署名方式では使えません")
    class AuthRequired(val url: String) : SignerException("署名器での承認が必要です") // NIP-46 auth_url
}
```

- 呼び出し元は、楽観的に更新した表示（リアクションなど）を、署名の失敗時に元へ戻す。今は秘密鍵で必ず成功するので、この戻し処理がない箇所がある。段階1で一覧を作って確かめる（§9）。
- `SessionClosed`（アカウントを切り替えた後の署名）は、今の `lease.ensureActive()` の例外をそのまま使う。

### 4.3 呼び出し元の suspend 化

- 30 か所の `sign(`（20 ファイル）と、`encryptToSelf`・`decrypt` の 6 か所を suspend の文脈へ移す。多くはすでに `viewModelScope.launch` の中にあるので、関数の宣言に `suspend` を足すだけで済む。
- 重い処理を持つ箇所（バッジの一括授与・一括削除の `map { signer.sign(...) }`）は、外部 signer では1件ごとに往復するので、進み具合を出す（§7.4）。
- 段階1の終わりでは `PrivateKeyAccountSigner` しかないので、画面の動きは変わらない。この段階で全テストを通し、Web を含む3プラットフォームでビルドする。

### 4.4 アカウントの保存形式

`KeyStorage` を、秘密鍵ではなく「アカウント記録」を保存する形へ広げる。

```kotlin
@Serializable
data class StoredAccountRecord(
    val pubkey: String,
    val method: SignerMethod,
    val nip46: Nip46Connection? = null,   // 段階3
    val nip55Package: String? = null,     // 段階4
)

@Serializable
data class Nip46Connection(
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val permissions: String? = null,
)
```

- 秘密にあたる値（秘密鍵、NIP-46 の通信用の鍵と `secret`）は記録に入れず、今と同じ安全な保存先に pubkey ごとのキーで置く（Android は Keystore の鍵で AES-GCM、iOS は Keychain、Web は §6）。
- 既存アカウントは `method = PrivateKey` として読み替える。移行は起動時に1回行い、元の秘密鍵の保存は消さない（失敗しても戻れるように）。
- `StoredAccount`（一覧表示用）には `method` を足し、アカウント一覧に「拡張機能」「Amber」などの印を出す。

## 5. 段階2: Web の NIP-07

### 5.1 ログイン

- ログイン画面に「ブラウザ拡張でログイン」を出す。`window.nostr` がないときは押せなくし、対応拡張の例（nos2x、Alby など）を出す。拡張は読み込みが遅れることがあるので、画面を開いてから最大 1 秒待って判定する（nokakoi・Lumilumi と同じ）。
- `getPublicKey()` で pubkey を得て、`method = Nip07` の記録を保存する。秘密鍵は保存しない。
- Web 版の既定のログイン方法にする（`web-private-key-login-design.md` 7章の方針どおり）。

### 5.2 実装

- `wasmJsMain` に `Nip07Signer` を置き、`window.nostr.signEvent`・`nip44.encrypt/decrypt`・`nip04.encrypt/decrypt` を Kotlin/Wasm の JS 相互運用で呼ぶ。Promise は `await()` で待つ。
- `capabilities` は、`window.nostr.nip44` があれば NIP44、`nip04` があれば NIP04。
- 拡張が承認を求めて待つ間は、呼び出し元の表示（送信中）を続ける。60 秒で `Timeout`。

## 6. 段階2: Web の秘密鍵をパスキーで保護する

### 6.1 仕組み

1. 秘密鍵でログインするとき、または既存の平文保存から移すとき、PRF 拡張つきのパスキーを作る（`navigator.credentials.create`、RP はアプリのドメイン）。
2. 生体認証などでパスキーを使い（`navigator.credentials.get`、`prf.eval.first` に固定の salt）、PRF の出力を HKDF で AES-256-GCM の鍵にする。
3. 秘密鍵を暗号化し、`torinos_web_private_key_<pubkey>` を `torinos_web_passkey_key_<pubkey>`（`{ version, credentialId, iv, ciphertext }`）に置き換える。平文は消す。
4. 起動時は「パスキーで解錠」を押し、生体認証の後に復号して `PrivateKeyAccountSigner` を作る。復号した鍵はセッションの間だけメモリに置く。

`method = Passkey` は「保存のしかた」が違うだけで、署名は `PrivateKeyAccountSigner` と同じ。

### 6.2 防げること・防げないこと

| 脅威 | 平文保存（現状） | パスキー保護 | NIP-07 / NIP-46 |
| --- | --- | --- | --- |
| 保存データの持ち出し（拡張機能、共有 PC、バックアップ） | 鍵が漏れる | 暗号文だけ | 鍵がない |
| 解錠していない間の XSS | 鍵が漏れる | 暗号文だけ | 鍵がない |
| 解錠中の XSS | 鍵が漏れる | **鍵が漏れうる** | 署名を頼まれるだけ（鍵は無事） |
| 偽サイト | — | パスキーはドメインに結び付くので使えない | 拡張・署名器の確認次第 |

解錠中の XSS は防げない。これは D2 の指摘どおりで、同意ダイアログでも「Web 版で最も安全なのは拡張機能またはリモート署名」と案内する。

### 6.3 対応しないブラウザ

- PRF 拡張に対応しない環境（古いブラウザ、PRF に未対応のパスキー保存先）では、作成時に `prf.enabled` が返らない。この場合は「このブラウザではパスキー保護を使えません」と出し、NIP-07・NIP-46 を勧める。
- 平文保存を新しく選ぶ道は残さない。既存の平文保存は読み込めるが、起動時に移行を促すバナーを出す（閉じると 7 日は出さない）。完全に止める時期は §12 で決める。
- 主要ブラウザの PRF 対応状況は実装の前に確かめる（§12）。

### 6.4 Kotlin/Wasm からの呼び出し

- `navigator.credentials` は Kotlin/Wasm の `external` 宣言と `js()` で呼ぶ。`ArrayBuffer` と `ByteArray` の変換は、既存の `NobleSecp256k1.kt` と同じ形でまとめる。
- HKDF と AES-GCM は WebCrypto（`crypto.subtle`）を使う。非同期だが、解錠は1回だけなので問題ない（D1 で WebCrypto を見送った理由は「同期 API に合わない」ことで、ここでは当てはまらない）。
- 生体認証のダイアログはユーザーの操作からしか開けないので、自動ログインはせず、必ず「パスキーで解錠」ボタンを押してもらう。

## 7. 段階3: NIP-46

### 7.1 接続

| 入口 | 流れ |
| --- | --- |
| `bunker://<署名器の公開鍵>?relay=…&secret=…` を貼り付け | 通信用の鍵を作り、`connect`（`[署名器の公開鍵, secret, perms]`）を送る |
| ToriNos が `nostrconnect://<通信用の公開鍵>?relay=…&secret=…&perms=…&name=ToriNos` を QR とリンクで出す | 署名器が `connect` の応答を返すのを待つ。応答の `result` が自分の出した `secret` と一致することを確かめる |

- 接続の後、`get_public_key` でユーザーの pubkey を確定し、記録に保存する。署名器の公開鍵とユーザーの pubkey は違うことがある。
- 通信用の鍵（32 バイト）は、アカウントごとに安全な保存先へ置く（Android は Keystore、iOS は Keychain、Web は §6 と同じパスキー保護を選べる。選ばない場合は平文で、そのリスクを明示する。nokakoi と同じ）。
- 求める権限（`perms`）は、ToriNos が署名する kind の一覧と `nip44_encrypt`・`nip44_decrypt`。kind の一覧は `AccountSigner` を使う箇所から作り、テストで漏れを確かめる。

### 7.2 通信

- kind 24133、内容は NIP-44 で暗号化した JSON-RPC（`{id, method, params}` → `{id, result, error}`）。
- 接続先リレーは利用者のリレー設定に含まれないことが多いので、`NostrRepository.subscribeTemporaryRelay` と同じ一時接続を使う。応答の購読（`#p=[通信用の公開鍵] kinds=[24133]`）は、要求を送る前に始める。
- 1 回の要求のタイムアウトは 30 秒。`auth_url` が返ったら `AuthRequired` にして、画面で「署名器で承認する」ボタンからその URL を開く。承認後は同じ要求の応答を待ち続ける（最大 5 分）。
- 同時に送る要求は 4 件まで。それ以上は順に待たせる（署名器やリレーの制限に当たらないように）。
- **応答がないときに自動で再送しない**。署名の再送は二重投稿にはならないが、暗号化・復号は署名器の承認画面を何度も出させてしまう。

### 7.3 セッションとの関係

- 接続はアカウントのセッション（`AccountSession`）の寿命に従い、切り替え・ログアウトで閉じる。
- 起動時は接続を張り直す。署名器が応答しない間も、読むだけの操作はできる（ログイン状態は保ち、書き込み時にエラーを出す）。
- ログアウトでは、通信用の鍵を消さない（再接続のため）。アカウント削除で消す。

### 7.4 遅さへの配慮

外部 signer では、1 回の署名に数百ミリ秒〜数秒かかり、署名器側で承認が必要なこともある。

- 書き込み操作のボタンは、署名が終わるまで「送信中」にする。リアクションのような楽観的な表示は先に出し、失敗したら戻す。
- 自動の書き込みを見直す。
  - 記事下書きの自動保存（入力が止まって 10 秒、最長 60 秒ごと）は、外部 signer では NIP-44 暗号化と署名で2回往復する。外部 signer のときは間隔を 60 秒・最長 5 分に広げ、離脱時の保存は残す。
  - 起動時の非公開ミュートリストの復号は、1 回の往復で済むので変えない。
  - バッジの一括授与・削除は、件数と進み具合を出し、途中で失敗したら残りを止める。
- NIP-42 の認証（`dm-feature-design.md` の前提）は接続ごとに署名が要る。外部 signer では、認証が必要なリレーへの接続を必要になるまで遅らせる。

## 8. 段階4: NIP-55（Android）

- `nostrsigner:` の Intent でログインし、`get_public_key` の応答で pubkey と署名アプリのパッケージ名を得る。
- 署名・暗号化は、まず ContentResolver（`content://<パッケージ名>.SIGN_EVENT` など）で画面を出さずに頼む。権限がなく `null` が返ったら Intent で署名アプリを開いて承認してもらう。
- `AndroidManifest.xml` の `<queries>` で `nostrsigner` スキームを宣言する（Zap の `lightning` と同じ理由）。
- Activity の結果を待つ仕組みは `androidMain` に閉じ込め、`AccountSigner` の suspend 関数から呼べるようにする。
- 同じ端末の Amber に NIP-46 でつなぐより速い（リレーを経由しない）ので、Android で Amber が入っていれば NIP-55 を勧める。

## 9. テスト

| 対象 | 主なケース |
| --- | --- |
| `AccountSigner` の契約 | 全実装で共通のテスト（署名の検証、NIP-44 の往復、未対応時の `Unsupported`、セッション終了後の失敗） |
| 外部 signer の返り値の検証 | 依頼と違う kind・content・tags・pubkey、不正な署名を拒否する |
| 保存形式の移行 | 既存の秘密鍵アカウントが `PrivateKey` として読める、移行の途中で失敗しても元の鍵が残る |
| 楽観的表示の戻し | リアクション・リポスト・フォローで署名が失敗したら元の表示に戻る（段階1で該当箇所を洗い出す） |
| NIP-46 | 偽の署名器（テスト用リレー）で connect・secret の照合・get_public_key・sign_event・auth_url・タイムアウト・同時要求の上限 |
| パスキー | PRF 出力から鍵を作って往復できる、credentialId が違うと復号できない、PRF 未対応の分岐 |
| 権限の一覧 | ToriNos が署名する kind がすべて `perms` に入っている |

実機では、Alby（NIP-07）、nsec.app と Amber（NIP-46）、Amber（NIP-55）で、投稿・リアクション・下書き保存・ミュートの復号を確かめる。

## 10. 実装順序

1. `AccountSigner` の suspend 化と例外、呼び出し元の変更、楽観的表示の戻しの洗い出し。
2. `StoredAccountRecord` と移行。
3. Web: `Nip07Signer` とログイン画面。
4. Web: パスキー保護、平文からの移行バナー。
5. NIP-46（共通コード）と接続画面、QR 表示。
6. NIP-55（Android）。
7. `app-requirements.md` の NIP-07/46/55 と Web の鍵保存の記述を更新し、`web-private-key-login-design.md` D2 に本書への参照を足す。

## 11. 設計レビュー

| # | 指摘 | 対応 |
| --- | --- | --- |
| R1 | 外部 signer が依頼と違うイベントに署名して返す可能性 | §4.1 で、返ってきたイベントを必ず検証する |
| R2 | D2 の「暗号化しても XSS には効かない」との矛盾 | §2・§6.2 で、防げるものと防げないものを分けて示した。XSS への対策は NIP-07・NIP-46 |
| R3 | NIP-46 の応答待ちで自動再送すると、署名器に承認画面を何度も出させる | §7.2 で自動再送しない |
| R4 | 下書きの自動保存が外部 signer の往復を増やす | §7.4 で外部 signer のときだけ間隔を広げる |
| R5 | 通信用の鍵が漏れると、その接続で署名を頼めてしまう | §7.1 で安全な保存先に置く。署名器側の権限で範囲を絞れることも案内する |
| R6 | 秘密鍵を持たないアカウントを `KeyStorage` で表せない | §4.4 でアカウント記録を導入 |
| R7 | 段階1で動作が変わると原因を切り分けにくい | 段階1は型の変更だけにし、実装は `PrivateKeyAccountSigner` のまま |

## 12. 未確定事項

- 主要ブラウザ（Chrome、Safari、Firefox）とパスキー保存先（iCloud キーチェーン、Google パスワードマネージャー、1Password など）の PRF 対応状況。
- Web の平文保存を完全にやめる時期（移行バナーを出し始めてから何か月後か）。
- NIP-46 の既定の接続リレー（`nostrconnect://` で ToriNos が指定するリレー）。
- Web 版を公開するドメイン（パスキーの RP ID が決まる。`web-private-key-login-design.md` の未確定事項と同じ）。

## 13. 実装で設計から変えた点（2026-10-17）

| 項目 | 設計 | 実装 | 理由 |
| --- | --- | --- | --- |
| アカウントの保存形式（§4.4） | 公開の「アカウント記録」と、秘密の値を別々に保存 | `KeyStorage` のアカウントごとの保存値を、秘密鍵の hex か `torinos-signer:v1:` で始まる JSON（`SignerCredential`）にした | 3プラットフォームの `KeyStorage` がすでに「アカウントごとに1つの値を安全な保存先へ」置く作りで、移行なしで既存アカウントをそのまま読めるため。署名方式は保存値から分かる |
| 呼び出し元の suspend 化（§4.3） | 30 か所を移す | 3 か所（下書き・ミュートの復号）だけ直せばよかった | ほかはすでに suspend の文脈で呼んでいた |
| 復号の失敗の扱い | — | 壊れた内容は従来どおり「読めない下書き」とし、署名器の失敗（`SignerException`）とセッション終了は呼び出し元へ伝える（`decodeUnlessSignerFails`） | 署名器が応答しないのに件数ぶんタイムアウトを待ち、理由も伝わらないことを避ける |
| 状態の知らせ方 | 解錠ボタン、承認ボタンを個別に | `InteractiveSigner.attention` を画面上部の帯（`SignerStatusBanner`）でまとめて出す | 解錠・承認・再接続・平文保存の案内を1か所にまとめる |
| 解錠後の読み直し | — | 解錠・再接続で署名器が使えるようになったら、非公開ミュートリストを読み直す | 起動時に読めなかった暗号化データを取り戻す |
| パスキーの作成（§6.1） | 作成と暗号化を別の操作 | 作成時に PRF の出力が返る環境では1回で済ませ、返らない環境では続けてパスキーを使う。途中で失敗したら、作成済みのパスキーを覚えておき、もう一度押したときに作り直さない | 利用者の操作を減らす |
| 解錠済みの鍵 | セッションの間だけメモリ | ページを開いている間はメモリに残し、ログアウト・削除で消す | アカウントを切り替えて戻るたびに生体認証を求めないため |
| NIP-46 の通信用の鍵（§7.1） | Web はパスキーで保護を選べる | 秘密鍵と同じ保存先に置く（Web では暗号化しない） | まず接続できることを優先した。漏れても署名器側の権限の範囲でしか使えず、署名器で接続を取り消せる |
| `nostrconnect://`（§7.1） | QR とリンクで出す | リンクの「署名アプリで開く」と「コピー」だけ | QR を描くライブラリが入っていない。QR は後で足す |
| NIP-46 の接続先 | 未確定（§12） | `wss://relay.nsec.app` と `wss://nos.lol` | NIP-46 でよく使われるリレー |
| 既存アカウントとの重なり | 未記載 | 同じ公開鍵が秘密鍵（パスキーで保護したものを含む）で保存済みなら、NIP-07・NIP-46 のログインで上書きしない | 実機確認で、上書きすると保存していた秘密鍵が消えることが分かった（確認記録 D1） |
| 同じ端末の署名アプリへの `nostrconnect://` | 未記載 | ダイアログで `bunker://` の貼り付けを勧める | 承認の間 ToriNos が裏に回って通信が止まり、リレーに残らない応答（kind 24133）を取りこぼす（確認記録 D2）。根本の対策は段階4の NIP-55 |
| 利用条件への同意 | 未記載 | NIP-46 のダイアログを開く前に求める | 接続の後に断られると、署名器に接続だけが残る |
| 下書きの自動保存（§7.4） | 外部 signer で間隔を広げる | NIP-07・NIP-46 で、入力が止まって 60 秒・最長 5 分（パスキーはアプリ内で署名するので変えない） | 設計どおり |

### 確かめたこと・確かめていないこと

- 自動テスト: iOS シミュレーターと Web（ブラウザ）で全件成功。新しく足したのは、保存値の変換、外部 signer の返り値の検証、パスキー以外の Web の保存（`BrowserKeyStore`）、NIP-46（偽の署名器で connect・sign_event・auth_url・タイムアウト・拒否・別の鍵からの応答・`nostrconnect://`）。
- Android 実機での確認は [`signer-abstraction-test-report-2026-10-17.md`](./signer-abstraction-test-report-2026-10-17.md)。本物の署名器（Amethyst）で `connect`・`get_public_key` まで確かめた。
- それ以外の実機・実ブラウザでの確認はまだ。Alby・nos2x（NIP-07）、nsec.app・Amber（NIP-46）、Chrome・Safari でのパスキー（PRF）を §9 の手順で確かめる。特に、ボタンを押してから生体認証のダイアログを出すまでに Compose の処理を挟むので、Safari がユーザー操作として扱うかを確かめる。
