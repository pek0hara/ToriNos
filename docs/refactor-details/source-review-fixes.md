# リファクタ後のソースレビューと修正

実施日: 2026-10-16。対象はR1〜R7で変更・追加したソース、既存呼び出し元、関連テスト。実装前からあった作業ツリーの変更は保持した。

通信の終了・配信・再同期、投稿入力の世代、共有集計の範囲、フィード投影、チャンネル公開とカードの移行を確認し、次の5件を修正した。

| 優先度 | 指摘・発生条件 | 修正 | 回帰テスト |
| --- | --- | --- | --- |
| P1 | 返信元の非同期解決中に同じ入力を編集しても、入力世代が変わらず古い返信結果を適用する。切り替え保存による入力凍結中も適用できる | AppSessionCoordinatorの適用条件にPostViewModel.editorRevisionを追加。ComposerInputViewModelで入力凍結中の適用を拒否 | ComposerInputBoundaryTest.delayedReplyCannotReplaceEditedInputWithinSameGeneration / delayedReplyCannotReplaceInputWhileItIsBeingSaved |
| P1 | 保存済み公開操作の再試行ではvalidateを省略し、購読等で元メタデータが更新された後も旧操作を再送できる。検証のCancellationExceptionも失敗へ変換する | ChannelPublicationServiceは操作検索時と配送開始前にセッションとvalidateを再確認し、キャンセルを再送出 | ChannelPublicationServiceTest.retryRevalidatesSourceBeforeSendingTheSavedEvent / validationCancellationIsNotConvertedToPublishFailure |
| P1 | 配送処理が結果を返す前に例外となる、または配送scopeが終了すると、first-successのCompletableDeferredが未完了のまま残る | RelayPublishCoordinatorは通常の例外をリレー別失敗にし、配送Jobのキャンセルを待ち合わせへ通知 | RelayCoordinationTest.sendExceptionFinishesInsteadOfLeavingAwaiterPending / cancelledDeliveryScopeDoesNotLeaveAwaiterPending |
| P2 | 配送結果をまとめるときにrejectedRelaysを落とし、呼び出し元で恒久拒否と一時失敗を区別できない | RelayPublishCoordinatorで拒否集合も集約し、最初の成功・全失敗の双方で返す | RelayCoordinationTest.rejectedRelayClassificationSurvivesAggregation |
| P2 | 通知のLive購読を開く途中で失敗するとinitialActiveが残り、再度表示した後に非表示にしても購読を閉じない | NotificationSyncCoordinatorはLiveの開始成功後に初回同期の所有フラグを設定 | NotificationSyncCoordinatorTest.failedLiveStartupDoesNotKeepAnInitialOwnerAfterRetry |

## 検証結果

追加した8件を含む最終コードで、以下がすべて成功した。

- iOS Simulator Arm64: 1,635件。失敗・エラー・skip 0。
- Wasm Browser: 1,588件。失敗・エラー・skip 0。
- Androidコンパイル、直接プロフィール購読の構造チェック。
- git diff --check、追加ファイルの行末空白、詳細設計文書の相対リンク確認。

```sh
./gradlew :composeApp:iosSimulatorArm64Test :composeApp:wasmJsBrowserTest :composeApp:compileAndroidMain :composeApp:verifyNoDirectProfileSubscriptions --offline
```

最終実行はBUILD SUCCESSFUL（1分31秒）。ログは`/tmp/torinos-refactor-review-final-checks.log`、JUnit XMLは`composeApp/build/test-results/`配下。プラットフォーム間で共通テストを含むため件数は合算しない。既存の非推奨等の警告は残る。実機の通信・表示操作と性能計測は本レビューに含めない。
