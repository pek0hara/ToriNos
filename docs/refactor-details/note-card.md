# R7: 共通投稿カードの詳細設計

状態: 2026-10-16に主要経路を実装・移行。優先度順の実装結果は[実装記録](./implementation-report.md)を参照。

## 実装との対応（2026-10-16）

NoteCardModel / NoteCardActions / NoteCardHost / NoteCardContentへ分け、全呼び出し元を新APIへ移行した。本文、メディア、集計、データ表示、通報、絵文字一覧を分離し、解析・URL抽出・画像URLをmodelへ移した。カード状態はevent IDで所有する。

実装上の判断・制約: 旧flat引数のNoteCard APIは削除済み。描画順と既存のnull callback・既定操作を維持したが、端末の文字サイズ・画像拡大・匿名表示の見た目は未確認。

検証対象: `NoteCardModelTest / ContentWarningTest / ParsedNoteContentMediaTest / UrlExtractionTest`。実行結果と未確認項目は[実装記録](./implementation-report.md)に集約する。

以下は実装前に作成した詳細設計である。型名・分割方法の差異は本節と実コードを優先し、残る段階を完了扱いにしない。

## 1. 現状と変更範囲

`NoteCard` は投稿本文と作者だけでなく、集計・自分の状態・操作callback・引用・表示方針を個別引数で受ける。同じファイルに解析、集計詳細、クイックリアクション、通報、画像グリッド、拡大表示、日時整形がある。

最初に解析と描画を分け、その後に表示データと操作のまとまりを定義する。既存の公開関数を互換ラッパーとして残し、呼び出し元を画面ごとに移す。描画を分けるだけで性能が改善するとは扱わない。

## 2. ファイル構成

| 追加・移動予定 | 内容 |
| --- | --- |
| `model/NoteContentParser.kt` | `ParsedNoteContent`、`parseNoteContent`、メディア・引用の純粋解析 |
| `ui/components/note/NoteCardModel.kt` | 作者、本文、集計、引用、表示方針の入力モデル |
| `ui/components/note/NoteCardActions.kt` | 操作callbackと利用可能性 |
| `ui/components/note/NoteCardHost.kt` | 設定・CompositionLocal・アカウント機能との接続 |
| `ui/components/note/NoteCardContent.kt` | 各描画部品の配置とカード内状態 |
| `NoteHeader` / `NoteBody` / `NoteMedia` | 作者・日時、本文・引用、画像・動画・音声 |
| `NoteEngagement` / `NoteActionMenu` / `NoteDialogs` | 集計と操作、メニュー、拡大・通報・データ表示 |
| 既存 `ui/components/NoteCard.kt` | 移行中の旧シグネチャから新モデルへのアダプター |

解析モデルがComposeやプラットフォーム型に依存しないことを確認してmodel側へ移す。`FeedItemMapper` がUIファイルの解析関数に依存する関係を外す。`ImageRequest` 生成、PlatformContext、描画サイズはUI側へ残す。すでに別ファイルにある部品を再分割しない。

依存方向の確認には `ui/components/UrlExtraction.kt` も含める。`parseNoteContent()` が使う `extractWebUrls` などの純粋ヘルパーと、画像URL判定・Nostr参照の解析を棚卸しし、共有の非UIパッケージへ必要な関数を移す。model側の解析関数から `ui.components` をimportする逆依存を残さない。クリック可能URLの表示、テキスト省略、ImageRequest生成などUI固有の利用先は新しい純粋ヘルパーを呼ぶ。

## 3. 表示モデルと操作

```kotlin
internal data class NoteCardModel(
    val event: NostrEvent,
    val author: NoteAuthorPresentation,
    val content: ParsedNoteContent,
    val engagement: NoteEngagementPresentation,
    val references: NoteReferencePresentation,
    val policy: NoteCardPolicy,
)
internal data class NoteCardPolicy(
    val showEngagement: Boolean,
    val showReactions: Boolean,
    val showImagePreviews: Boolean,
    val showXPreviews: Boolean,
    val deferWebViewLoad: Boolean,
)
```

