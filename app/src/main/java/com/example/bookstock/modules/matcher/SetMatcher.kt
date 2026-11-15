package com.example.bookstock.modules.matcher

import com.example.bookstock.modules.stock.StockProvider
import com.example.bookstock.modules.vision.ScannedSpine

/**
 * モジュール3: 巻数突合・セット判定モジュール (Kotlin版)
 * 仕様書: docs/03_set_matcher.md
 */

data class MatchedSeriesStatus(
    val title: String,
    val format: String? = null,
    val totalVolumes: Int,
    val isCompletedSeries: Boolean,
    val storeVolumes: List<String>,
    val stockVolumes: List<String>,
    val stockVolumeCounts: Map<String, Int> = emptyMap(),
    val stockVolumeDetails: Map<String, StockProvider.VolumeStockDetail> = emptyMap(),
    val mergedVolumes: List<String>,
    val missingVolumes: List<String>,
    val canCompleteByStore: Boolean,
    val canCompleteByMerged: Boolean,
    val note: String
)

class SetMatcher {
    private val knownSeries = mapOf(
        "天は赤い河のほとり" to 16,
        "はいからさんが通る" to 4,
        "不毛地帯" to 5,
        "竜馬がゆく" to 8,
        "獣の奏者" to 4,
        "燃えよ剣" to 2,
        "機動戦士ガンダムさん" to 23,
        "がんばれ元気" to 10,
        "指輪物語" to 10,
        "トワイライト" to 10
    )

    companion object {
        /**
         * ローマ数字・漢数字・サブタイトル・枝番付き上下巻等の巻数表記を正規化
         */
        fun normalizeVolume(vol: String): String {
            val v = vol.trim()

            // 1. 上下＋枝番 (指輪物語等: 上1, 上2, 下1, 下2)
            val jogBranch = Regex("([上下])\\s*([1-9])").find(v)
            if (jogBranch != null) {
                return "${jogBranch.groupValues[1]}${jogBranch.groupValues[2]}"
            }

            // 2. ローマ数字＋上下 (トワイライト等: Ⅱ上 -> 2-上)
            val romanMap = mapOf(
                "Ⅰ" to "1", "Ⅱ" to "2", "Ⅲ" to "3", "Ⅳ" to "4", "Ⅴ" to "5",
                "Ⅵ" to "6", "Ⅶ" to "7", "Ⅷ" to "8", "Ⅸ" to "9", "Ⅹ" to "10",
                "I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5",
                "VI" to "6", "VII" to "7", "VIII" to "8", "IX" to "9", "X" to "10"
            )
            for ((r, num) in romanMap) {
                val match = Regex("$r\\s*([上下])").find(v)
                if (match != null) {
                    return "$num-${match.groupValues[1]}"
                }
            }

            val map = mapOf(
                "Ⅰ" to "1", "Ⅱ" to "2", "Ⅲ" to "3", "Ⅳ" to "4", "Ⅴ" to "5",
                "Ⅵ" to "6", "Ⅶ" to "7", "Ⅷ" to "8", "Ⅸ" to "9", "Ⅹ" to "10",
                "I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5",
                "VI" to "6", "VII" to "7", "VIII" to "8", "IX" to "9", "X" to "10",
                "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
                "①" to "1", "②" to "2", "③" to "3", "④" to "4", "⑤" to "5",
                "⑥" to "6", "⑦" to "7", "⑧" to "8", "⑨" to "9", "⑩" to "10",
                "一" to "1", "二" to "2", "三" to "3", "四" to "4", "五" to "5",
                "六" to "6", "七" to "7", "八" to "8", "九" to "9", "十" to "10",
                "闘蛇編" to "1", "王獣編" to "2", "探求編" to "3", "完結編" to "4",
                "闘蛇" to "1", "王獣" to "2", "探求" to "3", "完結" to "4",
                "刹那" to "外伝", "外伝" to "外伝", "追補編" to "追補編", "追補" to "追補編",
                "最終章" to "最終章"
            )
            val direct = map[v]
            if (direct != null) return direct

            val numMatch = Regex("^(\\d+)").find(v)
            if (numMatch != null) return numMatch.groupValues[1]

            return v
        }
    }

