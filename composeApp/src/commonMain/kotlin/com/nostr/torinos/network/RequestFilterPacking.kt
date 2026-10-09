package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter

/**
 * NIP-11 の max_filters が分からないときに、1回の REQ に入れるフィルター数の上限。
 * 12要素（REQ・購読ID・フィルター10件）までしか受け付けないリレーがあるため、それに合わせる。
 */
internal const val DEFAULT_MAX_FILTERS_PER_REQUEST = 10

/**
 * フィルターを REQ ごとの組に分ける。
 *
 * - 1つのリスト項目だけが違うフィルターは、その項目の値をまとめて1つにする。結果は元の和集合と同じ。
 *   2つ以上の項目が違うものはまとめない（組み合わせが増えて余計なものまで取るため）。
 *   limit はフィルターごとに効くので、limit を持つものはまとめない。
 * - 空文字の値を含むフィルター（空の d など）は、仕様上正しくても拒否するリレーがあるため別の REQ にする。
 * - 1つの REQ のフィルター数を [maxFiltersPerRequest] 以下にする。
 */
internal fun packFilters(
    filters: List<NostrFilter>,
    maxFiltersPerRequest: Int = DEFAULT_MAX_FILTERS_PER_REQUEST,
): List<List<NostrFilter>> {
    val (isolated, regular) = filters.distinct().partition { it.hasEmptyValue() }
    return mergeFilters(regular).chunked(maxFiltersPerRequest) +
        mergeFilters(isolated).chunked(maxFiltersPerRequest)
}

private class ListField(
    val get: (NostrFilter) -> List<String>?,
    val set: (NostrFilter, List<String>?) -> NostrFilter,
)

private val mergeableFields = listOf(
    ListField({ it.dTags }, { f, v -> f.copy(dTags = v) }),
    ListField({ it.eTags }, { f, v -> f.copy(eTags = v) }),
    ListField({ it.aTags }, { f, v -> f.copy(aTags = v) }),
    ListField({ it.ids }, { f, v -> f.copy(ids = v) }),
    ListField({ it.qTags }, { f, v -> f.copy(qTags = v) }),
    ListField({ it.pTags }, { f, v -> f.copy(pTags = v) }),
    ListField({ it.rootEventTags }, { f, v -> f.copy(rootEventTags = v) }),
    ListField({ it.tTags }, { f, v -> f.copy(tTags = v) }),
    ListField({ it.authors }, { f, v -> f.copy(authors = v) }),
)

private fun mergeFilters(filters: List<NostrFilter>): List<NostrFilter> =
    mergeableFields.fold(filters) { current, field ->
        val merged = linkedMapOf<Any, NostrFilter>()
        current.forEachIndexed { index, filter ->
            val values = field.get(filter)
            if (filter.limit != null || values == null) {
                merged[index] = filter
                return@forEachIndexed
            }
            val key = field.set(filter, null)
            val existing = merged[key]
            merged[key] = if (existing == null) {
                filter.let { field.set(it, values.distinct()) }
            } else {
                field.set(existing, (field.get(existing).orEmpty() + values).distinct())
            }
        }
        merged.values.toList()
    }

private fun NostrFilter.hasEmptyValue(): Boolean = listOfNotNull(
    ids, authors, eTags, rootEventTags, parentKindTags, rootKindTags, qTags, pTags,
    rootPubkeyTags, aTags, rootAddressTags, dTags, tTags,
).any { values -> values.any { it.isEmpty() } }
