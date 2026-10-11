# 起動直後にリレーへ1つもつながらず、フィードが止まる不具合

## 症状
- Android 実機のコールドスタートで、ときどきフィードが回転アイコンのまま止まる。リレーに1つもつながらず、40秒以上たっても戻らない。
- 6リレーにしてから出始めた。画面録画を回している（端末が忙しい）と起きやすい。1/5〜1/20 程度。

## 調べたこと
1. `NostrRepository` の状態ロック（コルーチンの `Mutex`）が、ロックを渡された待ち手（バッジ取得）のまま止まっていた。待ち手は「渡された」状態なのに一度も動いていない。
2. ロックを `SynchronousLock`（スレッドのロック）に変えると、止まる頻度がかえって増えた（52回中48回）。ロックは原因ではなかった。
3. 止まったときのスレッドを出すと、`DefaultDispatcher` のスレッド8本が `runBlocking` の中で待っていた。呼び出し元は Ktor の `generateNonce`（WebSocket 接続の開始時）。

## 原因
- JVM 版 Ktor 3.3.3 の `generateNonce` は、用意済みのノンスがないと `runBlocking { seedChannel.receive() }` でスレッドを止めて待つ。
- ノンスを作るコルーチン（`nonceGeneratorJob`）は `Dispatchers.Default` で、最初の呼び出しで遅れて起動する。
- `NostrRelay.connect` は `NostrRepository` のスコープ（`Dispatchers.Default`）で接続を始める。起動直後は購読リレー6本と、リレーリスト同期の一時接続が同時に接続し、Default の枠（CPU 数 = 6）がすべて `runBlocking` で埋まる。
- するとノンスを作る側が Default で動けず、接続は完了しない。Default のほかの処理（ロックの受け渡し後の再開、受信処理など）もすべて止まる。

## 修正
- WebSocket 接続を `relayConnectionDispatcher` で動かす。Android は `Dispatchers.IO`。IO のスレッドが `runBlocking` で止まっても Default の枠は使わないので、ノンスを作る側が動ける。
- iOS（Darwin エンジン）と Web は接続時にスレッドを止めないので `Dispatchers.Default` のまま。
- 接続後の送受信ループも IO のスレッドで動く。ここで行うのは受信した文字列の解析と `messages` への受け渡しまでで、購読の処理は `NostrRepository` のスコープ（Default）で動く。

## 確認
- 単体テストでは Ktor の内部の待ちを再現できないため、実機のコールドスタートを繰り返して確かめる（修正前は 1/5〜1/20 で止まっていた条件で 50 回以上）。
