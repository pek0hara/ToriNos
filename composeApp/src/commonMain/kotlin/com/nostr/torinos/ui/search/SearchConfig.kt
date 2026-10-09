package com.nostr.torinos.ui.search

/**
 * 検索の取得・保持・更新頻度の上限。値の変更は実機計測を伴う別変更として行う。
 * 保持・集計の上限はアプリの処理量を制限するもので、リレーが送る総量の上限ではない。
 */
internal data class SearchConfig(
    val searchRelayUrl: String = "wss://search.nos.today",
    val userSearchLimit: Int = 20,
    /** 投稿検索の1ページの要求件数。 */
    val pageSize: Int = 30,
    val pageTimeoutMillis: Long = 10_000L,
    /** `limit` を超えて届いた一意イベントをこの件数まで待つ。超えたらページを閉じて未完了とする。 */
    val pageOverflowBudget: Int = 90,
    /** 検索全体で保持する投稿の上限。 */
    val maxResultEvents: Int = 600,
    /** 全件が境界秒だったページを、要求件数を増やして取り直す回数。 */
    val boundaryRetryLimit: Int = 2,
    /** 返信・リアクション・リポストを集計する対象投稿の上限（新しく追加した順に残す）。 */
    val maxTrackedEngagementTargets: Int = 100,
    val engagementFilterLimit: Int = 200,
    /** 付随情報の1回の取得で受け付けるイベントの上限。 */
    val engagementBatchEventLimit: Int = 800,
    val engagementLookbackSeconds: Long = 90L * 24L * 60L * 60L,
    val engagementTimeoutMillis: Long = 5_000L,
    val engagementBatchMillis: Long = 500L,
    val profileBatchMillis: Long = 500L,
    val profileMaxAgeMillis: Long = 15 * 60 * 1_000L,
    val stateSyncMillis: Long = 100L,
) {
    init {
        require(pageSize > 0) { "pageSizeは正の値である必要があります" }
        require(pageOverflowBudget >= 0) { "pageOverflowBudgetは0以上である必要があります" }
        require(maxResultEvents > 0) { "maxResultEventsは正の値である必要があります" }
        require(boundaryRetryLimit >= 0) { "boundaryRetryLimitは0以上である必要があります" }
        require(maxTrackedEngagementTargets > 0) { "maxTrackedEngagementTargetsは正の値である必要があります" }
    }
}
