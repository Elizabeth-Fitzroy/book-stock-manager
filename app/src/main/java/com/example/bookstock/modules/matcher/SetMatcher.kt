package com.example.bookstock.modules.matcher

import com.example.bookstock.modules.stock.StockProvider
import com.example.bookstock.modules.vision.DecisionResult
import com.example.bookstock.modules.vision.ScannedSpine

/**
 * モジュール3: 巻数突合・セット判定モジュール (Kotlin版)
 * 仕様書: docs/03_set_matcher.md
 * 
 * 通常コンプ判定に加え、
 * ・「B」（非初版・重版の帯付き）のみでの全巻コンプ判定
 * ・「BS」（初版かつ帯付き）のみでの全巻コンプ判定
 * を行います（BとBSは混ぜずに厳格に分離判定）。
 */

data class MatchedSeriesStatus(
    val title: String,
    val totalVolumes: Int,
    val isCompletedSeries: Boolean,
    val formatType: String? = null,
    val storeVolumes: List<String>,
    val stockVolumes: List<String>,
    val mergedVolumes: List<String>,
    val missingVolumes: List<String>,
    val canCompleteByStore: Boolean,
    val canCompleteByMerged: Boolean,
    // 帯付きコンディション独立判定（BとBSは混ぜない）
    val stockBVolumes: List<String> = emptyList(),       // 自宅在庫の「B」（非初版帯付き）巻数
    val stockBSVolumes: List<String> = emptyList(),      // 自宅在庫の「BS」（初版帯付き）巻数
    val storeObiVolumes: List<String> = emptyList(),     // 店頭で帯付きの巻数
    val canCompleteByB: Boolean = false,                 // B（重版帯付き）だけで全巻揃うか
    val canCompleteByBS: Boolean = false,                // BS（初版帯付き）だけで全巻揃うか
    val note: String,
    val decisionResult: DecisionResult? = null
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

    fun match(
        scannedBooks: List<ScannedSpine>,
        stockProvider: StockProvider,
        decisionResult: DecisionResult? = null
    ): List<MatchedSeriesStatus> {
        val grouped = scannedBooks.groupBy { normalize(it.title) }

        return grouped.map { (_, books) ->
            val displayTitle = books.first().title
            val storeVols = books.map { it.volume }.distinct()
            val storeObiVols = books.filter { it.hasObi }.map { it.volume }.distinct()
            
            val stockVols = stockProvider.getOwnedVolumesByTitle(displayTitle)
            val stockBVols = stockProvider.getOwnedVolumesByCondition(displayTitle, "B")
            val stockBSVols = stockProvider.getOwnedVolumesByCondition(displayTitle, "BS")

            evaluate(
                title = displayTitle,
                storeVols = storeVols,
                storeObiVols = storeObiVols,
                stockVols = stockVols,
                stockBVols = stockBVols,
                stockBSVols = stockBSVols,
                decisionResult = decisionResult
            )
        }.sortedByDescending {
            when {
                it.canCompleteByBS -> 3
                it.canCompleteByB -> 2
                it.canCompleteByStore || it.canCompleteByMerged -> 1
                else -> 0
            }
        }
    }

    private fun evaluate(
        title: String,
        storeVols: List<String>,
        storeObiVols: List<String>,
        stockVols: List<String>,
        stockBVols: List<String>,
        stockBSVols: List<String>,
        decisionResult: DecisionResult? = null
    ): MatchedSeriesStatus {
        val norm = normalize(title)
        val knownTotal = knownSeries.entries.find { norm.contains(normalize(it.key)) }?.value
        val formatType = decisionResult?.formatType

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

            // B判定（上下巻）
            val bMerged = (stockBVols + storeObiVols).distinct()
            val canB = bMerged.contains("上") && bMerged.contains("下")

            // BS判定（上下巻・自宅在庫中心）
            val canBS = stockBSVols.contains("上") && stockBSVols.contains("下")

            return MatchedSeriesStatus(
                title = title,
                totalVolumes = 2,
                isCompletedSeries = true,
                formatType = formatType,
                storeVolumes = storeVols,
                stockVolumes = stockVols,
                mergedVolumes = (storeVols + stockVols).distinct(),
                missingVolumes = missing,
                canCompleteByStore = storeComp,
                canCompleteByMerged = mergedComp,
                stockBVolumes = stockBVols,
                stockBSVolumes = stockBSVols,
                storeObiVolumes = storeObiVols,
                canCompleteByB = canB,
                canCompleteByBS = canBS,
                note = "上下巻完結",
                decisionResult = decisionResult
            )
        }

        val allVols = (storeVols + stockVols).distinct()
        val maxNumeric = allVols.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1

        val decisionTotal = decisionResult?.totalVolumes
        val total = decisionTotal ?: (knownTotal ?: maxNumeric)

        // 完結ステータスの判定
        val status = decisionResult?.completionStatus
        val isCompleted: Boolean
        val noteText: String

        if (status != null) {
            when {
                status.contains("完結済み") -> {
                    isCompleted = true
                    noteText = "全${total}巻完結 (Decision-1確認)"
                }
                status.contains("更新停止") -> {
                    isCompleted = true
                    noteText = "既刊${total}巻・更新停止 (事実上全巻)"
                }
                status.contains("長期休載") -> {
                    isCompleted = false
                    noteText = "既刊${total}巻 (長期休載中)"
                }
                status.contains("現在進行系") -> {
                    isCompleted = false
                    noteText = "既刊${total}巻 (連載中)"
                }
                status.contains("変則連載") -> {
                    isCompleted = false
                    noteText = "既刊${total}巻 (変則刊行)"
                }
                else -> {
                    isCompleted = knownTotal != null
                    noteText = if (knownTotal != null) "全${knownTotal}巻完結" else "推定既刊${total}巻"
                }
            }
        } else {
            isCompleted = knownTotal != null
            noteText = if (knownTotal != null) "全${knownTotal}巻完結" else "推定最大${total}巻"
        }

        // 不足巻数の算出
        val missing = mutableListOf<String>()
        if (isCompleted || decisionTotal != null) {
            for (i in 1..total) {
                if (!allVols.contains(i.toString())) {
                    missing.add(i.toString())
                }
            }
        }

        val storeSet = storeVols.toSet()
        var storeComp = isCompleted && (1..total).all { storeSet.contains(it.toString()) }
        if (decisionResult?.isStoreComplete == true && isCompleted && missing.isEmpty()) {
            storeComp = true
        }

        val mergedComp = isCompleted && missing.isEmpty()

        // ----------------------------------------------------
        // コンディション別・独立全巻コンプ判定（BとBSは混ぜない）
        // ----------------------------------------------------
        val requiredVols = (1..total).map { it.toString() }

        // ①「B」（非初版・重版の帯付き）のみでの判定
        // 自宅のB所持巻 ＋ 店頭の帯付き巻 を合体させて全巻揃うか
        val bMergedSet = (stockBVols + storeObiVols).toSet()
        val canCompB = isCompleted && requiredVols.isNotEmpty() && requiredVols.all { bMergedSet.contains(it) }

        // ②「BS」（初版帯付き）のみでの判定
        // 自宅のBS所持巻だけで全巻揃っているか
        val bsStockSet = stockBSVols.toSet()
        val canCompBS = isCompleted && requiredVols.isNotEmpty() && requiredVols.all { bsStockSet.contains(it) }

        return MatchedSeriesStatus(
            title = title,
            totalVolumes = total,
            isCompletedSeries = isCompleted,
            formatType = formatType,
            storeVolumes = storeVols,
            stockVolumes = stockVols,
            mergedVolumes = allVols,
            missingVolumes = missing,
            canCompleteByStore = storeComp,
            canCompleteByMerged = mergedComp,
            stockBVolumes = stockBVols,
            stockBSVolumes = stockBSVols,
            storeObiVolumes = storeObiVols,
            canCompleteByB = canCompB,
            canCompleteByBS = canCompBS,
            note = noteText,
            decisionResult = decisionResult
        )
    }

    private fun normalize(t: String): String {
        return t.replace(Regex("[（(].*?[）)]"), "").replace("\\s+".toRegex(), "").lowercase()
    }
}
