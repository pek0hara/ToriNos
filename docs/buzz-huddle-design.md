# Buzz のハドル（音声通話） 設計

- 作成日: 2026-11-22
- ステータス: 設計中（未着手）
- 目的: Buzz のチャンネルと DM で、ハドル（音声通話）を始める・参加する・聞く・話す・ミュートする・抜けることができるようにする。チャンネルの中では、ハドルの開始と終了をカードで表示する。
- 対象: Android と iOS だけ。**Web（Wasm）は対象外**にする。Web では、ハドルのカードは表示するが、参加ボタンは出さない。
- 調査対象（2026-11-22 時点で取得した main）:
  - [block/buzz](https://github.com/block/buzz) `0e7fd48`（Apache-2.0）
    - `mobile/HUDDLES.md`
    - `mobile/lib/shared/huddle/`（`huddle_wire.dart`、`huddle_auth.dart`、`huddle_transport.dart`、`huddle_session.dart`、`huddle_media.dart`）
    - `mobile/lib/features/channels/`（`mobile_huddle_controller.dart`、`channel_huddle_actions.dart`、`huddle_lifecycle_provider.dart`、`huddle_channel_filter.dart`、`channel_detail_page/huddle_card_summary.dart`）
    - `mobile/android/.../HuddleAudioEngine.kt`、`mobile/ios/Runner/HuddleAudioEngine.swift`
    - `crates/buzz-relay/src/audio/`（`handler.rs`、`join.rs`、`wire.rs`）、`crates/buzz-audio-client/`
  - [block/buzz-app](https://github.com/block/buzz-app) `4363e10`（Apache-2.0）: `docs/huddles.md`、`src/features/huddle/`
- 関連: `docs/nip29-buzz-design.md`（2.4 でハドルの kind を「表示対象外」、段階1の対象外に「ハドル・LiveKit」としていた。LiveKit は現在使われていない）、`docs/buzz-presence-design.md`、`docs/buzz-admin-design.md`

## 1. Buzz の仕様

ハドルは、**Nostr のイベント（開始・終了・参加者の記録）** と、**音声用の別の WebSocket（Opus の音声フレーム）** の2つでできている。公式のデスクトップ版とモバイル版は同じプロトコルを使う。

### 1.1 部屋（裏のチャンネル）

ハドルごとに、音声用の非公開チャンネルを1つ作る。このチャンネルを、ここでは「裏のチャンネル」と呼ぶ。

| 項目 | 値 |
| --- | --- |
| kind | 9007（作成） |
| チャンネルID | 新しく作った UUID v4 |
| `name` | `huddle-<IDの先頭8文字>` |
| `visibility` | `private` |
| `channel_type` | `stream` |
| `ttl` | `3600`（1時間で期限切れ） |
| `about` | `Buzz Huddle (buzz.huddles/v1)\nparent:<親チャンネルのUUID>` |

- タグの並びは `buildCreateChannelTags` と同じで、`h`、`name`、`visibility`、`channel_type`、`about`、`ttl` の順。ToriNos の `buildBuzzCreateChannel` には `ttl` が無いので足す。
- 作った人は、作成が受理された時点で裏のチャンネルのメンバー（owner）になる。
- 公式アプリは、裏のチャンネルを普通のチャンネル一覧に出さない。判定には次の2つを使う（`huddle_channel_filter.dart`）。
  - **強い判定**: 作成した本人の kind 9007 で、`visibility=private`、`channel_type=stream`、`about` が上の形になっているもの。
  - **弱い判定**: 名前が `huddle-<IDの先頭8文字>` で、`private`・`stream` で、`ttl` を持つもの。表示だけに使う。

### 1.2 開始・終了・参加者のイベント（親チャンネルに流れる）

| kind | 署名する人 | タグ | content | 意味 |
| --- | --- | --- | --- | --- |
| 48100 | 始めた人 | `["h", 親]` | `{"ephemeral_channel_id": 裏のID}` | 開始 |
| 48101 | リレーの `self` 鍵 | `["h", 親]`、`["p", 参加者]` | `ephemeral_channel_id`、`generation`、`admission_id`、`roster_revision` など | 参加した（音声の接続が受理された） |
| 48102 | リレーの `self` 鍵 | 同上 | 同上 | 抜けた |
| 48103 | 始めた人（リレーのこともある） | `["h", 親]` | `{"ephemeral_channel_id": 裏のID}` | 終了 |

- 48101 と 48102 は、リレーが音声の接続を受け付けたときと切れたときに、自分で書き込む。クライアントは送らない。
- 公式のカードは次のように表示する（`huddle_card_summary.dart`）。
  - 参加者: リレーの鍵で署名された 48101/48102 を、`generation`・`admission_id`・`roster_revision` の順で並べ直して求める。
  - 終了: 始めた人かリレーの 48103 で判断する。
  - 経過時間: 開始から終了（終わっていなければ今）までを出す。
  - 自分が接続中のときは、音声の WebSocket から届く参加者一覧（1.4）を優先する。
- 取得は、親チャンネルの `#h` で kinds `[48100, 48101, 48102, 48103]` を購読する。履歴は、開始・終了と参加者の記録とで上限を分けて取る（開始・終了は200件、参加者は200件取得・2000件保持）。

### 1.3 始める・参加する・抜ける

**始める**（`MobileHuddleController._start`）

1. 裏のチャンネルを作る（9007）。
2. 親チャンネルに 48100 を送る。
3. 音声の WebSocket で接続する（1.4）。
4. 途中で失敗したら、48100 を送った後なら 48103 を送り、裏のチャンネルをアーカイブする（9002）。どちらも、できる範囲で行う。

**参加する**

- 48100 の `ephemeral_channel_id` に、音声の WebSocket で接続するだけでよい。事前の 9021（参加要求）は要らない。
- 親チャンネルのメンバーであれば、リレーが接続を受け付けたときに裏のチャンネルへ自動で追加する（`MembershipAdmission::AutoAddRequired`）。
- 親チャンネルのメンバーでなければ、`{"type":"error","message":"not a member"}` が返る。

**抜ける**（`leave` と `_finishLeaveLifecycle`）

1. 先にマイクと音声の WebSocket を止める。人数の確認は数秒かかることがあり、その間マイクを開いたままにしないため。
2. 裏のチャンネルの最新の kind 39002 をリレーから取り直し、役割が `bot` でないメンバーを数える。取れなかったときは「2人」とみなし、ハドルを終わらせない側に倒す。
3. 数えた人数が1人以下（自分だけ）なら、48103 を送り、裏のチャンネルをアーカイブする（9002）。
4. ほかに人がいれば、裏のチャンネルから退出する（9022）。ただし DM のハドルでは、終わった後も記録を読めるように退出しない。

### 1.4 音声の WebSocket（制御）

1. `wss://<リレーのホスト>/huddle/<裏のID>/audio` に接続する。
   - メインの Nostr の WebSocket とは別に張る。メインの接続は JSON の配列しか受け付けず、バイナリのフレームを捨てるため。
   - 接続のタイムアウトは8秒、受理までのタイムアウトは5秒、ping の間隔は30秒（公式モバイル版の値）。
2. リレーから `{"type":"challenge","challenge":"…"}` が届く。
3. NIP-42 の kind 22242 を署名し、`{"type":"auth","event":…,"parent_channel_id":"<親のUUID>","protocol_version":2}` を送る。
   - 22242 の `relay` タグには、音声の URL ではなく**メインのリレーの WebSocket URL** を入れる。
4. 次のどれかが返る。
   - `{"type":"joined","revision":n,"pubkey":…,"peer_index":i,"epoch":e,"peers":[{"pubkey","peer_index","epoch"}…]}`: 受理された。これを受け取るまでは「接続中」とは扱わない。
   - `{"type":"error","code"?: …,"message": …}`: 拒否された。`code` は `room_full`、`room_ended`、`upgrade_required`、`join_rejected` など。
5. 接続している間は、次のメッセージで参加者一覧を更新する。
   - `joined`: ほかの人が入った。
   - `left`: 抜けた。
   - `roster`: 一覧をまとめて差し替える。
   - いずれも `revision` を持つ。
6. 自分から抜けるときは `{"type":"leave"}` を送ってから閉じる。
7. 受理された後に音声の接続だけが切れたときは、`0, 100, 250, 500, 1000, 2000, 2000` ms の間隔で、音声の接続だけを張り直す。その間もマイクと再生は止めない。

補足:

- リレーは protocol v1〜v3 に対応している。ただし、**部屋は最初に入った人のバージョンに固定**され、ほかのバージョンで入ろうとすると `upgrade_required` で拒否される。公式のクライアントはすべて v2 なので、ToriNos も v2 を使う。
- 公式モバイル版は、接続時に `Nostr-Federated-Identity` ヘッダーを付けることがある。これは NIP-FI（外部 ID）のコミュニティだけで使う。ToriNos は NIP-FI のコミュニティを対象外にしているので、付けない。

### 1.5 音声フレーム（protocol v2）

- 形式: Opus、48kHz、モノラル、20ms（960サンプル）ごとに1フレーム。
- 送る: `8バイトのヘッダー | Opus のデータ`
- 受け取る: `peer_index(u8) | 8バイトのヘッダー | Opus のデータ`
- ヘッダー（ビッグエンディアン）:

| 位置 | 型 | 内容 |
| --- | --- | --- |
| 0–1 | u16 | 連番 |
| 2–5 | u32 | 48kHz 単位のタイムスタンプ |
| 6 | i8 | 音量（dBov、-127〜0。範囲外はリレーが -127 として扱う） |
| 7 | u8 | フラグ。bit0 = DTX（無音）。ほかのビットは無視する |

- 1フレームは、peer_index を除いて4096バイトまで。
- 音量が **-55 dBov より大きい**ときに「話している」とみなし、表示は50ms ごとに更新する。

### 1.6 公式アプリの端末側の処理

| | Android | iOS |
| --- | --- | --- |
| 録音 | `AudioRecord`（48kHz モノラル、通話向けの入力）+ `AcousticEchoCanceler`・`NoiseSuppressor`・`AutomaticGainControl` | `AVAudioEngine`（Voice Processing I/O でエコーを除去） |
| Opus | `MediaCodec`（`audio/opus`）で変換と再生用の戻し | `AVAudioConverter`（`kAudioFormatOpus`） |
| 再生 | 参加者ごとに `AudioTrack` を1つ作り、OS の通話用ミキサーで混ぜる | `AVAudioEngine` で参加者ごとに再生して混ぜる |
| 揺らぎ吸収 | 3フレーム溜まってから再生を始め、参加者ごとに最大10フレームまで溜める | 同じ |
| 出力先 | 最初は受話口。スピーカーは利用者が切り替える。Bluetooth などは OS の切り替えに任せる | 同じ |
| バックグラウンド | 画面を離れたら抜ける | 受理された後は、画面ロック中も続く（`UIBackgroundModes: audio`） |
| 対応していない端末 | Opus の変換と戻しの両方ができなければ参加させない（`supportsFullDuplexOpus`） | 同じ |

- 録音した生の音声（PCM）は OS ごとのコードの中だけで扱い、Flutter 側には Opus に変換したフレームだけを渡す。
- 公式モバイル版でもやっていないこと: エージェント（ボット）の音声、文字起こしと読み上げ、録音、バックグラウンドでの着信、細かい機器の設定、絵文字リアクション（kind 24810。実装はあるが、ボタンは隠している）。

## 2. ToriNos での方針

### 2.1 対象

- Buzz のコミュニティのチャンネルと DM。標準の NIP-29 リレーにはハドルの仕組みが無いので、対象外。
- Android と iOS。Web は対象外（2.5）。
- できること: 始める、参加する、聞く、話す、ミュート、スピーカーの切り替え、抜ける。参加者一覧と話している人の表示。

### 2.2 公式に合わせること

1章の内容は、すべて公式モバイル版に合わせる。特に次の点は、公式のアプリと同じハドルに入るために必要。

- protocol v2、AUTH の形（`relay` タグはメインのリレーの URL）、`parent_channel_id`
- 裏のチャンネルの作り方（9007 のタグと `about` の形）
- 抜けるときの判定（39002 を取り直して数える。取れなければ終わらせない）

### 2.3 Android の対応端末

ToriNos の minSdk は 24 だが、`MediaCodec` の Opus **エンコーダー**は Android 10（API 29）からしか使えない。そのため、次のようにする。

- 起動時に、Opus の変換と戻しが両方できるかを `MediaCodecList` で調べる（公式と同じ）。
- できない端末では、ハドルのカードと参加者は表示するが、参加ボタンは押せない状態にし、「この端末はハドルに対応していません」と出す。
- 聞くだけの参加（デコーダーは API 21 から使える）は、段階5の後で必要になったら考える。音声を送らない参加者をリレーがどう扱うかは確かめていない（未確定事項 3）。

### 2.4 バックグラウンド

公式に合わせる。

- **Android**: 画面を離れたら（`ON_STOP`）抜ける。フォアグラウンドサービスを使わないので、Google Play での「マイクを使うフォアグラウンドサービス」の申告も要らない。
- **iOS**: 受理された後は、画面ロック中もバックグラウンドでも続ける。Info.plist に `UIBackgroundModes: audio` を足す。メインのリレー接続は、今まで通りバックグラウンドで切れてよい。音声の WebSocket は別の接続なので、そのまま続く。
- どちらも、アカウントやコミュニティを切り替えたら抜ける。
- iOS では、通話中に抜ける操作をしたときにメインのリレーが切れていることがある。その場合、9022 は再接続した後に送る（公式の `_pendingMembershipLeaves` と同じ）。48103 と 9002 は、公式と同じく送れる範囲で送り、失敗しても送り直さない。終わらせ損ねた裏のチャンネルは、`ttl`（1時間）で期限切れになる。

### 2.5 Web を対象外にする

- 公式にもブラウザ版の音声処理は無い。デスクトップ版でも、Opus の変換は Rust 側で行っている。
- Web で作るには WebCodecs と AudioWorklet を Wasm から扱う必要があり、Safari の対応も不確かなので、作らない。
- Web では、カード（開始・終了・参加者・経過時間）は表示し、「ハドルはアプリで参加できます」と出す。
- コードは `expect`/`actual` で分け、Web の `actual` は「対応していない」を返すだけにする。

### 2.6 やらないこと

- 絵文字リアクション（kind 24810）
- エージェント・ボットの呼び出し、文字起こし・読み上げ
- DM のハドルの着信音・着信画面（公式モバイル版も、バックグラウンドの着信はやっていない）
- 「この部屋に人を追加する」（`Add someone`。デスクトップ版だけの機能）
- 録音、機器の細かい選択（マイクの選択など。スピーカーと受話口の切り替えだけ作る）
- 標準の NIP-29 リレーと NIP-FI のコミュニティ
- Web での音声

## 3. 構成

```
commonMain  chat/BuzzHuddle.kt           イベントの組み立て・読み取り、カードの状態の計算（純粋関数）
            chat/HuddleWire.kt           ヘッダーとフレームのエンコード・デコード（純粋関数）
            chat/HuddleControl.kt        制御メッセージ（challenge/auth/joined/left/roster/error/leave）の JSON
            chat/HuddleJitterBuffer.kt   参加者ごとの揺らぎ吸収（3で再生を始め、上限は10）
            chat/HuddleTransport.kt      AUTH、受理、張り直し、状態。接続そのものは HuddleSocket を通す
            chat/HuddleSocket.kt         音声の WebSocket の interface と Ktor での実装（テストでは偽物に差し替える）
            chat/HuddleSession.kt        1つのハドルの状態（参加者・話している人・ミュート・接続状態）
            chat/HuddleController.kt     始める・参加・抜ける（Nostr のイベントと音声をまとめる）
            chat/HuddleMedia.kt          expect: 対応状況、録音の開始・停止、Opus の送受信、ミュート、スピーカー
            ui/chat/HuddleCard.kt        タイムラインのカード
            ui/chat/HuddleCallScreen.kt  通話中の全画面と最小化バー
androidMain chat/HuddleMedia.android.kt  AudioRecord、MediaCodec、AudioTrack、AudioManager（通話モード、スピーカー）
iosMain     chat/HuddleMedia.ios.kt      AVAudioSession、AVAudioEngine（Voice Processing）、AVAudioConverter
wasmJsMain  chat/HuddleMedia.wasmJs.kt   isSupported = false
```

- 生の音声（PCM）は `HuddleMedia` の中で完結させ、共通コードとの間では Opus のフレームだけをやり取りする（公式と同じ境界）。
- 音声処理はリアルタイムで動かす。Android は専用のスレッド（`Process.THREAD_PRIORITY_URGENT_AUDIO`）、iOS は AVAudioEngine のコールバックで処理する。コルーチンの Dispatchers は使わない。
- 共通コードとの受け渡しは、上限付きのキューで行う。溢れたら古いものから捨てる。
- 音声の WebSocket は `Dispatchers.IO` で動かす（Ktor の WebSocket は nonce の生成で Default のスレッドを使い切ることがあるため）。
- 公式のコードを移したり参考にしたりした箇所は、Apache-2.0 の表記をサードパーティの告知（`4092616` で追加したもの）に足す。

### 3.1 権限・設定

- Android: `RECORD_AUDIO`（実行時に許可を求める。許可は「参加」を押したときに求める）、`MODIFY_AUDIO_SETTINGS`。
- iOS: `NSMicrophoneUsageDescription`、`UIBackgroundModes: audio`。App Store の審査メモに、バックグラウンドで音声を使う理由（通話を続けるため）を書く。

### 3.2 既存の画面への組み込み

- **チャンネル・DM の見出し**: ヘッドホンのボタンを置く。
  - 進行中のハドルがあれば参加する。無ければ始める。
  - 接続中は、ボタンの見た目を「接続中」に変える。
- **タイムライン**: 48100 をカードで表示する。今は表示対象外にしている。
  - 終わっていれば「終了・経過時間」、進行中なら参加者のアバターと「参加」を出す。
  - 48103 は、対応する 48100 のカードがあればそちらにまとめ、1行には出さない（公式の `isCollapsedHuddleEnding`）。
  - 48101 と 48102 はタイムラインに出さない。
- **チャット一覧**: 裏のチャンネル（1.1 の判定）を出さない。既読数や通知の対象にもしない。
- **通話中**: 全画面と、画面の下に出す最小化バーを用意する。最小化バーは、ほかの画面へ移動しても表示を続ける。
  - 全画面: 参加者（話している人に輪）、ミュート、スピーカー、最小化、抜ける
  - 最小化バー: 参加者、ミュート、抜ける

## 4. 段階

各段階で、先にテストを書いて失敗を確かめてから実装する。

1. **表示だけ**（全プラットフォーム。Web を含む）
   - 48100〜48103 の読み取りとカードの状態の計算、タイムラインのカード
   - 裏のチャンネルをチャット一覧から隠す
   - 参加ボタンはまだ出さない
2. **プロトコル層**（commonMain）
   - `HuddleWire`、`HuddleControl`、`HuddleJitterBuffer`、`HuddleTransport`
   - 偽の `HuddleSocket` を相手に、AUTH から受理、参加者一覧の更新、張り直しまでを試す（Ktor の MockEngine は WebSocket に対応していないため、接続を interface で差し替える）
3. **Android の音声**
   - `HuddleMedia.android`、権限、参加・抜ける、通話画面
   - 実機で、公式のデスクトップ版・モバイル版と双方向に話せるかを確認する
4. **始める・終わらせる**
   - 9007（`ttl` 付き）、48100、抜けるときの判定（39002）、48103、9002、9022
   - 失敗したときの後始末
5. **iOS の音声**
   - `HuddleMedia.ios`、AVAudioSession の割り込み（電話の着信など）、バックグラウンド、画面ロック中の確認
6. 回帰テストの手順（`docs/regression-test.md`）にハドルを足す

## 5. テスト（先に書く）

- **`HuddleWire`**
  - ヘッダーのビッグエンディアン（リレーの `parse_reads_network_byte_order` と同じバイト列を使う）
  - 8バイト未満は拒否する
  - 音量が範囲外なら -127 にする
  - DTX のフラグを読み、予約ビットは無視する
  - 4096バイトを超えたら拒否する
  - 受け取るときの peer_index を読む
- **`HuddleControl`**
  - `auth` の形（`parent_channel_id`、`protocol_version: 2`）
  - 22242 の `relay` タグが音声の URL ではなくメインのリレーの URL であること
  - `joined`、`left`、`roster` を読み、`revision` が古いものは捨てる
  - `error` の `code` を種類に分ける
- **`HuddleJitterBuffer`**
  - 3フレーム溜まるまで再生しない
  - 上限10を超えたら古いものから捨てる
  - 連番が戻ったり重複したりしたものを捨てる
  - u16 の連番が一周しても正しく並ぶ
- **`HuddleTransport`**（偽の `HuddleSocket` と、仮想時間の `runTest` を使う）
  - challenge → auth → joined で「接続中」になる
  - `error` で失敗になる
  - 受理までのタイムアウト
  - 受理後に切れたら張り直し、その間も「接続中」を保つ
  - 張り直しを使い切ったら失敗にする
  - `leave` を送ってから閉じる
- **`BuzzHuddle`**
  - 9007 のタグの並び（`ttl` と `about` の形）
  - 48100 と 48103 の形
  - カードの状態: 参加者を `generation`、`admission_id`、`roster_revision` から求める。リレーの鍵でない 48101 は捨てる
  - 終了の判定（始めた人かリレーの 48103 だけを使う）
  - 裏のチャンネルの判定（強い判定と弱い判定）
- **`HuddleController`**（偽の送信先と偽の `HuddleMedia` を使う）
  - 開始の順番と、途中で失敗したときの後始末
  - 抜けるときの判定: 1人なら 48103 と 9002、2人以上なら 9022、DM なら退出しない、39002 が取れなければ終わらせない
  - マイクを、人数の確認より先に止める

## 6. 確認（実機）

- 公式のデスクトップ版・モバイル版と同じハドルに入り、双方向に話せること
  - ToriNos で始めたハドルに、公式から入る
  - 公式で始めたハドルに、ToriNos から入る
- 受話口とスピーカーの切り替え、有線・Bluetooth のイヤホン
- エコー（スピーカーで話したときに、相手側に自分の声が返らないか）
- 抜けるときの判定が公式と同じになること（最後の1人が抜けたら、公式のカードも「終了」になる）
- Android: 画面を離れたら抜けること、Android 9 以下の端末で参加できないと出ること
- iOS: 画面ロック中も続くこと、電話がかかってきたときの割り込みと再開
- 回線の切り替え（Wi-Fi とモバイル回線）で、音声の接続だけが張り直されること

## 7. 未確定事項

1. 公式以外のクライアントから音声の接続（`/huddle/.../audio`）を使えるか。リレーのコードに、クライアントを限定する仕組みは見当たらないが、実際のコミュニティでは確かめていない。段階2の後で最初に確かめる。
2. 公式の `HuddleAudioEngine.kt`（925行）と `.swift`（1019行）を、どこまでそのまま参考にするか。ライセンスは Apache-2.0 なので、移すことはできる。
3. 聞くだけの参加（2.3）を、リレーが普通の参加者として扱うか。
4. protocol v3 への移行。部屋が最初の人のバージョンに固定されるので、公式が v3 に上げると、ToriNos が v2 のまま後から入ると拒否される。`upgrade_required` を受け取ったら「アプリの更新が必要」と出し、公式の変更を追う。
5. DM のハドルで、退出しない（記録を読むため）ことが、ToriNos の DM の表示とどう関わるか。
