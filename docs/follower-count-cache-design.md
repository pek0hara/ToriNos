# フォロワー数の保存と表示 設計

## 目的
- フォロワー数を自動では数えないまま、前に分かった数をすぐ出す
- フォロワー一覧画面で取った結果を捨てずに使う

## 現状
- プロフィールのフォロワー枠はダウンロードアイコン。押すと kind 3（`#p`=対象）を取って数える
  - 他人: `UserProfileViewModel.loadFollowersCount`（limit 500、上限到達で「+」）
  - 自分: `MyProfileViewModel.fetchFollowers`（limit 1000、「+」なし）
- フォロワー一覧（`FollowListViewModel` の FOLLOWERS）も limit 1000 で同じものを取るが、件数は捨てている
- どれも画面を閉じると結果が消える。一度取った後に数え直す手段がない
- 通知の既知フォロワー一覧（`LocalNotificationStore.knownFollowers`）は初回 100 件で、追加しかしないので数そのものには使わない

## 保存: `FollowerCountStore`
- 1件 = `FollowerCountSnapshot(count, limited, countedAt)`。pubkey ごと、アカウントに依らない（公開情報なので）
- `LocalSettingsStorage` の1キー（`follower_counts_v1`）に JSON で全件。最大 200 件、`countedAt` の古いものから消す
- 起動後に初めて使うときに読み込み、以降はメモリの `StateFlow<Map<String, FollowerCountSnapshot>>` を正とする。書き込みは Mutex で直列化
- 読み書きと時刻は差し替え可能にしてテストする（`AccountBadgeStore` と同じ形）

### 書く条件（純関数 `shouldRecordFollowerCount`）
| 終わり方 | 件数 | 書く |
|---|---|---|
| EOSE が来た / 時間切れ | 1以上 | 書く（前回より少なくても上書き） |
| EOSE が来た / 時間切れ | 0 | 書かない（落ちているリレーかもしれない） |
| 画面を閉じて中断 | — | 書かない |

`limited` は受け取ったイベント数が limit 以上のとき。

## 数える処理の共通化: `FollowerCountLoader`
自分・他人のプロフィールで重複している「購読→数える→EOSEか時間切れまで待つ」をまとめる。

- 入力: pubkey、subId、limit、relayUrl（任意）、scope の起動関数、store
- 出力: `StateFlow<FollowerCountLoadState>`（`liveCount`、`isLoading`）
- `start()`: 読み込み中なら何もしない。前回の集計をクリアし、購読を閉じてから張り直す
- 終わったら（中断以外）`shouldRecordFollowerCount` が真なら store に記録
- `close()`: 購読を閉じる。ViewModel の `onCleared` から呼ぶ
- limit は自分・他人とも 500 にそろえる（自分も「+」が付く）

`FollowListViewModel` は一覧のための処理なので Loader は使わず、`awaitFollowerRelayCompletion` が終わった後（中断でないとき）に `knownPubkeys.size` と受信イベント数（limit 1000 で `limited`）を store に記録する。

## 表示
`followerStatDisplay(stored, load)`（純関数）で状態を決める。

| 状態 | 数字の位置 | 2行目 | 枠のタップ |
|---|---|---|---|
| `Unknown`（保存なし・未取得） | 「—」薄い色 | 空（高さは確保） | 数える |
| `Counting`（保存なし・取得中） | 届いた数 + 右に小さいスピナー | 空 | 一覧を開く |
| `Known`（保存あり） | 数（`limited` なら「+」） | 「↻ 3日前」タップで数え直し | 一覧を開く |
| `Known(refreshing)` | 保存値のまま + スピナー | 「↻ 3日前」（押せない） | 一覧を開く |

- 2行目は常に高さを取っておき、数え終わったときに行の高さが変わらないようにする
- スピナーはサイズ0のレイアウトで数字の右に置き、数字の中央ぞろえをずらさない
- 経過時間 `followerCountAgeLabel(countedAt, now)`: 1分未満「たった今」/「n分前」/「n時間前」/「n日前」
  - 1時間以内は隠す案もあったが、隠すと数え直しの入口も消えるので常に出す
- 「↻ 3日前」の行は高さ 28dp・左右 8dp の余白を押せる範囲にする（列の高さを大きく変えない範囲）

## テスト
- `FollowerCountStore`: 記録→読み込みで復元／200件を超えると古いものから消える／少ない値で上書き／壊れた保存値は空として扱う
- `shouldRecordFollowerCount`: 上の表のとおり
- `followerCountAgeLabel`: 境界（59秒・60秒・59分・1時間・23時間・24時間）
- `followerStatDisplay`: 4つの状態
- `isFollowerCountLimited`: limit ちょうど／未満

## 通知のフォロー取得との連携
通知はフォロー通知のために自分宛ての kind 3 を差分で取っている。これを自分のフォロワー数の更新に使う。

- **新規フォロワーで +1**: `handleFollowEvent` で既知一覧にいない人が来たら（基準の一覧があるときだけ）`recordNewFollower(自分)`。
  数えた時刻は変えない。保存値が無ければ何もしない。ミュート中の人も数える
  - 外した人は `#p` では見えないので反映されない。数え直しか一覧を開いたときに正しい数に戻る
- **全部数えたら既知一覧を補う**: `record` に数えた相手の pubkey を渡し、`completedCounts` に流す。
  通知 ViewModel は自分の分を受けたら（基準の一覧があるときだけ）既知一覧に足して保存する
  - 初回 100 件の基準に入らなかった古いフォロワーが kind 3 を出し直したとき、新規と誤って通知するのを防ぐ
  - 全部数えた直後に重なりの1日分で同じ人の kind 3 が届いても、既知になっているので二重に +1 しない
- **基準を作るときの件数を 500 に**: `initialFollowSyncLimit(since)`。since なし（基準を作る）なら 500、差分なら 100。
  kind 3 は1件が大きいので、差分のときは増やさない

## 実装メモ
- `FollowerCountStore.kt`（保存と純関数）、`FollowerCountLoader.kt`（数える処理）、`FollowerStatCell.kt`（表示）
- `UserProfileState` / `MyProfileState` は `followersLoaded`・`isFollowersCountLimited` をやめ、`storedFollowerCount` を持つ
- 保存値は画面を開いた直後に非同期で読み込む。読み込むまでの一瞬は「—」になる

## 手順
1. 純関数と Store のテストを書いて失敗を確認
2. Store・純関数を実装
3. Loader を作り、2つのプロフィール ViewModel を置き換える
4. FollowListViewModel から記録
5. `UserStatCell` の表示を差し替え
6. Android 実機（またはエミュレーター）で 未取得→数える→戻って一覧→プロフィール の流れを確認
