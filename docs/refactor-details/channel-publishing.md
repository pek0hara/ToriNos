# R6: チャンネル公開処理の詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

ChannelPublicationServiceがセッション単位で署名済みイベントと配送操作を所有し、PublishTicketが配送結果とattemptを保持する。同一入力の二重送信を共有し、全失敗後も同じevent IDで未受理先だけ再送する。kind 40/41/42を接続し、画像処理はChannelImageUploadControllerへ移した。

実装上の判断・制約: ダイアログの表示状態は既存ViewModelに残す。開始済みの署名済み配送はRepository scopeで期限まで続け、UIへの通知はobserver scopeに限定する。永続outboxは作らない。画像の見た目と端末上のキャンセル操作は手動確認が必要。

検証対象: `ChannelPublicationServiceTest / RelayCoordinationTestおよび既存ダイアログ世代テスト`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 現状の責務

`ChannelListViewModel.createChannel()` と `ChannelController.saveThreadMeta()` が、入力検証、配送計画、署名・送信、逐次結果、ローカル反映、ダイアログをまとめて扱う。`SignedEventPublisher.publish()` は最初の受理で戻れるが、全件失敗の結果には署名済みイベントを残さない。

既存の `ChannelCreatePlan`、`ChannelEditDelivery`、`ChannelPublishUiState`、メタデータresolverを再利用する。再送時の同じイベントの使用を保証するため、署名と配送を明示的に分ける。

## 2. クラスと寿命

| 追加予定 | 責務 | 寿命 |
| --- | --- | --- |
| `ChannelPublicationService` | create / editの手順、plan、event、再送 | AccountSessionのscope |
| `ChannelPublicationStore` | operationごとの進行状態と署名済みイベント | セッション内。終了済みは利用者が消費後に解放 |
| `network/PublishTicket` | 1署名済みイベントの対象・受理・失敗・未回答 | Repositoryのscopeで有限な配送期限まで |
| `ChannelImageUploadController` | 画像選択・upload Job・編集世代 | 各画面のViewModel |
| `ChannelPublicationProjector` | 公開状態を既存Dialog/noticeへ変換 | 純粋関数 |

Storeは画面やNavControllerを保持しない。公開後に画面を移ってもリレー結果を集計できるようにし、通知はoperation IDで消費する。未完了operationは保持し、終了済み履歴を無制限に残さない。完了後は要約通知だけを既存の通知経路へ渡し、画面が再送を提供する間だけ署名済みイベントを保持する。

セッションscopeの現行ディスパッチャはDefaultであり、ViewModelのUI状態と同じスレッドとは限らない。公開Storeとresolverへの適用は注入した単一の状態用ディスパッチャ上へ集約する。本番はMain、テストはTestDispatcherとし、ネットワークcallbackは不変の結果だけを渡す。UIはStateFlowを購読し、callbackから画面の可変MapやDialogを直接更新しない。

## 3. 公開操作とAPI

```kotlin
internal data class PublicationKey(
    val sessionId: String,
    val operationId: String,
)
internal data class PublicationStep(
    val kind: Int,
    val event: NostrEvent?,
    val targetRelays: Set<String>,
    val succeeded: Set<String>,
    val failed: Map<String, String>,
    val pending: Set<String>,
)
```

Serviceは `startCreate(command)`、`startEdit(command)`、`retry(key, step)`、`observe(key)`、`consumeNotice(key)` を提供する。commandにはダイアログ世代、入力revision、検証済み本文、推奨リレー、送信先snapshot、編集ならsource event IDを含める。

submitの冪等キーは `sessionId + dialogGeneration + inputRevision + action` とする。状態用ディスパッチャ上でキーを登録してから署名Jobを開始し、同じキーの同時要求は同じoperationを返す。呼び出しごとに新しいoperation IDを発行してから重複を判断しない。入力が変わった新規submitと、同じ署名済みeventのretryをAPIで区別する。Dialogが閉じた後はそのgenerationの新規要求を拒否し、進行中のoperationは配送完了まで保持する。

`SignedEventPublisher` に相当する署名処理を `prepare()` と署名済みeventの `publishPrepared()` に分ける。既存の `publish()` は互換ラッパーにできる。署名後に全配送が失敗してもeventを操作状態に保持する。再送は同じevent ID・createdAt・タグ・署名を使い、未受理リレーにだけ配送する。

最初の受理と配送の最終完了を別に扱う。ticketはすべてのtargetを成功・失敗・未回答に分け、逐次callbackと初回戻り値の順序が前後しても集合で合成する。成功済みを後着の失敗で失敗に戻さない。

再送には単調増加の `deliveryAttempt` を付け、各リレーの失敗と未回答をattempt単位で管理する。retry開始時は既存成功を維持し、再送先の旧失敗を新attemptのpendingへ移す。古いattemptの失敗・timeoutが遅れて届いても新attemptを終了させない。同じeventに対する遅い受理は、実際の受理事実として成功へ合成してよい。処理中の同一stepへの二重retryはBusyとして返す。

