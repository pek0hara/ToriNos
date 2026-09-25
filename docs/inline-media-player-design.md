# 投稿カード インライン動画・音声プレイヤー設計

## 1. 目的

投稿本文や `imeta` タグに含まれる動画・音声ファイルのURLを、リンクプレビューではなく
投稿カード内のプレイヤーとして表示する。

保証する性質は次のとおり。

1. 動画・音声URLは本文テキストとリンクプレビューから除外され、プレイヤーとして表示される。
2. タイムライン表示時点ではメディア本体を読み込まない。プレイヤーはタップされてから生成する。
3. 同時に生成されるプレイヤーは常に1つだけである。
4. 画面外へのスクロール、画面遷移、アプリのバックグラウンド移行でプレイヤーを解放する。
5. 再生できないURLは、エラー表示と外部ブラウザで開く導線に切り替える。

## 2. 対象範囲

### 2.1 対象

- `model/MediaMetadata.kt`: メディア種別の判定
- `ui/components/NoteCard.kt`: `parseNoteContent`、`NoteCard`、`QuotePreview`
- `ui/components/InlineMediaPlayer.kt`（新規）: 共通UI、再生中メディアの管理
- `ui/components/PlatformMediaPlayer.android.kt`（新規）: Media3 ExoPlayer
- `ui/components/PlatformMediaPlayer.ios.kt`（新規）: AVPlayer / AVPlayerViewController

### 2.2 非目標

- 自動再生、スクロール連動のミュート再生
- 動画のアップロード（投稿画面）
- HLS(`.m3u8`)など、ストリーミングマニフェストへの対応
- `fallback` URLへの自動切り替え
- バックグラウンド再生、ロック画面の再生コントロール
- 画像ビューア相当の独自全画面UI（iOSは標準コントローラの全画面を利用）

## 3. メディア種別の判定

`MediaMetadata` に `mediaKind: MediaKind?`（`Image` / `Video` / `Audio`）を追加する。

1. `mimeType` が `image/*`・`video/*`・`audio/*` のいずれかであれば、それを優先する。
2. それ以外は、クエリとフラグメントを除いたURLの拡張子で判定する（大文字小文字は区別しない）。

| 種別 | 拡張子 |
|---|---|
| Image | jpg, jpeg, png, gif, webp, bmp, svg（既存と同じ） |
| Video | mp4, m4v, mov, webm |
| Audio | mp3, m4a, aac, wav, ogg, oga, opus, flac |

`isImage` は `mediaKind == Image` と同じ意味にする。今の実装では `mimeType` が `video/*`
のときでも拡張子が画像なら画像として扱っているが、変更後は `mimeType` を優先する。

`mp4` は音声だけのファイルの場合もあるが、URLだけでは区別できない。そのため判定時点では
`Video` として扱い、再生開始後にプラットフォーム側から映像トラックの有無を受け取って
音声用の表示に切り替える（5.3）。

WebM・Ogg・Opus は iOS の AVPlayer では再生できない。判定では対象に含め、再生に失敗した
ときに5.4のエラー表示へ落とす。プラットフォームによって本文の見え方が変わらないようにするためである。

## 4. 本文の解析

`ParsedNoteContent` に `playableMedia: List<MediaMetadata>` を追加する。

- 本文中のWeb URL（`extractWebUrls`、末尾の句読点除去を含む）のうち、`Video`・`Audio`
  と判定されたもの
- 本文に含まれる `imeta` のうち、`Video`・`Audio` と判定されたもの（拡張子なしのBlossom URLなども対象）
- kind `1063` イベントで、`Video`・`Audio` と判定されたもの

同じURLは `imeta` の情報を優先して1件にまとめ、本文での出現順に並べる。

- `textContent` から、画像と同じように再生可能メディアのURLを除去する。
- `linkPreviewUrl` は、画像にも再生可能メディアにも当たらない最初のURLにする。
- `images` には再生可能メディアを含めない。

## 5. UI

### 5.1 配置

`NoteCard` と `QuotePreview` で、画像グリッドの直後、リンクプレビューの前に
`InlineMediaPlayer` を再生可能メディアごとに縦に並べる。content-warning がある投稿では、
既存の画像と同じく警告を解除するまで表示しない。

### 5.2 待機表示（プレイヤー生成前）

- 黒背景の枠に再生ボタンを重ねて表示する。
- 動画のアスペクト比は `imeta` の `dim` を使う。ない場合は 16:9 とし、比率は 9:16〜2:1 の範囲に丸める。
  高さの上限は投稿カードで 360dp、引用で 180dp。
