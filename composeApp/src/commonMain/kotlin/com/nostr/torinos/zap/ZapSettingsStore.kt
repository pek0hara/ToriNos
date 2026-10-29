package com.nostr.torinos.zap

import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val DEFAULT_ZAP_AMOUNTS = listOf(10L, 50L, 100L)
internal const val MAX_ZAP_AMOUNT_CHOICES = 6
internal const val MAX_ZAP_AMOUNT_SATS = 1_000_000L

/** 額の選択肢の入力（`10, 50 100` など）を読む。1〜6 個、1〜1,000,000 sats。読めなければ null。 */
internal fun parseZapAmounts(text: String): List<Long>? {
    val values = text.split(',', ' ', '、', '\n').map { it.trim().replace("_", "") }.filter { it.isNotEmpty() }
    val amounts = values.map { value -> value.toLongOrNull()?.takeIf { it in 1..MAX_ZAP_AMOUNT_SATS } ?: return null }
        .distinct()
    return amounts.takeIf { it.size in 1..MAX_ZAP_AMOUNT_CHOICES }
}

/** 額の選択肢への追加の結果。 */
internal sealed interface ZapAmountEdit {
    data class Updated(val amounts: List<Long>) : ZapAmountEdit
    data class Rejected(val reason: String) : ZapAmountEdit
}

/** [input]（sats）を選択肢に加える。小さい順に並べ直す。 */
internal fun addZapAmount(current: List<Long>, input: String): ZapAmountEdit {
    val text = input.trim().replace(",", "").replace("_", "")
    if (text.isEmpty()) return ZapAmountEdit.Rejected("額を入力してください")
    val value = text.toLongOrNull()?.takeIf { it in 1..MAX_ZAP_AMOUNT_SATS }
        ?: return ZapAmountEdit.Rejected("1〜1,000,000 sats で入力してください")
    if (value in current) return ZapAmountEdit.Rejected("$value sats はすでにあります")
    if (current.size >= MAX_ZAP_AMOUNT_CHOICES) return ZapAmountEdit.Rejected("選択肢は $MAX_ZAP_AMOUNT_CHOICES 個までです")
    return ZapAmountEdit.Updated((current + value).sorted())
}

/** [value] を選択肢から外す。最後の1つは外さない（null を返す）。 */
internal fun removeZapAmount(current: List<Long>, value: Long): List<Long>? =
    (current - value).takeIf { it.isNotEmpty() && it.size < current.size }

/** Zap の設定。端末ごとに保存する（アカウント間で共有）。 */
object ZapSettingsStore {
    private const val EnabledKey = "zap_pref_enabled_v1"
    private const val AmountsKey = "zap_pref_amounts_v1"
    private const val LastAmountKey = "zap_pref_last_amount_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _enabled = MutableStateFlow(true)
    /** オフなら⚡と合計を出さない。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _amounts = MutableStateFlow(DEFAULT_ZAP_AMOUNTS)
    /** 額の選択肢（sats）。 */
    val amounts: StateFlow<List<Long>> = _amounts.asStateFlow()

    private val _lastAmount = MutableStateFlow(DEFAULT_ZAP_AMOUNTS.last())
    /** 詳細シートで前回送った額（sats）。 */
    val lastAmount: StateFlow<Long> = _lastAmount.asStateFlow()

    init {
        scope.launch {
            LocalSettingsStorage.getString(EnabledKey)?.toBooleanStrictOrNull()?.let { _enabled.value = it }
            LocalSettingsStorage.getString(AmountsKey)?.let(::parseZapAmounts)?.let { _amounts.value = it }
            LocalSettingsStorage.getString(LastAmountKey)?.toLongOrNull()
                ?.takeIf { it in 1..MAX_ZAP_AMOUNT_SATS }?.let { _lastAmount.value = it }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        scope.launch { LocalSettingsStorage.putString(EnabledKey, value.toString()) }
    }

    fun setAmounts(value: List<Long>) {
        _amounts.value = value
        scope.launch { LocalSettingsStorage.putString(AmountsKey, value.joinToString(",")) }
    }

    fun setLastAmount(value: Long) {
        _lastAmount.value = value
        scope.launch { LocalSettingsStorage.putString(LastAmountKey, value.toString()) }
    }
}
