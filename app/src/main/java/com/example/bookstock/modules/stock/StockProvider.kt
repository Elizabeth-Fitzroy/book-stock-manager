package com.example.bookstock.modules.stock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * モジュール1: 在庫データ連携モジュール (Kotlin版)
 * 仕様書: docs/01_stock_provider.md
 * 参照先: Stock_Summary シート (gid=1166961180)
 */

data class StockItem(
    val isbn: String,
    val category: String,
    val seriesTitle: String,
    val title: String,
    val subTitle: String,
    val publisher: String,
    val volume: String?,
    val totalCount: Int,
    val actualCount: Int,
    // コンディション4区分（無印を先頭に判定）
    val isUnmarked: Boolean,        // 1. 無印 (コンディション未記入・未チェック)
    val hasObi: Boolean,            // 2. 帯あり
    val isFirstEdition: Boolean,    // 3. 初版
    val isDamaged: Boolean          // 4. ダメージ品 (ヤケ/折れ/破れ/濡れのいずれか)
)

class StockProvider(
    private val sheetCsvUrl: String = "https://docs.google.com/spreadsheets/d/1goIhHeIiuA5hBK-748TVNhwnR267KekETNyW_nrjZ9w/export?format=csv&gid=1166961180"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var items = listOf<StockItem>()
    private val isbnMap = mutableMapOf<String, StockItem>()
    var lastSyncTime: Long? = null
        private set

    /**
     * スプレッドシートからCSVデータを非同期で取得して初期化
     */
    suspend fun syncFromSheet(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d("BookStock", "スプレッドシート(Stock_Summary)取得開始: $sheetCsvUrl")
            val request = Request.Builder().url(sheetCsvUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errMsg = "HTTPエラー: ${response.code} ${response.message}"
                    Log.e("BookStock", errMsg)
                    return@withContext Result.failure(Exception(errMsg))
                }
                val csv = response.body?.string() ?: ""
                items = parseCsv(csv)
                lastSyncTime = System.currentTimeMillis()

                isbnMap.clear()
                for (item in items) {
                    if (item.isbn.isNotEmpty()) {
                        isbnMap[item.isbn] = item
                    }
                }
                Log.d("BookStock", "スプレッドシート取得成功: ${items.size} 件")
                Result.success(items.size)
            }
        } catch (e: Exception) {
            Log.e("BookStock", "スプレッドシート取得例外: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * キーワード（書名・シリーズ名・ISBN）による高速検索
     */
    fun searchByKeyword(query: String): List<StockItem> {
        if (query.isBlank()) return items.take(100)
        val q = query.trim().lowercase()
        return items.filter { item ->
            item.title.lowercase().contains(q) ||
            item.seriesTitle.lowercase().contains(q) ||
            item.isbn.contains(q) ||
            item.publisher.lowercase().contains(q)
        }
    }

    /**
     * 作品名またはシリーズ名から所持している巻数一覧を取得
     */
    fun getOwnedVolumesByTitle(titleOrSeries: String): List<String> {
        if (titleOrSeries.isBlank()) return emptyList()
        val q = titleOrSeries.trim().lowercase()

        return items.filter { item ->
            val matchTitle = item.title.lowercase().contains(q) || q.contains(item.title.lowercase())
            val matchSeries = item.seriesTitle.isNotEmpty() && (item.seriesTitle.lowercase().contains(q) || q.contains(item.seriesTitle.lowercase()))
            val hasStock = item.totalCount > 0 || item.actualCount > 0
            (matchTitle || matchSeries) && hasStock && item.volume != null
        }.mapNotNull { it.volume }.distinct().sorted()
    }

    fun getTotalCount(): Int = items.size

    private fun parseCsv(csvText: String): List<StockItem> {
        val lines = csvText.lines()
        if (lines.size < 2) return emptyList()

        val result = mutableListOf<StockItem>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            val cols = splitCsvLine(line)
            if (cols.size < 4) continue

            val isbn = cols.getOrElse(0) { "" }.replace(Regex("[^0-9X]"), "")
            val category = cols.getOrElse(1) { "" }
            val seriesTitle = cols.getOrElse(2) { "" }
            val title = cols.getOrElse(3) { "" }
            val subTitle = cols.getOrElse(4) { "" }
            val publisher = cols.getOrElse(5) { "" }
            val totalCount = cols.getOrElse(6) { "0" }.toIntOrNull() ?: 0
            val actualCount = cols.getOrElse(12) { "0" }.toIntOrNull() ?: 0

            // コンディション列のパース
            // 7: 帯, 8: 初版, 14: ヤケ, 15: 折れ, 16: 破れ, 17: 濡れ
            val obiRaw = cols.getOrElse(7) { "" }.trim()
            val firstRaw = cols.getOrElse(8) { "" }.trim()
            val yakeRaw = cols.getOrElse(14) { "" }.trim()
            val oreRaw = cols.getOrElse(15) { "" }.trim()
            val yabureRaw = cols.getOrElse(16) { "" }.trim()
            val nureRaw = cols.getOrElse(17) { "" }.trim()

            val hasObi = obiRaw == "1"
            val isFirstEdition = firstRaw == "1"
            val isDamaged = yakeRaw == "1" || oreRaw == "1" || yabureRaw == "1" || nureRaw == "1"

            // 1. 無印 (帯・初版・ダメージのすべてが未記入・未チェック)
            val isUnmarked = !hasObi && !isFirstEdition && !isDamaged

            val volume = extractVolume(title, subTitle)

            result.add(
                StockItem(
                    isbn = isbn,
                    category = category,
                    seriesTitle = seriesTitle,
                    title = title,
                    subTitle = subTitle,
                    publisher = publisher,
                    volume = volume,
                    totalCount = totalCount,
                    actualCount = actualCount,
                    isUnmarked = isUnmarked,
                    hasObi = hasObi,
                    isFirstEdition = isFirstEdition,
                    isDamaged = isDamaged
                )
            )
        }
        return result
    }

    private fun extractVolume(title: String, subTitle: String): String? {
        val text = "$title $subTitle"
        if (Regex("[（(]?上[）)]?$").containsMatchIn(title) || text.contains("上巻")) return "上"
        if (Regex("[（(]?下[）)]?$").containsMatchIn(title) || text.contains("下巻")) return "下"
        if (Regex("[（(]?中[）)]?$").containsMatchIn(title) || text.contains("中巻")) return "中"

        val numMatch = Regex("(\\d+)\\s*(?:巻|話)?$").find(text) ?: Regex("(?:第)?\\s*(\\d+)\\s*(?:巻|話)").find(text)
        if (numMatch != null) return numMatch.groupValues[1]

        val kanjiMatch = Regex("[（(]?([一二三四五六七八九十]+)[）)]?$").find(text)
        if (kanjiMatch != null) {
            val map = mapOf("一" to "1", "二" to "2", "三" to "3", "四" to "4", "五" to "5", "六" to "6", "七" to "7", "八" to "8", "九" to "9", "十" to "10")
            return map[kanjiMatch.groupValues[1]] ?: kanjiMatch.groupValues[1]
        }
        return null
    }

    private fun splitCsvLine(line: String): List<String> {
        val list = mutableListOf<String>()
        var insideQuote = false
        val sb = StringBuilder()
        for (c in line) {
            when (c) {
                '"' -> insideQuote = !insideQuote
                ',' -> {
                    if (insideQuote) sb.append(c)
                    else {
                        list.add(sb.toString().trim())
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
        list.add(sb.toString().trim())
        return list
    }
}