- ポスター画像（`imeta` の `image`、なければ `thumb`）は、画像プレビュー設定
  （`showImagePreviews`）が有効な場合だけ読み込む。無効のときは黒背景のままにする。
- 音声は高さ 120dp の枠に音符アイコンと再生ボタンを表示する。
- タップすると `ActiveMediaPlayback` に自分のキーを登録し、プレイヤーを生成して自動で再生を始める。

### 5.3 プレイヤー表示

`expect fun PlatformMediaPlayer(url, modifier, onInfo, onError)` でプラットフォームのプレイヤーを埋め込む。
再生・シーク・音量の操作はプラットフォーム標準のコントローラを使う。

- `onInfo(hasVideo, width, height)`: 読み込み完了時に通知する。`hasVideo == false` であれば
  音声用の高さ（120dp）に切り替える。`dim` がなく実寸が取れた場合は、その比率に更新する。
- `onError(message)`: 再生に失敗したときに通知する。

### 5.4 エラー表示

「このメディアは再生できません」と「ブラウザで開く」（`LocalUriHandler.openUri`）を表示する。
再試行はもう一度タップしたときに行う。

## 6. 再生中メディアの管理

アプリ全体で共有する `ActiveMediaPlayback`（`StateFlow<String?>`）を用意する。

- キーは「表示元（イベントID）＋ URL」とする。同じ動画が別の投稿カードに出ても区別するため。
- `activate(key)`: 現在のキーを置き換える。以前のプレイヤーは `isActive` が false になり、
  コンポジションから外れて解放される。
- `deactivate(key)`: 現在のキーが `key` と一致するときだけ null にする。後から有効になった別のプレイヤーは止めない。
- 有効なプレイヤーは、ライフサイクルが `STARTED` 未満になったとき（バックグラウンドへの移行など）に
  自分のキーで `deactivate` して解放される。

LazyColumn で画面外に出た項目はコンポジションから外れるため、プレイヤーも
`DisposableEffect`/`onRelease` で解放される。そのときに `deactivate` を呼び、
再び画面に入っても自動で再生が始まらないようにする。

## 7. プラットフォーム実装

### 7.1 Android

- 依存関係: `androidx.media3:media3-exoplayer`、`androidx.media3:media3-ui`（1.11.1）
- `AndroidView` で `PlayerView` を生成し、`ExoPlayer` は `remember` で1つ保持する。
  `onRelease`/`DisposableEffect` で `release()` する。
- `setAudioAttributes(..., handleAudioFocus = true)` で他アプリとオーディオフォーカスを調整する。
- `Player.Listener.onTracksChanged` で映像トラックの有無、`onVideoSizeChanged` で実寸、
  `onPlayerError` でエラーを通知する。

### 7.2 iOS

- `AVPlayer` と `AVPlayerViewController` を `UIKitViewController` で埋め込む。追加の依存関係は不要。
- 再生開始前に `AVAudioSession` のカテゴリを `playback` にし、消音スイッチがオンでも音が出るようにする。
- `AVPlayerItem.status` を一定間隔で確認する。`ReadyToPlay` になったら
  `tracksWithMediaType(AVMediaTypeVideo)` と `presentationSize` から情報を、
  `Failed` になったらエラーを通知し、確認を終える。K/N から KVO を使う複雑さを避けるためである。
- 解放時は `pause()` と `replaceCurrentItemWithPlayerItem(null)` を実行する。

## 8. テスト

commonTest に次を追加する。

- `MediaMetadataTest`: `mediaKind` の判定（mimeの優先、拡張子、クエリ・フラグメント、大文字小文字、不明な拡張子）
- `ParsedNoteContentMediaTest`:
  - mp4 URLが `playableMedia` に入り、`linkPreviewUrl` と `textContent` から除外される
  - 再生可能メディアの後ろにある通常のURLがリンクプレビューになる
  - 画像と動画が混在する場合の振り分け
  - `imeta` の `m video/mp4` を持つ拡張子なしURLが動画になり、`imeta` の情報が引き継がれる
  - 同じURLが重複しない
  - 本文に含まれない `imeta` は無視する
- `InlineMediaLayoutTest`: アスペクト比の既定値と丸め
- `ActiveMediaPlaybackTest`: 切り替え、自分以外のキーで `deactivate` しても止まらないこと

プラットフォームのプレイヤーは Android エミュレータと iOS シミュレータで手動確認する
（mp4 動画、音声のみの mp4、mp3、存在しないURL、スクロールでの解放、2本目を再生したときの1本目の停止）。
