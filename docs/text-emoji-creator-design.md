# テキスト絵文字の作成 設計

対象: 新規 `emoji/OwnEmojiSet*.kt`・`emoji/TextEmojiRenderer.kt`・`ui/settings/TextEmojiCreator*.kt`、既存の `CustomEmojiSettingsScreen`・`ImageUploader`。

## 1. 目的

10文字くらいまでのテキストから絵文字画像を作り、自分の絵文字セットに追加して、すぐ投稿やリアクションで使えるようにする。

今のアプリでできるのは、公開されているセット（kind 30030）の検索と登録だけ。自分でセットを作ったり絵文字を追加したりはできない。

## 2. ユーザーから見える流れ

1. カスタム絵文字の画面の上部に「＋ 作る」ボタンを置く（ログイン中だけ表示）。
2. 作成画面（全画面シート）:
   - テキスト入力（複数行。1行5文字まで・合計10文字まで・4行まで。改行はユーザーが入れる。超える入力は受け付けず、残り文字数を出す）
   - プレビュー（実際の大きさ 1.2em の表示と、拡大表示の2つ。拡大表示は透過がわかるように市松模様の背景）
   - 文字色（パレット8色程度）、背景（透明 + パレット）、太字の切り替え
   - shortcode 入力（初期値は 5.3 の規則で自動生成。英数字・`_`・`-` 以外は入力できない）
   - 追加先のセット（初回は「自分の絵文字」を新規作成。2回目以降は自分のセットから選ぶ）
   - 「作成して追加」ボタン
3. 押すと「アップロード中 → 送信中」と出し、終わったらシートを閉じて「:shortcode: を追加しました」と出す。
4. 追加先のセットは自動で登録済み（kind 10030）にするので、そのままピッカーに出る。

## 3. 画像の作り方

### 3.1 形と大きさ

- **正方形 256×256 の PNG**。このアプリは本文中の絵文字を 1.2em の正方形に `ContentScale.Fit` で描くため（`LinkedText.kt`）、横長にすると文字がとても小さくなる。
- 自動の折り返しはしない。ユーザーが入れた改行どおりに並べ、全行が正方形に収まる最大の文字サイズで描く（5.1）。
- 内側に 4% の余白を取る（隣の文字と接して見えないように）。

### 3.2 描画（commonMain）

Compose の `ImageBitmap(256, 256)` に `CanvasDrawScope` と `TextMeasurer` で描く。全プラットフォーム共通のコードになる。

- フォントは同梱の `NotoSansJP_Regular.ttf` を使う（Web 版でも日本語が同じ見た目になる）。太字は合成太字。
- 行ごとに横幅いっぱいに引き伸ばすのではなく、全行同じ文字サイズにする（読みやすさ優先）。

### 3.3 PNG への変換（プラットフォームごと）

```kotlin
// commonMain
internal expect fun ImageBitmap.encodeToPng(): ByteArray?
```

| プラットフォーム | 実装 |
| --- | --- |
| Android | `asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)` |
| iOS / Web | `Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)`（Skiko） |

`ImageCropper` の `cropImageForUpload` と同じく、各プラットフォームの `actual` を `ui/components` に置く。

### 3.4 アップロード

既存の `ImageUploader.uploadMedia(bytes, "image/png", signer)` をそのまま使う（nostr.build、NIP-98）。

## 4. 自分の絵文字セット

### 4.1 データ

```kotlin
/** 自分が作った kind 30030。最新のイベントをそのまま持ち、追加時は tags を書き換えて送り直す。 */
internal data class OwnEmojiSet(
    val address: EmojiSetAddress,
    val title: String,
    val emojis: List<CustomEmoji>,
    val event: NostrEvent?,   // 未送信の新規セットなら null
)
```

### 4.2 追加の手順（`OwnEmojiSetRepository.addEmoji`）

1. 追加先のセットの最新イベントを、自分の書き込みリレーから取り直す（`EmojiSetAddress.toFilter()`）。
   他のクライアントで追加した絵文字を上書きで消さないため、手元の古い内容は使わない。
2. 取れたイベントの tags を保ったまま `["emoji", shortcode, url]` を末尾に足す。
   `d`・`title`・`image` やこのアプリが知らないタグも残す。
3. 同じ shortcode があれば作成画面で確認し、「置き換える」なら url だけ差し替える。
4. 署名して書き込みリレーへ送る。1つ以上で OK が返れば成功とする。
5. 成功したら `CustomEmojiRepository.registerSet` で登録済みにする（kind 10030 の送信は既存の `EmojiPreferenceSync` が行う）。

失敗時はアップロード済みの URL を作成画面に残し、「もう一度送信」で 4 からやり直せるようにする（再アップロードしない）。
送信箱（バッジ発行の `BadgeOutbox` のような永続化と再送）は初版では作らない。5.4 参照。

### 4.3 新規セット

- d タグ: `torinos-<作成日時のミリ秒>`。title は「〈表示名〉の絵文字」を初期値にし、作成画面で変えられる。
- 自分のセット一覧は、自分の書き込みリレーから `kinds=[30030], authors=[自分]` で取る。登録済みでないものも出す。

## 5. 細かい規則

### 5.1 行と文字サイズ

行はユーザーが入れた改行で決まる。アプリは折り返さない。
全行を同じ文字サイズで描き、「最も幅の広い行が横に収まる」「全行の高さが縦に収まる」の両方を満たす最大の文字サイズを `TextMeasurer` で求める。各行は中央揃え。

