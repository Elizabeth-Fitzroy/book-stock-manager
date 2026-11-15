package com.example.bookstock.modules.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * モジュール4: 相場・外部リサーチモジュール (Kotlin版・Gemini推論連携＆直接通信完全撤廃)
 * 仕様書: docs/04_market_researcher.md
 */

data class DirectMarketPrice(
    val title: String,
    val mercariSoldPrice: Int?,      // メルカリ直近売切相場 (Gemini推論値・円)
    val amazonLowestPrice: Int?,    // Amazon中古最安値 (Gemini推論値・円)
    val estimatedProfit: Int?,      // 見込み純利益 (円)
    val profitMarginPercent: Float?,// 粗利率 (%)
    val mercariDetailUrl: String,   // タップで開くメルカリ検索URL
    val amazonDetailUrl: String     // タップで開くAmazon検索URL
)

class MarketResearcher(
    private val defaultShippingCost: Int = 750, // 宅急便60サイズ想定
    private val defaultCostPerBook: Int = 110   // 110円棚想定
) {
    /**
     * Geminiの推論値または推定巻数から純利益を安全に計算する
     * ※ メルカリやAmazonへの危険な直接スクレイピング・独自通信は行いません
     */
    suspend fun estimateMarketPrice(
        title: String,
        storeBuyCount: Int = 0,
        totalVolumes: Int = 1,
        isCompleteSet: Boolean = true,
        aiPredictedPrice: Int? = null
    ): DirectMarketPrice = withContext(Dispatchers.Default) {
        val keyword = if (isCompleteSet) "$title 全巻" else "$title $totalVolumes"
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")

        val mercariUrl = "https://jp.mercari.com/search?keyword=$encodedKeyword&status=sold_out&sort=created_time&order=desc"
        val amazonUrl = "https://www.amazon.co.jp/s?k=$encodedKeyword&i=stripbooks"

        // Geminiの推論値がある場合はそれを採用、ない場合は冊数ベースの安全な相場推計 (1冊あたり平均250〜350円)
        val mercariPrice = aiPredictedPrice ?: run {
            if (totalVolumes > 0) {
                // 概算推論: 巻数 x 300円 (完結セット加算)
                val base = totalVolumes * 300
                if (isCompleteSet) (base * 1.2).toInt() else base
            } else {
                null
            }
        }

        val amazonPrice = mercariPrice?.let { (it * 1.15).toInt() }

        var netProfit: Int? = null
        var margin: Float? = null

        if (mercariPrice != null && mercariPrice > 0) {
            val purchaseCost = storeBuyCount * defaultCostPerBook
            val fee = (mercariPrice * 0.10f).toInt()
            val profit = mercariPrice - purchaseCost - defaultShippingCost - fee
            netProfit = profit
            margin = (profit.toFloat() / mercariPrice.toFloat()) * 100f
        }

        DirectMarketPrice(
            title = title,
            mercariSoldPrice = mercariPrice,
            amazonLowestPrice = amazonPrice,
            estimatedProfit = netProfit,
            profitMarginPercent = margin,
            mercariDetailUrl = mercariUrl,
            amazonDetailUrl = amazonUrl
        )
    }
}