集計表示はR3の同じ投影から作り、カードが件数を再計算しない。返信・リアクションの詳細一覧と件数が異なる取得範囲の場合は、その状態をモデルに持ち、空の一覧を0件に読み替えない。`showEngagement = false` と `showReactions = true` の現行の独立した指定を保つ。

Actionsはlike・emoji・reply・repost・profile・reference・hide・mute・delete・reportなどを用途別にまとめる。操作できない場合はnullまたは明示したavailabilityで表し、空のラムダを「操作可能」として扱わない。

`onQuotedNoteClick` と `onReplyParentClick` の既定が `onNoteClick` に従う現在の振る舞いは、旧アダプターで明示的に解決する。新モデルで無意識にnullへ変えない。プレビュー表示とリンク遷移、記事引用の遷移を区別する。

## 4. 状態と副作用

| 状態 | 所有者・キー |
| --- | --- |
| 本文解析 | Controller側の既存キャッシュ。未計算ならHostでevent IDをキーに解析 |
| 集計・自分の操作 | Controller / 共通ストア。カード内へコピーしない |
| 開いたメニュー・詳細・内容警告解除・画像拡大 | カード内。基本キーはevent ID |
| 選択バッジ | event IDと作者公開鍵。バッジ購読は作者行の部品で読む |
| 表示設定 | HostがStateを読み、必要なPolicyだけを渡す |
| 署名・通信・保存 | 呼び出し元のActions。描画部品から直接行わない |

LazyListでカードが再利用されたときに、前の投稿の画像・通報・メニュー状態が次の投稿へ残らないよう、状態をevent IDで区切る。すべての一時状態をUiStateへ押し上げず、必要な部品内に保つ。メディアの遅延生成、単一プレイヤー管理、WebViewの抑制は既存の契約を使う。

Hostは既存の `LocalQuotePostHandler`、Matometr、記事ナビゲーションなどをActionsへ変換する。新たなネットワーク購読を作らない。副作用を持つDialogと表示部品は分け、再Composeで通報や送信を再実行しない。

## 5. 呼び出し元の移行

1. 解析型・関数を移し、既存importを変更する。移行用のforwarderを残す場合は単一実装へ委譲する。
2. NoteCardの現行引数を新モデルへ変換するアダプターを追加し、既存画面で同じ見た目を維持する。
3. 内部部品を本文・メディア・集計・メニューの順に移す。各段階で構図や間隔は変えない。
4. フィード → 詳細 → チャンネル → 検索・プロフィール・ジャーナルの順で、各呼び出し元からモデルを組み立てる。
5. 旧シグネチャの呼び出しがなくなったらラッパーを削除する。

`@Stable` / `@Immutable` を型名だけを理由に付けない。モデル内のコレクションとcallbackの実際の安定性を確認する。描画計測で必要な場合だけ、既存モデルの共有や更新粒度を改善する。

## 6. 検証

解析の既存テストは移動後の単一実装を検証する。`ParsedNoteContentArticleQuoteTest`、日時、メディア判定、URL抽出を維持する。追加テストは旧引数アダプターの既定値と操作可否、event ID変更時の状態分離に絞る。

| 画面確認 | 条件 |
| --- | --- |
| 本文 | 長文、空本文、メンション、ハッシュタグ、内容警告 |
| 画像 | 1〜4枚以上、拡大、プレビュー非表示、スクロール中 |
| その他メディア | 動画・音声、X、YouTube、WebView遅延 |
| 集計 | 絵文字、like、返信・引用、未取得の詳細、送信中・失敗 |
| 操作 | 自分・他人・匿名、削除、非表示、ミュート、通報 |
| 再利用 | カードの入れ替え、画面往復、アカウント切り替え |
| レイアウト | 全文字サイズ、長い名前、バッジ、記事引用 |

新旧を同じ固定イベントで比較し、利用可能な操作、本文解析、表示順が一致することを条件とする。性能改善を主張する場合は、同じデータで解析回数・メディア生成数・フレーム時間を測る。

関連: [R3](./engagement.md)、[R5](./feed.md)、[`inline-media-player-design.md`](../inline-media-player-design.md)、[`regression-test.md`](../regression-test.md)。
