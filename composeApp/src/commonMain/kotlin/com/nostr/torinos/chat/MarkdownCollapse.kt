package com.nostr.torinos.chat

import com.nostr.torinos.model.countTextWithCustomEmojis
import com.nostr.torinos.model.findRawEndForCustomEmojiLength
import com.nostr.torinos.model.groupLinkLabel
import com.nostr.torinos.model.isGroupLinkUri
import com.nostr.torinos.model.profileLinkLabel
import com.nostr.torinos.model.shortenUrlForDisplay

/** 折りたたんだ本文。[truncated] なら元より短く、「もっと見る」で全部を出す。 */
data class CollapsedMarkdown(val blocks: List<MdBlock>, val truncated: Boolean)

/**
 * 本文に出すリンクの文字。むき出しのリンク（[withAutolinks] で拾ったもの）はフィードの本文と同じ文字にする:
 * グループへのリンクは「💬 Buzz のメッセージ」など、URL は短くする。`[文字](URL)` の文字はそのまま。
 * 人へのリンクの名前は画面で付けるので、ここでは名前なしの文字。開く先は [MdInline.Link.url] のまま。
 */
fun MdInline.Link.displayText(): String {
    // `npub1…` と書かれた人へのリンクは、URL だけ `nostr:` 付きにそろえてある。
    val bare = text == url || "nostr:" + text.removePrefix("nostr:").lowercase() == url.lowercase()
    if (!bare) return text
    npubOfLink(url)?.let { return profileLinkLabel(it, null) }
    if (isGroupLinkUri(url)) return groupLinkLabel(url)
    return shortenUrlForDisplay(url)
}

/**
 * 長い Markdown の本文を、フィードの長い投稿と同じ基準（文字数と行数）で切る。
 * Markdown の文字のまま切ると記法が壊れるので、読んだ後のブロックを切る。
 * - 文字数はブロックをまたいで数える。カスタム絵文字は1文字、リンクは表示する文字で数え、途中で切らない。
 * - 行数はブロックの頭と本文中の改行で数える（画面での折り返しは数えない）。
 * - 画像は数えないが、切れ目より後のものは隠す。
 */
fun collapseMarkdown(
    blocks: List<MdBlock>,
    maxChars: Int,
    maxLines: Int,
    emojiShortcodes: Set<String>,
): CollapsedMarkdown {
    val budget = CollapseBudget(maxChars, maxLines, emojiShortcodes)
    val out = ArrayList<MdBlock>(blocks.size)
    for (block in blocks) {
        if (block is MdBlock.Image) {
            out += block
            continue
        }
        if (!budget.startBlock()) return CollapsedMarkdown(out, truncated = true)
        when (block) {
            is MdBlock.Paragraph -> budget.inlines(block.inlines)?.let { return CollapsedMarkdown(out + MdBlock.Paragraph(it), true) }
            is MdBlock.Heading -> budget.inlines(block.inlines)?.let { return CollapsedMarkdown(out + MdBlock.Heading(block.level, it), true) }
            is MdBlock.Quote -> budget.inlines(block.inlines)?.let { return CollapsedMarkdown(out + MdBlock.Quote(it), true) }
            is MdBlock.CodeBlock -> {
                val end = budget.fit(block.text, countEmojis = false)
                if (end < block.text.length) {
                    return CollapsedMarkdown(out + MdBlock.CodeBlock(block.text.take(end).trimEnd() + ELLIPSIS), true)
                }
            }
            is MdBlock.Image -> Unit
        }
        out += block
    }
    return CollapsedMarkdown(blocks, truncated = false)
}

private const val ELLIPSIS = "…"

private class CollapseBudget(
    private var chars: Int,
    /** これから始められる行の数。 */
    private var linesLeft: Int,
    private val emojiShortcodes: Set<String>,
) {
    /** 新しいブロック（＝新しい行）を始められるか。始めたら1行使う。 */
    fun startBlock(): Boolean {
        if (linesLeft <= 0 || chars <= 0) return false
        linesLeft--
        return true
    }

    /**
     * [text] のうち入る分の終わり（生の文字の位置）。全部入れば `text.length`。
     * 入らなかった時点で文字数を使い切ったことにする。
     */
    fun fit(text: String, countEmojis: Boolean): Int {
        val shortcodes = if (countEmojis) emojiShortcodes else emptySet()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            val segment = text.substring(start, if (newline < 0) text.length else newline)
            val length = countTextWithCustomEmojis(segment, shortcodes)
            if (length > chars) {
                val end = start + findRawEndForCustomEmojiLength(segment, chars, shortcodes)
                chars = 0
                return end
            }
            chars -= length
            if (newline < 0) return text.length
            if (linesLeft <= 0) return newline
            linesLeft--
            start = newline + 1
        }
    }

    /** 全部入れば null。入らなければ、入る分に「…」を付けたもの。 */
    fun inlines(inlines: List<MdInline>): List<MdInline>? {
        val kept = ArrayList<MdInline>(inlines.size)
        // むき出しの URL もリンクとして数える（途中で切らない）。
        for (inline in inlines.withAutolinks()) {
            if (inline is MdInline.Link) {
                if (chars <= 0) return kept.withEllipsis()
                chars -= inline.displayText().length
                kept += inline
                continue
            }
            val (text, rebuild) = when (inline) {
                is MdInline.Text -> inline.text to { s: String -> MdInline.Text(s) }
                is MdInline.Bold -> inline.text to { s: String -> MdInline.Bold(s) }
                is MdInline.Italic -> inline.text to { s: String -> MdInline.Italic(s) }
                is MdInline.Code -> inline.text to { s: String -> MdInline.Code(s) }
                is MdInline.Spoiler -> inline.text to { s: String -> MdInline.Spoiler(s) }
                is MdInline.Link -> error("handled above")
            }
            val end = fit(text, countEmojis = inline !is MdInline.Code)
            if (end < text.length) {
                val part = text.take(end).trimEnd()
                if (part.isNotEmpty()) kept += rebuild(part)
                return kept.withEllipsis()
            }
            kept += inline
        }
        return null
    }

    private fun List<MdInline>.withEllipsis(): List<MdInline> {
        val last = lastOrNull()
        return if (last is MdInline.Text) {
            dropLast(1) + MdInline.Text(last.text.trimEnd() + ELLIPSIS)
        } else {
            this + MdInline.Text(ELLIPSIS)
        }
    }
}
