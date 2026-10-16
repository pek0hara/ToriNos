# R3: エンゲージメントの詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

EngagementEventClassifierとEngagementProjectionに文脈・受信元・受理IDによる分類と投影を集約した。チャンネルと検索は共有ストアへ接続し、フィード・詳細も同じ投影を使う。pendingには配送先を固定し、自分の確定操作はsessionIdとcommitSequenceで検索再開時に回復する。

実装上の判断・制約: 検索の追跡上限と凍結表示は維持する。ChannelReplyStoreは返信プレビュー用に残すが、Controllerの件数はBindingから導出する。公開先と閲覧リレーが違う場合、閲覧範囲外の確定件数へ混ぜない。

検証対象: `EngagementProjectionTest / SearchResultEnricherTest / PostSearchControllerTestおよび既存Feed・Thread・Channelテスト`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 現状の契約差

フィードと投稿詳細は `EngagementStore` と `PendingEngagementOperations` を共有する。検索は `SearchEngagementAccumulator`、チャンネルは `ChannelReplyStore` と独自の加算Mapを使う。

重要な差として、現行 `EngagementStore.targetsOf()` はkind 42の返信・引用を扱わない。検索には90日の参照期間、追跡100投稿、1バッチ800イベントなどの上限があり、追跡から外れた投稿は最後の表示値を残す。共有ストアを読むだけに置換すると、この範囲と寿命が変わる。

共有するのはイベント集合・削除・集計規則・自分の操作である。検索の取得量、チャンネルのリレー選択、kind 43による非表示はそれぞれの責務に残す。

## 2. 追加・変更する部品

| 対象 | 変更 |
| --- | --- |
| `engagement/EngagementProjection.kt`（追加） | 投稿文脈と観測範囲で絞り、共通の集計と楽観更新を適用 |
| `engagement/EngagementEventClassifier.kt`（追加） | kind別のターゲット分類。既存 `targetsOf()` から抽出 |
| `EngagementStore` | kind 42の分類と重複・削除処理を追加。公開イベントの正本を維持 |
| `SearchResultEnricher` | 取得済み範囲の管理とプロフィール取得に限定 |
| `ui/search/SearchEngagementView.kt`（追加） | 追跡順・受理ID・取得範囲・追跡終了時の表示値を管理 |
| `ui/channel/ChannelEngagementBinding.kt`（追加） | retain、変更監視、文脈とリレー範囲の設定、UiStateへの投影 |
| `ChannelReplyStore` | 返信本文プレビューに縮小。件数・重複集計は共有側へ移す |

## 3. 投影入力

```kotlin
internal data class EngagementViewScope(
    val context: NoteContext,
    val relayUrls: Set<String>?, // null: 全観測元、empty: 観測元なし
    val admittedEventIds: Set<String>?, // null: ストアにある範囲すべて
)
```

投影は `TargetEngagement`、scope、ownPubkey、pending操作を受け取る。返信・引用には `NoteContext.matches()` と親IDの検証を適用し、リアクション・リポストは対象投稿IDとの参照を検証する。受信元の絞り込みは件数だけでなく一覧と自分の確定状態にも同じ規則を適用する。

処理順は、文脈・受信元・受理IDの絞り込み → `summarizeEngagement()` → `EngagementReducer.rebase()` → UIへの変換とする。検索の `SearchEngagement` やチャンネルのMapは最終的な表示形式として残してよいが、加算更新の正本にはしない。pendingはセッション単位で共有し、操作IDを条件に解除する。

pendingにも開始時点の正規化済み配送先snapshotを持たせる。relayUrlsが限定された投影には、配送先との積集合がある操作だけを重ねる。削除操作は対象の確定イベントが投影内にある場合だけ減算する。配送先のうち閲覧範囲外だけが受理して確定した場合は、その範囲の楽観値を解除し、閲覧範囲の確定件数を増やさない。未回答先が残ることは別の配送状態として示し、件数で受理を推測しない。既存の `PendingEngagementOperation` には配送先がないため、この属性の追加とフィード・詳細を含む全生成箇所の更新を同じ移行段階で行う。

`NoteEngagementState` にはreplyCountが含まれない。投影結果は集計の基礎値・返信数・一覧イベントを持つ `EngagementPresentation` とし、rebaseの戻り値でlike/repost関連だけを上書きする。`toNoteEngagementState()` の戻り値だけで全表示を置換し、返信数や引用一覧を落とさない。

イベント集合とpendingの読み取りにも整合性を持たせる。それぞれのsnapshotに単調増加revisionを付け、baseを取得→pendingを取得→base revisionを再確認し、投影をcommitする直前に両revisionを照合する。変更されていれば結果を公開せず最新snapshotで再予約する。成功時の書き込み順はbaseへのイベント・ローカル成功情報→pending解除とし、baseが古くpendingだけが解除された中間値を表示しない。変更通知はsnapshot取得より前に監視し、通知のまとめ処理中に入った変更を次回へ残す。連続更新時にMain上の同期ループで回し続けず、計算依頼を1本へ集約する。

## 4. 検索の範囲を維持する

検索は全ストアを無条件に数えず、その検索で受け入れたイベントIDをscopeへ渡す。バッチ開始時のsinceを固定し、投影するたびに現在時刻から引き直さない。受信イベントの文脈・期間を検証してからIDを受理し、ストアへ渡す。

