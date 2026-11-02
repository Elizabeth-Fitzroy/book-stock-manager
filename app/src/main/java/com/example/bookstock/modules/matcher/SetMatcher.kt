package com.example.bookstock.modules.matcher

import com.example.bookstock.modules.stock.StockProvider
import com.example.bookstock.modules.vision.ScannedSpine

/**
 * モジュール3: 巻数突合・セット判定モジュール (Kotlin版)
 * 仕様書: docs/03_set_matcher.md
 */

data class MatchedSeriesStatus(
    val title: String,
    val totalVolumes: Int,
    val isCompletedSeries: Boolean,
    val storeVolumes: List<String>,
    val stockVolumes: List<String>,
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
        "がんばれ元気" to 10
    )

    fun match(scannedBooks: List<ScannedSpine>, stockProvider: StockProvider): List<MatchedSeriesStatus> {
        val grouped = scannedBooks.groupBy { normalize(it.title) }

        return grouped.map { (_, books) ->
            val displayTitle = books.first().title
            val storeVols = books.map { it.volume }.distinct()
            val stockVols = stockProvider.getOwnedVolumesByTitle(displayTitle)
            evaluate(displayTitle, storeVols, stockVols)
        }.sortedByDescending { if (it.canCompleteByStore || it.canCompleteByMerged) 1 else 0 }
    }

    private fun evaluate(title: String, storeVols: List<String>, stockVols: List<String>): MatchedSeriesStatus {
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
                totalVolumes = 2,
                isCompletedSeries = true,
                storeVolumes = storeVols,
                stockVolumes = stockVols,
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
            totalVolumes = total,
            isCompletedSeries = isCompleted,
            storeVolumes = storeVols,
            stockVolumes = stockVols,
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
