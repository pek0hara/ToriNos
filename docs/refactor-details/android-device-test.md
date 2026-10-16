# Android実機の診断ログとテスト

更新日: 2026-10-17。

## 診断ログを有効にする

Androidの診断タグは`ToriNosRefactor`。通常は無効で、端末側で有効化する。本文・秘密鍵・プロフィール・フィルターのタグ値・リレーURLはこの診断ログへ出力しない。公開配送の追跡には公開イベントIDの先頭12文字を使う。iOS/Wasmでは今回の診断ログは無効。

```sh
adb devices -l
./gradlew :androidApp:assembleDebug --offline
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
sh scripts/android-refactor-log.sh
```

複数端末が接続されている場合は、スクリプトの第1引数でserial、第2引数で保存先を指定する。既存logcatは消去しない。Ctrl+Cで採取を終了し、診断タグをINFOへ戻す。アプリデータの消去・アンインストールは不要。署名不一致で更新できない場合は、データを消さず、対応する署名のビルドを用意する。

スクリプトを使わない場合:

```sh
adb shell setprop log.tag.ToriNosRefactor DEBUG
adb logcat -v threadtime -T 1 ToriNosRefactor:D AndroidRuntime:E '*:S'
# 別のターミナルでアプリを起動
adb shell am start -W -n com.nostr.torinos/com.nostr.torinos.MainActivity
# 検証後
adb shell setprop log.tag.ToriNosRefactor INFO
```

`[App] resumed diagnostics=v1`が出れば、今回のビルドと端末側設定が有効。採取を強制終了した場合は、最後のINFO設定を手動で実行する。

## 操作と確認するログ

| 操作・条件 | 確認するログと期待値 |
| --- | --- |
| 起動、バックグラウンド、再表示 | Appのresumed / paused。AndroidRuntimeのFATAL EXCEPTIONがない |
| 通知画面を開く・閉じる、もう一度開く | Notificationのvisibility / live-open / live-close。初回同期終了後、非表示でremaining=0。初回同期中はsync-endまでの保持を許す |
| 記事一覧・チャンネル履歴を読み込む | Fetchの同じtraceでstart / closed / end。失敗時はallAnswered=false。rangeComplete=falseは問い合わせ失敗と同義ではなく、範囲完了を未確認の状態 |
| 遅い返信元解決の間に本文を編集・別の入力へ移る | Replyのresolvedでaction=discard。inputCurrent=false / current=falseを確認。保存凍結・旧セッションではallowed=false。通常解決時はaction=apply |
| テスト用チャンネルの作成・編集、全失敗後の再試行 | ChannelPublishのprepared / attempt。再試行でeventが同じ、attemptが増える。二重操作はreuse-inflight。元データが変わればvalidation-rejected |
| 複数リレーへの公開 | Publishのreturnedの後も遅いrelay-resultが出る。success / failed / rejectedを確認。cancelledの場合は待機が残らない |
| Liveの信号欠落・handler例外（自然発生・専用テスト環境） | Recoveryのneeded / start / complete。acknowledged=trueで解消。不完全な取得はincompleteとなり、未解消のまま残る |

公開・リアクション等の送信確認にはテスト用アカウントと送信先を使う。通常の閲覧テストだけで公開配送や故障条件まで確認したことにはしない。無線ADB中にWi-Fiを切るとログ接続も失うため、通信切断の試験はUSB接続で行う。

## 今回の実機実施結果

- 接続端末: ASUS_AI2302、Android 15、無線ADB。
- `:androidApp:assembleDebug :composeApp:iosSimulatorArm64Test :composeApp:compileKotlinWasmJs --offline`: BUILD SUCCESSFUL。iOS共通テスト1,635件、失敗・エラー・skip 0。
- `adb install -r`: Success。データ消去なしで既存デバッグ版を更新した。
- Activityのコールド起動: Status ok、起動1,745ms。プロセスの生存を確認。
- `App resumed diagnostics=v1`を専用タグで取得できた。
- 初回通知の6リレーへの有限取得はどちらもDeadlineExpired（10秒、受信0件、allAnswered=false / rangeComplete=false）。正常取得を確認した結果ではない。
- この不完全取得で`commitBaseline=false`となり、Liveを`count=2 remaining=0`で解放し、`sync-end initial=false visible=false live=0`となることを確認した。
- 採取中にAndroidRuntimeのクラッシュログはなかった。ログは`/tmp/torinos-refactor-android-device.log`へ保存し、診断タグはINFOへ戻した。
- 端末はロック画面のため、画面往復・遅い返信・テスト公開・強制的な障害条件・描画の操作確認は未実施。ロック解除後に上の手順で継続できる。

ソースの空白チェックとログ採取スクリプトの`sh -n`も成功した。
