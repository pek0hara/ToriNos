package com.nostr.torinos.zap

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.json.Json

/**
 * 署名と中身を確かめた Zap レシート（kind 9735）。
 * 受け取り先の LNURL サーバーの公開鍵との照合（[zapperPubkey]）は、集計するときに行う。
 */
data class ZapReceipt(
    val receiptId: String,
    /** レシートに署名した公開鍵。受け取り先の `nostrPubkey` と一致しなければ数えない。 */
    val zapperPubkey: String,
    /** Zap を送った人（Zap リクエストの署名者）。 */
    val senderPubkey: String,
    val requestId: String,
    val recipientPubkey: String,
    val targetEventId: String?,
    val amountMsats: Long,
    val comment: String,
    val createdAt: Long,
)

private val receiptJson = Json { ignoreUnknownKeys = true }

private fun NostrEvent.tagValues(name: String): List<String> =
    tags.filter { it.firstOrNull() == name }.mapNotNull { it.getOrNull(1) }

/**
 * レシートを読み、NIP-57 Appendix F の検証のうち、レシートだけで分かるものを確かめる。
 * - レシートと、`description` に入った Zap リクエストの署名が正しい
 * - Zap リクエストの `p` が1つで、レシートの `p` と一致する。`e` もレシートと一致する
 * - 請求書に額があり、Zap リクエストの `amount`（あれば）と一致する
 * - レシートの `P`（あれば）が Zap リクエストの署名者と一致する
 * 満たさなければ null。
 */
fun parseZapReceipt(
    receipt: NostrEvent,
    verify: (NostrEvent) -> Boolean = ::isValidEvent,
): ZapReceipt? {
    if (receipt.kind != ZAP_RECEIPT_KIND) return null
    val bolt11 = receipt.tagValues("bolt11").singleOrNull() ?: return null
    val description = receipt.tagValues("description").singleOrNull() ?: return null
    val request = runCatching { receiptJson.decodeFromString(NostrEvent.serializer(), description) }.getOrNull()
        ?: return null
    if (request.kind != ZAP_REQUEST_KIND) return null
    val recipient = request.tagValues("p").singleOrNull() ?: return null
    if (receipt.tagValues("p").firstOrNull() != recipient) return null
    val requestTarget = request.tagValues("e")
    if (requestTarget.size > 1) return null
    val targetEventId = requestTarget.firstOrNull()
    if (targetEventId != null && receipt.tagValues("e").firstOrNull() != targetEventId) return null
    receipt.tagValues("P").firstOrNull()?.let { if (it != request.pubkey) return null }
    val amount = parseBolt11(bolt11)?.amountMsats ?: return null
    request.tagValues("amount").firstOrNull()?.let { if (it.toLongOrNull() != amount) return null }
    if (!verify(receipt) || !verify(request)) return null
    return ZapReceipt(
        receiptId = receipt.id,
        zapperPubkey = receipt.pubkey,
        senderPubkey = request.pubkey,
        requestId = request.id,
        recipientPubkey = recipient,
        targetEventId = targetEventId,
        amountMsats = amount,
        comment = request.content,
        createdAt = receipt.createdAt,
    )
}

/** 1つの投稿が受け取った Zap の集計。 */
data class ZapTotal(
    val totalMsats: Long = 0,
    val count: Int = 0,
    val senders: Set<String> = emptySet(),
) {
    companion object {
        val Empty = ZapTotal()
    }
}

/**
 * [eventId]（作者 [authorPubkey]）へのレシートを集計する。
 * [zapperPubkey] は作者の受け取り先の `nostrPubkey`。分からなければ何も数えない。
 * 同じ支払いのレシートが複数届くことがあるので、Zap リクエストの ID で重複を除く。
 */
fun summarizeZaps(
    receipts: Collection<ZapReceipt>,
    eventId: String,
    authorPubkey: String,
    zapperPubkey: String?,
): ZapTotal {
    if (zapperPubkey == null) return ZapTotal.Empty
    val counted = receipts
        .filter { it.targetEventId == eventId && it.recipientPubkey == authorPubkey && it.zapperPubkey == zapperPubkey }
        .distinctBy { it.requestId }
    if (counted.isEmpty()) return ZapTotal.Empty
    return ZapTotal(
        totalMsats = counted.sumOf { it.amountMsats },
        count = counted.size,
        senders = counted.mapTo(mutableSetOf()) { it.senderPubkey },
    )
}

/**
 * 合計額（msats）の表示。sats に切り捨ててから、1万未満はそのまま、以上は `12k`・`1.2M`（四捨五入）。0 は空文字。
 */
fun formatZapAmount(msats: Long): String {
    val sats = msats / 1_000
    return when {
        sats <= 0 -> ""
        sats < 10_000 -> sats.toString()
        sats < 999_500 -> "${(sats + 500) / 1_000}k"
        else -> {
            val tenths = (sats + 50_000) / 100_000
            if (tenths >= 1_000 || tenths % 10 == 0L) "${tenths / 10}M" else "${tenths / 10}.${tenths % 10}M"
        }
    }
}

/**
 * 支払う前の請求書の検査（設計 §7.4）。メインネット、額の一致、期限内を確かめる。
 * description hash の不一致は止めない（受け取り側の実装差で正しい Zap を止めないため）。
 */
fun checkInvoice(invoice: String, amountMsats: Long, nowSeconds: Long): Bolt11Invoice {
    val parsed = parseBolt11(invoice) ?: throw ZapException("請求書を読めませんでした")
    if (!parsed.isMainnet) throw ZapException("メインネット以外の請求書は支払えません")
    if (parsed.amountMsats != amountMsats) throw ZapException("請求書の額が指定と違うため中止しました")
    if (parsed.isExpired(nowSeconds)) throw ZapException("請求書の期限が切れています")
    return parsed
}
