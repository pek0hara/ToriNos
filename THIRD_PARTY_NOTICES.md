# Third-party notices

ToriNos の独自コード・文書はルートの `LICENSE`（MIT）に従います。
第三者のコード、フォント、ライブラリ、テストデータの権利・ライセンスは
各権利者に帰属し、ToriNos の MIT ライセンスで置き換えられることはありません。

## 直接依存・同梱物

2026-11-22 に `gradle/libs.versions.toml`、各モジュールの Gradle 設定、
インストール済み npm パッケージ、Git 管理下のソースとリソースを確認しました。
下記のライセンス本文は `licenses/` に保存しています。

| 対象 | 宣言バージョン | ライセンス・本文 | 上流 |
| --- | --- | --- | --- |
| Kotlin / kotlin-test | 2.4.20 | Apache-2.0・kotlin.txt、kotlin-NOTICE.txt | https://github.com/JetBrains/kotlin |
| Compose Multiplatform（UI、Foundation、Runtime、Material、アイコン、Resources等） | 1.12.0 | Apache-2.0・compose.txt | https://github.com/JetBrains/compose-multiplatform |
| Ktor（Core、WebSockets、OkHttp、Darwin、JS） | 3.3.3 | Apache-2.0・ktor.txt | https://github.com/ktorio/ktor |
| kotlinx.coroutines | 1.10.2 | Apache-2.0・coroutines.txt | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.9.0 | Apache-2.0・serialization.txt | https://github.com/Kotlin/kotlinx.serialization |
| kotlinx.datetime | 0.7.1 | Apache-2.0・datetime.txt | https://github.com/Kotlin/kotlinx-datetime |
| JetBrains AndroidX Lifecycle / Navigation | 2.8.4 / 2.9.2 | Apache-2.0・jetbrains-androidx.txt | https://github.com/JetBrains/compose-multiplatform-core |
| AndroidX Activity / DataStore / Media3 / Credentials（Play Services連携を含む） | 1.10.1 / 1.2.1 / 1.11.1 / 1.5.0 | Apache-2.0・androidx.txt | https://github.com/androidx/androidx |
| Coil | 3.6.3 | Apache-2.0・coil.txt | https://github.com/coil-kt/coil |
| secp256k1-kmp / JNI Android | 0.23.0 | Apache-2.0・secp256k1-kmp.txt | https://github.com/ACINQ/secp256k1-kmp |
| @noble/curves / @noble/hashes | 各2.4.0 | MIT・noble-curves.txt、noble-hashes.txt | https://github.com/paulmillr/noble-curves / https://github.com/paulmillr/noble-hashes |
| @js-joda/timezone / @js-joda/core | 2.3.0 / 3.2.0（coreは間接依存） | BSD-3-Clause・js-joda-timezone.txt、js-joda-core.txt | https://github.com/js-joda/js-joda |
| ws（ローカルのWeb依存ツリー） | 8.18.3 | MIT・ws.txt | https://github.com/websockets/ws |
| Noto Sans JP | 同梱TTF | OFL-1.1・NotoSansJP-OFL.txt | https://github.com/google/fonts/tree/main/ofl/notosansjp |

Noto Sans JP のファイル内メタデータでも Adobe の著作権表記と OFL-1.1 を確認済みです。
フォントは `composeApp/src/commonMain/composeResources/font/NotoSansJP_Regular.ttf` にあります。

## 主な間接依存・開発ツール

全プラットフォームの解決済み依存ツリーを網羅する一覧ではありません。

| 対象 | ライセンス・保存本文 | 上流 |
| --- | --- | --- |
| libsecp256k1（暗号ライブラリ内部） | MIT・libsecp256k1.txt | https://github.com/bitcoin-core/secp256k1 |
| OkHttp / Okio | Apache-2.0・okhttp.txt、okio.txt | https://github.com/square/okhttp / https://github.com/square/okio |
| Skiko / Skia（描画） | Apache-2.0・skiko.txt / BSD-3-Clause・skia.txt | https://github.com/JetBrains/skiko / https://github.com/google/skia |
| Gradle wrapper | Apache-2.0・gradle.txt | https://github.com/gradle/gradle |

## 参照コード・テストデータ

- `crypto/Bech32.kt` は Bech32 のチェックサム・ビット変換を実装しています。
  コメントからコピー元を断定できないため、同じ処理を持つ
  [Pieter Wuille の参照実装](https://github.com/sipa/bech32/blob/master/ref/python/segwit_addr.py)
  の MIT 表記も `licenses/bech32-reference.txt` に保存しています。コピー元が確定したとの主張ではありません。
- `CryptoVectorsTest.kt` の BIP340 ベクトルは
  [BIP340 の Test Vectors and Reference Code](https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki)
  に記載された BSD-2-Clause / MIT / CC0-1.0 の選択肢から CC0-1.0 を適用します。
- 同テストの NIP-44 ベクトルは
  [paulmillr/nip44 の JavaScript テストデータ](https://github.com/paulmillr/nip44/blob/main/javascript/test/nip44.vectors.json)
  を参照しています。同リポジトリの README は TypeScript / JS を Public domain としています。
- Git 管理下のコードを著作権・借用元を示す表記で検索しました。それ以外の借用元は特定できていません。
  表記のないコードの由来を、この検索だけで保証するものではありません。
- ルートの `commonMain/`、`androidMain/`、`jvmMain/` は `.gitignore` 対象の展開済み依存ソースです。
  公開リポジトリの独自コードとして扱っていません。

## 配布と更新

- MIT / BSD の著作権・条件・免責条項、Apache-2.0 本文と該当する NOTICE、
  フォントの OFL 本文を保持します。第三者コードを改変した場合は変更した旨も記録します。
- 本文をまとめた `composeApp/src/commonMain/composeResources/files/third_party_licenses.txt`
  を共通リソースとして同梱します。`python3 scripts/generate-third-party-licenses.py` で再生成できます。
- npm の本文はインストール済みの上記バージョンから取得しました。
  その他の本文は確認日の公式リポジトリから取得しており、全リリースタグとの一致を検証したものではありません。
- リリース前には各プラットフォームの解決済み依存ツリーと最終配布物を確認してください。
  特に Skia の内包ライブラリ、Google Play Services などの間接依存、追加の NOTICE、
  アプリ内からのライセンス閲覧導線は、この確認では網羅・検証していません。
  この文書は、完成した配布物全体のライセンス監査を証明するものではありません。
