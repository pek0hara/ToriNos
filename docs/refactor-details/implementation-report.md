# 優先度順リファクタの実装記録

更新日: 2026-10-16。既存の作業ツリーを保持したまま、R1 → R2 → R3 → R4 → R5 → R6 → R7の順に主要経路を変更した。コミット・公開は行っていない。

## 実装した範囲

| ID | 実装・移行した境界 | 主な回帰防止 |
| --- | --- | --- |
| [R1](./finite-fetch.md) | 有限取得結果・リレー別継続、記事・通知・チャンネルへの導入、Session実体による終了保護 | 遅いリレー、拒否、部分取得、上限不明、同秒境界、キャンセル、通知Live所有 |
| [R2](./app-coordination.md) | ComposerInputViewModel、返信解決、フッター遷移判断、入力置換中の編集保護 | 入力世代・編集revision、遅い返信結果、展開、内部復元 |
| [R3](./engagement.md) | 集計の分類・投影、検索・チャンネルの共有ストア利用、確定操作の検索再開時回復 | kind 42の文脈、配送先、受理ID、pending、削除先着、安定スナップショット |
| [R4](./network.md) | Registry、接続Pool、配送Coordinator、Session・Mailbox、Live欠落の補完 | 満杯、handler例外、世代とack、購読枠、所有者、初回成功後の配送継続 |
| [R5](./feed.md) | 保持モデル、範囲判断、キャッシュ参照、純粋投影、状態公開の分離 | 包含境界、アンカー、ソート時刻、間引き公開・終了 |
| [R6](./channel-publishing.md) | セッション所有の公開サービス、署名済みeventと配送Ticket、画像処理 | 二重submit、全失敗後の同ID再送、古いattemptの失敗拒否 |
| [R7](./note-card.md) | 表示モデル・操作・Host・描画部品、model内の本文解析・URL抽出 | 全呼び出し元の移行、null操作、既定callback、既存本文解析ケース |

最初の5回のレビューは[設計レビュー記録](./review-cycles.md)に残している。実装中にはコンパイラ・既存テスト・追加テストで境界の誤りを修正し、全プラットフォームの自動検証を行った。設計例と実装の型名・分割方法は必ずしも一致しない。各詳細設計の「実装との対応」を参照する。

## 検証

ソースレビューの修正を含む最終コードに対し、次のGradle実行が成功した（オフライン、1分31秒）。[指摘5件と修正・8件の追加回帰テスト](./source-review-fixes.md)を参照。

```sh
./gradlew :composeApp:iosSimulatorArm64Test :composeApp:wasmJsBrowserTest :composeApp:compileAndroidMain :composeApp:verifyNoDirectProfileSubscriptions --offline
```

| 確認 | 結果 |
| --- | --- |
| iOS Simulator Arm64 | 228スイート、1,635件、失敗・エラー・skip 0 |
| Wasm Browser | 219スイート、1,588件、失敗・エラー・skip 0 |
| Android共通実装のコンパイル | 成功 |
| 直接プロフィール購読の構造チェック | 成功 |
| git diff --check | 問題なし |
| 今回変更・追加したソース/文書の行末空白 | 問題なし |
| リファクタ設計文書の相対リンク | 欠落なし |

テスト件数は各プラットフォームのJUnit XMLの合計。同じ共通テストを含むため、独立したケース数として合算しない。既存の非推奨API等のコンパイラ警告とWasmのdependency警告は残っているが、最終実行に失敗はない。実行ログは`/tmp/torinos-refactor-review-final-checks.log`、JUnit XMLは`composeApp/build/test-results/`配下にある。

## 設計からの変更と残る段階

- R1: `FiniteFetchResult`は`FiniteEventFetcher.kt`内へ配置した。短いページでも上限不明なら範囲完了とみなさない。有限継続はリレーごとに既定3ページと全体期限を持ち、証明できない同秒境界は停止する。記事一覧は包含境界と最大4倍の要求枠で再取得し、進捗がなければ明示的なエラーにする。チャンネル履歴は既存のカーソル構造を保ち、不完全ページでは進めない。履歴カーソルの全面的なリレー別モデルへの置換と、通知の初回基準構築を画面内で再試行するUIは残る。
- R2: 細かいポート群を新設せず、入力ViewModelと既存ViewModelの境界で移行した。NavGraph全体の分割は残る。入力回復はセッション内の保証であり、アプリ終了後の永続復元は追加していない。
- R3: 返信プレビューを扱うChannelReplyStoreは残す。Controllerの件数は共有投影から導出する。検索は受理IDと自分のローカル確定操作に範囲を絞り、他画面の受信イベントを無条件には混ぜない。
- R4: Repositoryは単一mutexと外部APIを保つ調停役として残る。補完の予算を使い切った場合はNeedsResyncを維持する。状態公開は実装したが、全画面共通の未解消警告UIは残る。
- R5: timelineとdecorationのrevisionを分ける追加最適化は保留し、既存の再計算による一貫性を維持した。性能改善量は未計測。
- R6: ダイアログ固有の表示Mapは既存ViewModelに残す。公開は永続outboxを持たず、プロセス終了後の配送継続を保証しない。
- R7: flat引数の旧NoteCard APIは削除し、呼び出し元をすべて移行した。端末での文字サイズ・メディア・匿名表示・メニュー操作の見た目は未確認。

自動テストの成功は、AC-01〜16の全時系列を実機で確認したことを意味しない。切断・再接続、画面往復、アカウント切り替え、画像アップロードの取消、文字サイズ変更、スクロール位置と性能は[`regression-test.md`](../regression-test.md)に従って端末で確認する必要がある。

この記録と詳細設計は、上記の残る段階と実機検証があるため保持する。
