package com.nostr.torinos.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class UnicodeReactionEntry(
    val value: String,
    val keywords: List<String>,
)

internal val UNICODE_REACTION_CATALOG = listOf(
    UnicodeReactionEntry("😀", listOf("笑顔", "うれしい")),
    UnicodeReactionEntry("😃", listOf("笑顔", "うれしい")),
    UnicodeReactionEntry("👍", listOf("いいね", "賛成", "good")),
    UnicodeReactionEntry("👎", listOf("よくない", "反対", "bad")),
    UnicodeReactionEntry("😂", listOf("笑う", "爆笑", "笑顔")),
    UnicodeReactionEntry("🤣", listOf("笑う", "爆笑")),
    UnicodeReactionEntry("😊", listOf("笑顔", "うれしい")),
    UnicodeReactionEntry("🥰", listOf("好き", "笑顔")),
    UnicodeReactionEntry("😍", listOf("好き", "ハート")),
    UnicodeReactionEntry("🤔", listOf("考える", "疑問")),
    UnicodeReactionEntry("😮", listOf("驚く", "びっくり")),
    UnicodeReactionEntry("😢", listOf("悲しい", "泣く")),
    UnicodeReactionEntry("😭", listOf("悲しい", "泣く")),
    UnicodeReactionEntry("😡", listOf("怒る")),
    UnicodeReactionEntry("🥳", listOf("お祝い", "パーティー")),
    UnicodeReactionEntry("🤩", listOf("すごい", "感動")),
    UnicodeReactionEntry("🫡", listOf("了解", "敬礼")),
    UnicodeReactionEntry("🫠", listOf("溶ける")),
    UnicodeReactionEntry("🙌", listOf("万歳", "お祝い")),
    UnicodeReactionEntry("👏", listOf("拍手", "すごい")),
    UnicodeReactionEntry("🙏", listOf("お願い", "ありがとう", "感謝")),
    UnicodeReactionEntry("💪", listOf("がんばれ", "力")),
    UnicodeReactionEntry("🤝", listOf("握手", "同意")),
    UnicodeReactionEntry("👌", listOf("了解", "ok")),
    UnicodeReactionEntry("✌️", listOf("平和", "ピース")),
    UnicodeReactionEntry("👀", listOf("見る", "注目")),
    UnicodeReactionEntry("🎉", listOf("お祝い", "おめでとう")),
    UnicodeReactionEntry("✨", listOf("きらきら", "素敵")),
    UnicodeReactionEntry("🔥", listOf("炎", "熱い", "最高")),
    UnicodeReactionEntry("💯", listOf("満点", "最高")),
    UnicodeReactionEntry("💡", listOf("アイデア", "ひらめき")),
    UnicodeReactionEntry("✅", listOf("完了", "確認")),
    UnicodeReactionEntry("🚀", listOf("ロケット", "開始")),
    UnicodeReactionEntry("🐦", listOf("鳥", "とり")),
    UnicodeReactionEntry("🐣", listOf("ひよこ", "鳥")),
    UnicodeReactionEntry("🍣", listOf("寿司", "食べ物")),
    UnicodeReactionEntry("🍺", listOf("ビール", "乾杯")),
    UnicodeReactionEntry("☕", listOf("コーヒー", "休憩")),
)

internal val EMOJI_SEARCH_KEYWORDS by lazy {
    UNICODE_REACTION_CATALOG.associate { it.value to it.keywords }
}