## 4. 状態遷移

| 状態 | 入力 | 次の状態・副作用 |
| --- | --- | --- |
| Draft | submit | 検証、送信先固定、Preparing |
| Preparing | 署名成功 | event保存、Sending |
| Preparing | 鍵なし・署名失敗 | Failed。ネットワーク送信なし |
| Sending | 最初の受理 | Accepted。ローカル反映と次step開始を許可 |
| Sending / Accepted | 一部結果 | 同じticketへ統合 |
| Accepted | 全対象受理 | Completed |
| Accepted | 残り失敗 | Partial。既存成功は維持 |
| Sending | 全対象失敗 | Failed。再送用eventを維持 |
| Failed / Partial | retry | 同じeventを未受理先へ配送 |
| 任意 | session終了 | 新たな署名を禁止。未回答を成功にしない |

UIの `Sending` / `PartialSuccess` 等はこの操作状態から `ChannelPublishUiState` へ投影する。画面が閉じたことを公開失敗としない。アプリ終了をまたぐ再送は新たな永続outboxが必要であり、本件では保証しない。

セッション終了ではServiceによる新しい署名・再送とticketの監視を止める。すでに開始した配送はR4のRepository側で期限まで続け、旧UIへの通知は行わない。公開イベントの観測だけは共有ストアへ反映してよい。旧セッションのローカル操作補助情報や新セッションのDialogへは書き込まない。

## 5. チャンネル作成

1. commandを検証し `ChannelCreatePlan` を固定する。入力とリレー設定が後から変わっても進行中のplanを変えない。
2. kind 40を1回署名し、送信前にoperationへ保持する。
3. 最初の受理時点で作成結果をローカルへ反映する。観測元は実際に受理したリレーだけを記録し、推奨リレーを成功元にしない。
4. 最初の発言があれば、同じplanとrelay hintでkind 42を1回署名する。
5. kind 42が全失敗してもkind 40の成功を取り消さない。作成済みchannel IDと署名済み発言を保持し、再送ではkind 40を作り直さない。
6. 画面遷移と部分失敗通知はoperation IDで一度だけ消費する。遅いリレーの結果は遷移後もStoreへ反映する。

kind 40の配送途中にkind 42を開始する現在の「最初の受理で進む」規則は維持する。配送順序の保証を変更する場合は別のプロトコル変更として扱う。

## 6. メタデータ編集

検証はUIとServiceの両方で行う。署名前にsessionの公開鍵と作成者、現在の実効source event IDとcommandのsourceを照合する。不一致なら署名せず競合エラーを返す。現在のメタデータから完全な内容を組み立て、picture・relaysを脱落させない。

配送は `ChannelEditDelivery.plan()` を使う。追加した推奨リレーへkind 41を送り、署名済みkind 40も再配送する。write無効の設定を尊重する。kind 40の再配送失敗はkind 41の受理と分けて記録する。

成功イベントは受信と同じresolverへ投入する。ローカルの表示を直接古いmetaへ上書きしない。新しい別イベントがすでに実効になっているとき、古い編集操作の結果で巻き戻さない。retryはすでに署名した同じeventの再配送であり、新しい編集の署名とは区別する。

## 7. 画像とダイアログ

uploadには `sessionId + dialogGeneration + imageRevision` を付ける。画像を削除・差し替え、ダイアログを閉じる、アカウントを切り替えるとrevisionを進めてJobを取り消す。遅延完了はtokenを比較し、現在の画像欄に適用しない。

アップロード成功URLを入力へ反映してから公開commandを固定する。未完了uploadを含むsubmitは拒否する。公開開始後の画像編集は新しい入力revisionとして扱い、送信中eventの内容を変更しない。公開結果が届いても別のダイアログを閉じない。

## 8. 移行とテスト

1. prepare / publishPreparedとticketを追加し、既存publishをラップして検証。
2. createの状態をStoreへ移し、ViewModelはcommandと投影だけにする。
3. editを移し、resolverとローカル反映をServiceのイベントから呼ぶ。
4. upload管理を抽出し、既存の公開Map・reported集合を削除。

必須ケースは、全失敗後の同一ID再送、最初の受理後の遅い失敗、callback先着、重複callback、kind 40成功/kind 42失敗、作成後の画面移動、編集競合、追加リレー配送、write無効、画像差し替え・終了後の完了、別sessionへの結果混入である。

`ChannelPublishStateTest`、`ChannelEditDraftTest`、メタデータresolverテストを維持し、`ChannelPublicationServiceTest` に偽Signer・偽Publisher・偽ローカル反映先を渡す。署名回数、event ID、実際の送信先、反映回数を検証する。

関連: [R4](./network.md)、[`Torinos_NIP28_channel_spec.md`](../../Torinos_NIP28_channel_spec.md)。