検索の取得をキャッシュ再生に頼らせない。`replayCachedEvents = false` としてネットワーク予算を数え、必要な初期表示は期間・受信元を満たすスナップショットから別途投入する。ネットワーク受信予算の数え方は初回移行で現行と同じにし、変更する場合は独立した性能変更にする。

`maxTrackedEngagementTargets` は維持する。上限超過時は最古の対象を投影して最後の値を保存し、対象IDをretainから外し、受理ID索引を解放する。表示結果の保持は `maxResultEvents` 内に限る。停止中は保持を続け、監視Jobを止める。再開時にストアとpendingから再投影し、未完了バッチのみ再予約する。reset・closeではownerをreleaseする。

他画面で取得された新しいイベントを検索の受理集合へ自動追加しない。すでに受理したイベントの削除と、自分の操作は反映する。自分の操作が成功した場合は、検索の追跡対象に限り、その確定イベントを受理集合へ加えてからpendingを外す。追跡終了した投稿は現行どおり最後の値を維持する。

この処理を一過性の完了通知だけで実装しない。共有操作サービスがセッション内の単調増加 `commitSequence` を発行し、ストアの保持中イベントへローカル成功の `sessionId + commitSequence` を補助情報として付ける。ネットワーク先着で同じIDがすでにあっても補助情報を更新し、変更を通知する。検索は作成時のsequenceを記録し、それ以降の同じセッションの成功イベントを投影時に受理する。これにより停止中や通知の取りこぼし後もsnapshotから回復できる。

補助情報はイベントIDに付属させ、別の無制限な完了履歴を作らない。対象entryの解放時に削除し、セッション終了時にそのsessionの情報を取り除く。公開イベント自体は他のownerが保持していれば残す。kind 5の成功は通常の削除を適用すればよく、削除イベントを返信やリアクションの集合へ追加しない。

したがって、検索と詳細が常に同じ全件数になることは保証しない。同じ文脈・リレー・受理集合での値が一致することを保証する。検索の制限内の件数を全件数へ変える変更は本件に含めない。

## 5. チャンネルのkind 42対応

分類器でkind 42を処理する。

1. `channelRootId()` からチャンネルIDを求め、不正なイベントを除く。
2. `NoteContext.Channel(channelId).replyTargetId()` で直接の親を求める。チャンネルIDそのものへのroot参照は返信数に含めない。
3. qタグは重複を除き、チャンネルの引用として分類する。投影時に同じチャンネルの投稿だけを採用する。
4. 受理したイベントをID集合で持つ。複数リレー受信時はイベントを増やさず受信元を追加する。

分類器は「文脈なしのtarget ID」だけを返さず、イベントの文脈も判定可能な形にする。ストアの既存kind 1 / 1111の結果を変えない。kind 42の返信・引用追加は機能上の互換に関わるため、抽出と別の変更で検証する。

通常メッセージ履歴もストアへ観測させ、返信の先着に備える。retainは表示・追跡対象の更新時に行い、未retainイベントの既存5,000件の待機上限を増やさない。スレッドで表示するチャンネル返信も同じ分類器・投影を利用する。

チャンネルBindingは現在のreadTargetをscopeへ反映する。送信先の受理元も記録し、閲覧範囲外だけで受理された操作の確定値を閲覧リレーの値として加算しない。kind 43の自分用非表示は本文・プレビューの表示フィルターに残し、サーバー上の返信集合の削除と混用しない。

## 6. 操作と削除

既存の `PendingEngagementRegistry.forSession()` を使う。操作開始 → pending登録 → 署名・公開 → 成功イベントをストアへ投入 → 該当操作IDのpending解除、の順を統一する。失敗はpendingだけ解除する。送信中に再受信してもrebaseで二重加算しない。

削除は作者を検証したkind 5で処理する。削除が先着した場合も後着イベントを再計上しない。ローカルのチャンネル非表示をkind 5として投入しない。削除がストアへ観測されない場合まで他端末の取り消しを即時反映できるとは扱わない。

## 7. 移行手順

1. 分類器とscope付き投影を抽出し、既存フィード・詳細と結果が一致することを確認。
2. 検索の受理集合・上限・追跡終了を固定するテストを追加し、表示を共有ストアへ移す。
3. kind 42の分類と文脈フィルターを追加し、チャンネル返信・引用を検証。
4. チャンネルの受信・pending・UiState投影を移し、独自の件数加算とseen集合を削除。
5. `ChannelReplyStore` から件数責務を外す。プレビュー本文の1,000件上限は維持。

`ReactionEventStore`、記事アドレス参照、ジャーナル期間集計は移行対象外。利用先が残るクラスは削除しない。

## 8. 必須テスト

- 既存のkind 1 / 1111、like・Unicode・カスタム絵文字、kind 6 / 16の集計を維持する。
- チャンネルroot投稿を返信に含めず、別チャンネルのイベントを混ぜない。
- 同一ID複数リレー、同一作者の別ID、削除の先着・後着、作者不一致の削除を区別する。
- リレー範囲変更時に、件数と一覧を同じ集合から導出する。
- 検索の90日・100追跡・800イベント予算と、追跡終了後の表示値を維持する。
- 検索停止・再開、検索→詳細→検索、チャンネル→スレッド→チャンネルでpendingと確定値が二重計上されない。
- 最後のowner終了でretainを解除し、同じ公開イベントでも別セッションのpendingを混ぜない。

関連: [`engagement-fetch-redesign.md`](../engagement-fetch-redesign.md)、[`post-search-refactor-design.md`](../post-search-refactor-design.md)。
