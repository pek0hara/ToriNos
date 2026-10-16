# R2: 投稿入力・画面調停の詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

ComposerInputViewModelへ入力の保存・復元・引き継ぎ・flushを移し、入力世代と編集revisionで遅い結果を拒否する。ReplyComposerResolverとAppNavigationPolicyを分離した。ComposerHostは公開完了を所有者へ通知してから閉じる。

実装上の判断・制約: 既存ViewModelを直接注入する形にし、設計例の細分化したポートとNavGraph全体の分割は採用していない。セッション内の回復入力はメモリ管理であり、プロセス終了後の回復保証を追加していない。

検証対象: `ComposerInputBoundaryTest / PostViewModelTest / PendingPostInputsTest`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 現状と維持する所有権

[`AppSessionCoordinator.kt`](../../composeApp/src/commonMain/kotlin/com/nostr/torinos/AppSessionCoordinator.kt) のローカル関数が、保留入力の適用・破棄、引用の引き継ぎ、記事下書き作成、返信解決、保存、画面遷移を行う。

現行の `ComposerCoordinator` は表示形態と返信・引用の文脈、`PostViewModel` は本文と送信状態、`ArticleDraftStore` は保存データ、`PendingPostInputs` は保留入力の適用規則を所有する。この分担を維持し、コーディネーターに本文のコピーを追加しない。匿名から鍵設定後に引き継ぐ `PendingComposerRequestHolder` はセッションの外に残す。

保存の耐久性を区別する。現行 `ArticleDraftStore` の `composers`、handoff、公開attemptは `SessionArticleMemory` にあり、アプリ終了・セッション終了をまたぐ永続保存ではない。リレーへ保存されるのは記事下書きである。本設計の「入力を残す」「回復可能」は、セッションが有効な間の処理失敗・中断に対する保証とする。正常な切り替え完了後やアプリ終了後に保留入力を復元する機能は追加しない。

## 2. クラス構成

| 追加予定 | 所有者・寿命 | 責務 |
| --- | --- | --- |
| `ComposerInputCoordinator` | セッションのViewModelStore内に置く `ComposerInputViewModel` が所有 | 復元・破棄・引用引き継ぎ・flushの直列化、実行状態 |
| `ComposerEditorPort` | 同じViewModelStore内のPostViewModelとComposerを包む | 現在入力を読み、復元を1つの同期操作で適用 |
| `ReplyComposerResolver` | セッション内の同じ所有者 | 返信元の取得、要求の置換とキャンセル、古い結果の破棄 |
| `AppNavigationPolicy` | UIのルート・パネル状態を入力とする純粋関数 | タブ切り替え、ドロワー、戻るの判断 |
| `AppDestinationContent` / `AppOverlays` | Composition | 画面とシートの配置、NavControllerへの適用 |

Composeの `remember` で作ったCoordinatorをセッション全体に保持すると、破棄済みUIを参照しうる。`ComposerCoordinator` をセッションのViewModelStoreへ移し、Compositionに依存しない同じインスタンスを上記Portへ渡す。Compose stateの読み書きはUI用ディスパッチャ上に限定する。

## 3. 入力操作の契約

```kotlin
internal data class ComposerOperationToken(
    val sessionId: String,
    val openingGeneration: Long,
    val inputGeneration: Long,
    val editorRevision: Long,
    val operationId: Long,
)
internal interface ComposerEditorPort {
    fun snapshot(): PostMemoData?
    fun isReadyForReplacement(): Boolean
    fun restore(memo: PostMemoData, offerPending: Boolean)
}
internal sealed interface ComposerInputState {
    data object Idle : ComposerInputState
    data class Running(val token: ComposerOperationToken) : ComposerInputState
    data class Failed(val operationId: Long, val message: String) : ComposerInputState
}
```

操作APIは `listPending()`、`applyPending(input, token)`、`discardPending(input, token)`、`quoteSelection(mode, token)`、`createOrAppendArticle(token)`、`flush()` とする。外から `ArticleDraftStore.transaction` を直接実行しない。UIはStateFlowと操作結果を受け取り、遷移だけを適用する。

通常操作は同時要求をBusyとして返し、二重タップを待ち行列に積まない。内部Mutexは保存と復元の一貫性を守る。操作開始時、保存前、suspend後のUI適用前に、session lease、`inputFrozen`、入力開始世代を確認する。世代が変わった場合は新しい画面へ適用せず、保存済みの回復用入力を残す。

`openingGeneration` だけでは同じ画面での本文編集を検知できない。本文・画像・引用・返信先・送信先を変更するすべてのPort経路で `editorRevision` を進める。置換操作はUI用ディスパッチャでrevisionを比較し、短命な入力変更許可を取得する。許可を保持している間は本文編集、submit、画像追加、別の復元を受け付けず、閉じる・切り替え要求だけは許可して世代を失効させる。許可はUIスレッドを待機させるロックではなく、各操作が検査する状態とする。suspend後の復元もrevisionと世代を再検査し、`finally` で許可を解除する。