    fun match(scannedBooks: List<ScannedSpine>, stockProvider: StockProvider): List<MatchedSeriesStatus> {
        val grouped = scannedBooks.groupBy { normalize(it.title) }

        return grouped.map { (_, books) ->
            val displayTitle = books.first().title
            val format = books.mapNotNull { it.format }.firstOrNull() ?: "コミックス"
            val storeVols = books.map { normalizeVolume(it.volume) }.distinct()
            val stockDetails = stockProvider.getOwnedVolumeDetailsByTitle(displayTitle, format)
            val stockCounts = stockDetails.mapValues { it.value.count }
            val stockVols = stockCounts.keys.sorted()
            evaluate(displayTitle, storeVols, stockVols, stockCounts, stockDetails, format)
        }.sortedByDescending { if (it.canCompleteByStore || it.canCompleteByMerged) 1 else 0 }
    }

    private fun evaluate(title: String, storeVols: List<String>, stockVols: List<String>, stockCounts: Map<String, Int> = emptyMap(), stockDetails: Map<String, StockProvider.VolumeStockDetail> = emptyMap(), format: String? = null): MatchedSeriesStatus {
        val norm = normalize(title)
        val knownTotal = knownSeries.entries.find { norm.contains(normalize(it.key)) }?.value

        // 上下巻の特別判定
        if (storeVols.contains("上") || stockVols.contains("上") || storeVols.contains("下") || stockVols.contains("下")) {
            val hasStoreJo = storeVols.contains("上")
            val hasStoreGe = storeVols.contains("下")
            val hasStockJo = stockVols.contains("上")
            val hasStockGe = stockVols.contains("下")

            val storeComp = hasStoreJo && hasStoreGe
            val mergedComp = (hasStoreJo || hasStockJo) && (hasStoreGe || hasStockGe)

            val missing = mutableListOf<String>()
            if (!hasStoreJo && !hasStockJo) missing.add("上")
            if (!hasStoreGe && !hasStockGe) missing.add("下")

            return MatchedSeriesStatus(
                title = title,
                format = format,
                totalVolumes = 2,
                isCompletedSeries = true,
                storeVolumes = storeVols,
                stockVolumes = stockVols,
                stockVolumeCounts = stockCounts,
                stockVolumeDetails = stockDetails,
                mergedVolumes = (storeVols + stockVols).distinct(),
                missingVolumes = missing,
                canCompleteByStore = storeComp,
                canCompleteByMerged = mergedComp,
                note = "上下巻完結"
            )
        }

        val allVols = (storeVols + stockVols).distinct()
        val maxNumeric = allVols.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1
        val total = knownTotal ?: maxNumeric
        val isCompleted = knownTotal != null

        val missing = mutableListOf<String>()
        if (isCompleted) {
            for (i in 1..total) {
                if (!allVols.contains(i.toString())) {
                    missing.add(i.toString())
                }
            }
        }

        val storeSet = storeVols.toSet()
        val storeComp = isCompleted && (1..total).all { storeSet.contains(it.toString()) }
        val mergedComp = isCompleted && missing.isEmpty()

        return MatchedSeriesStatus(
            title = title,
            format = format,
            totalVolumes = total,
            isCompletedSeries = isCompleted,
            storeVolumes = storeVols,
            stockVolumes = stockVols,
            stockVolumeCounts = stockCounts,
            stockVolumeDetails = stockDetails,
            mergedVolumes = allVols,
            missingVolumes = missing,
            canCompleteByStore = storeComp,
            canCompleteByMerged = mergedComp,
            note = if (knownTotal != null) "全${knownTotal}巻完結" else "推定最大${total}巻"
        )
    }

    private fun normalize(t: String): String {
        return t.replace(Regex("[（(].*?[）)]"), "").replace("\\s+".toRegex(), "").lowercase()
    }
}