- 例: 「草」→1行で大きく、「あり / がとう」→2行、「おつかれ / さまでした」→2行（5文字の行に合わせた大きさ）
- 1行5文字までなので、全角5文字の行でも文字サイズは画像の幅のおよそ 1/5 以上になる。
- 空の行（改行の連続）と、先頭・末尾の空行は取り除く。

純粋関数 `parseTextEmojiLines(input: String): List<String>` と、`TextMeasurer` を差し替えられる `fitTextEmojiFontSize(...)` に分けてテストする。

### 5.2 入力の制限

- 空白だけのテキストは作れない。各行の前後の空白は削る。
- 1行5文字・合計10文字（改行は数えない）・4行の制限を、入力欄で超えないようにする（`limitTextEmojiInput(previous, next)`。超える変更は受け付けず前の値のままにする。貼り付けも同じ）。
- 文字数は書記素単位で数える（絵文字や結合文字を途中で切らない）。

### 5.3 shortcode

- NIP-30 の shortcode は `[A-Za-z0-9_-]+`（`CustomEmojiCodeRegex` と同じ）。
- 初期値: テキストが英数字だけならそれを小文字にしたもの。それ以外は `txt_` + 作成日時（`yyMMddHHmm`）。
- 追加先のセットに同じ shortcode があれば 4.2 の 3 で確認する。登録済みの他のセットと重複しても止めない（今も重複は許している）。

### 5.4 範囲外（初版ではやらない）

- 自分のセットからの絵文字の削除・並べ替え・セットの削除（kind 5）。
- 縁取り・影・グラデーション・フォントの選択。
- 送信の永続的な再試行。

## 6. 実装の段階

1. 純粋関数とテスト: `parseTextEmojiLines`・`limitTextEmojiInput`・`fitTextEmojiFontSize`・shortcode の初期値と検証・`OwnEmojiSet` へのタグ追加（未知タグの保持、同じ shortcode の置き換え）。
2. 描画と PNG 変換: `renderTextEmoji(text, style): ImageBitmap` と `encodeToPng` の各 `actual`。
3. `OwnEmojiSetRepository`（取り直し → 追加 → 送信 → 登録）。送受信は差し替えられるようにしてテストする。
4. 作成画面と、カスタム絵文字画面への入口。
5. 動作確認。

## 7. 確認方法

1. 単体テスト: `./gradlew :composeApp:iosSimulatorArm64Test`。
2. ビルド: Android・iOS シミュレータ・Web（wasmJs）のコンパイル。
3. 画像: 3プラットフォームで同じ入力（「草」「あり/がとう」「おつかれ/さまでした」「ABCDE」、絵文字入り）の PNG を書き出して見比べる。
4. Web 版をヘッドレスで操作。アップロードと EVENT 送信は `route` / `routeWebSocket` で横取りし、送る予定の kind 30030 のタグを確かめる（実リレーには送らない）。
5. 実際の送信は、ユーザーの了承を得てテスト用のセットで1回だけ行う。

## 8. 決めてほしいこと

- Q1. 画像は正方形のみでよいか（このアプリの表示に合わせた。他のクライアント向けに横長も選べるようにするか）。
- Q2. 追加先は「自分の絵文字」1つに固定してよいか、複数のセットから選べるようにするか（設計は選べる形にしている）。
- Q3. 入口はカスタム絵文字の画面だけでよいか（絵文字ピッカーからも作れるようにするか）。

## 9. 設計レビュー

- 3.1: 256px は nostr.build の再圧縮後も十分な大きさで、1つ数KB〜十数KB に収まる。表示側は `emojiDecodeSizePx` で縮小して読むので重さは変わらない。
- 3.2: `CanvasDrawScope` で `ImageBitmap` に描く方法は Android（`android.graphics.Canvas`）と Skiko の両方で動く。`TextMeasurer` は Composable の外で作るため `FontFamily.Resolver` が要る。作成画面で `LocalFontFamilyResolver` を取って渡す。
- 3.2: 同梱フォントに無い文字（カラー絵文字など）は環境の代替フォントで描かれ、Web 版では出ないことがある。プレビューで見たものがそのまま画像になるので、ユーザーは作る前に気づける。
- 4.2: 取り直しで何も取れなかったとき（リレーの不調）に手元の内容で送ると、他のクライアントでの追加を消すおそれがある。既存セットへの追加で取り直しが失敗したら送らずにエラーにする。新規セットは取り直さない。
- 4.2: kind 30030 は置き換え可能イベントなので、同時に2端末から追加すると片方が消える。初版では許容し、5.4 の範囲外とする。
- 4.2 の 5: `registerSet` は既に登録済みなら内容を差し替えるだけ（`EmojiPreferencesReducer.registerSet`）なので、2回目以降の追加でも呼んでよい。
- 5.1: 改行をユーザーに任せるのは、言葉の切れ目（「おつかれ / さまでした」）を自動では判断できないため。1行5文字の上限で、文字が小さくなりすぎるのを防ぐ。
- 5.1: 行ごとに文字サイズを変えない方針は、短い行だけ大きくなって見た目がそろわないのを避けるため。
- 5.2: 4行の上限は、1文字ずつ縦に並べた場合でも文字サイズが画像の高さのおよそ 1/5 になるようにするため。
- 7.4: Web 版のヘッドレス確認は既存の手順（EVENT 遮断）に合わせる。