画面の表示形態が変わるだけの簡易→全画面展開は、同一入力の操作として既存規則を維持する。現行 `openFullScreen()` は展開でもopeningGenerationを進めるため、内容の適用判定には別の `inputGeneration` を導入する。新しい入力開始・入力破棄・別入力の復元でinputGenerationを進め、展開・同じ入力の再表示では進めない。openingGenerationは保留入力の案内など表示要求の有効性だけに使う。復元操作自身が世代を進めた後の保存は、その操作が得た新世代の許可で完了させる。

sessionIdは `AccountSessionHost` の識別子を利用し、匿名でも同じ寿命のIDを渡す。AccountSessionがない場合、鍵・リレー下書き・保存Storeを必要とする操作は `RequiresAccount` を返す。匿名状態を署名可能な仮のアカウントや共通の保存キーへ置換しない。

## 4. 保留入力と引き継ぎ

保留入力の適用は既存の `PendingPostInputs.apply()` を利用する。

1. Portの送信・アップロード・保存状態を確認し、現在入力を取得する。
2. 旧入力を退避するトランザクションを行う。選択した入力の内容が変わっていれば競合として終了する。
3. 同じ操作トークンが有効な場合だけ同期的にPortへ復元する。
4. 復元後に `current` への保存と元の入力IDの整理を行う。
5. 保存完了前に中断した場合、元入力を削除せず回復可能にする。エラーを成功通知で覆わない。

これは2つのトランザクションとUI変更を含み、全体が1つのDBトランザクションではない。第4段階が失敗したときは、復元済み本文と元入力を保持し、次のflushまたは再適用で収束させる。保存形式や回復IDの意味をこの抽出で変えない。

Matometrは既存のhandoff ID、`markCommitted()`、`finishHandoff()` を再利用する。同じhandoffの再実行は同じ保存済み結果へ戻し、選択投稿を二重追加しない。保存成功後に画面要求が失効した場合も保存結果を残す。`createOrAppendArticle()` は遷移先のdraft IDを返し、NavControllerを直接呼ばない。

## 5. アカウント切り替えとflush

`AccountSession.registerInput("post-composer", ...)` の登録元を `ComposerInputViewModel` へ移す。ViewModelの生成時に1回登録し、終了時に解除する。背景保存と切り替え保存は同じ `flush()` を使う。

切り替えは現行どおり `inputFrozen = true` → `flushInputs()` → セッション終了の順とする。`flush()` は凍結中に許可する専用の保存操作であり、通常操作の `inputFrozen` 検査で拒否しない。

flushは送信・アップロード・下書き操作・引き継ぎの終了を待ってからMutexを取る。待機中に同じMutexを保持しない。凍結前に開始した通常操作は保存済み結果を残して終了し、flushと相互待ちにしない。未アップロード画像が残る場合は現在の失敗規則を維持し、保存できたように扱わない。20秒の切り替え期限も変更しない。

セッション終了時は要求を無効化し、返信解決Jobと画面操作Jobを止める。切り替え準備として必要な保存の完了前にViewModelを破棄しない。切り替え準備が失敗した場合は旧セッションの凍結を解除し、同じ入力を続けられることを保証する。正常な終了で破棄するセッションメモリを、flushにより永続化したとは扱わない。

## 6. 返信解決と遷移

`ReplyComposerResolver` は既存の `ReplyResolutionTracker` を内部に持つ。解決開始で前のJobを取り消し、要求IDを進める。結果はsession IDと要求IDが一致した場合のみ返す。取り消された要求の失敗はSnackbarへ出さない。

ナビゲーションの判断関数は、選択タブ・現在ルート・ドロワー・入力表示形態を受け取り、`CloseDrawer`、`HideInline`、`Pop`、`Navigate(route)` などの命令列を返す。命令は同じUIイベントの中で順に適用し、再Composeで再送しない。戻る優先順位と「編集中の記事から投稿選択へ移り戻る」動線は現行のまま固定する。

## 7. 移行と検証

1. 現在の保留入力・handoff・flushの振る舞いを偽PortとStoreで固定する。
2. `ComposerCoordinator` の所有者を移し、入力操作とflushを抽出する。
3. 返信解決を抽出し、呼び出し側からJobと世代管理を削除する。
4. 遷移判断を抽出してから、ルートとOverlayのComposableを分割する。

| 追加ケース | 期待結果 |
| --- | --- |
| 復元の前後でアカウント切り替え | 新アカウントへ書き込まず、準備失敗で旧セッションへ戻る場合は入力を保持 |
| 第2トランザクション失敗 | 元入力を失わず、次回flushで保存できる |
| 同じhandoffを再実行 | 投稿・記事へ二重追加しない |
| 復元中に閉じる・別入力を開始 | 古い結果で新しい入力を上書きしない |
| 入力を意図的に空にする | 古い本文や引用を復活させない |
| 同じ画面の本文を変更してから復元が完了 | revision不一致で古い復元を適用しない |
| flushと操作完了の競合 | デッドロックせず、失敗時は凍結解除 |
| 返信Aの解決中に返信Bを開始 | Bだけ適用し、Aの完了・失敗を表示しない |

既存の `ComposerCoordinatorTest`、`AccountSessionManagerTest`、`ArticleEditorPersistenceTest` を維持する。抽出のためだけに既存の期待値を変更しない。
