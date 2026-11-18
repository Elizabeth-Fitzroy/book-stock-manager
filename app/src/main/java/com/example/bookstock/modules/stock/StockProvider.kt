package com.example.bookstock.modules.stock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * モジュール1: 在庫データ連携モジュール (Kotlin版)
 * 参照先: Ledger シート (gid=70342279)
 * 
 * 1冊ずつの明細（Ledger）から「ステータスが Sold 以外」の在庫を集計。
 * コンディション列の空欄は「D」（総合ランクD・標準コンディション）とみなし、
 * コンディション記号（D, B, BS, ST 等）ごとに冊数を集計・保持します。
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
    // コンディション記号ごとの冊数内訳（例: "D" -> 2, "B" -> 3, "BS" -> 1, "ST" -> 1）
    val conditionCounts: Map<String, Int> = emptyMap(),
    // 互換用プロパティ
    val isUnmarked: Boolean = false,
    val hasObi: Boolean = false,
    val isFirstEdition: Boolean = false,
    val isDamaged: Boolean = false
)

data class SyncStats(
    val totalRowsInSheet: Int = 0,
    val validRows: Int = 0,
    val soldExcluded: Int = 0,
    val shortColumnsSkipped: Int = 0,
    val blankRowsSkipped: Int = 0,
    val totalPhysicalBooks: Int = 0,
    val distinctGroupCount: Int = 0,
    val statusBreakdown: Map<String, Int> = emptyMap()
)

class StockProvider(
    private val spreadsheetId: String = "1goIhHeIiuA5hBK-748TVNhwnR267KekETNyW_nrjZ9w",
    private val sheetGid: String = "70342279"
) {
    private val sheetCsvUrl: String = "https://docs.google.com/spreadsheets/d/$spreadsheetId/export?format=csv&gid=$sheetGid"
    private val sheetsApiUrl: String = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/Ledger"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var items = listOf<StockItem>()
    private val isbnMap = mutableMapOf<String, StockItem>()
    var lastSyncTime: Long? = null
        private set
    var lastSyncStats: SyncStats = SyncStats()
        private set

    /**
     * スプレッドシート（Ledger）からデータを非同期で取得して初期化・集計
     * accessToken が渡された場合は非公開スプレッドシート対応の Google Sheets API v4 を使用し、
     * 未指定の場合は公開CSVエクスポートURLからダウンロードします。
     */
    suspend fun syncFromSheet(accessToken: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val parsedItems: List<StockItem> = if (!accessToken.isNullOrEmpty()) {
                Log.d("BookStock", "Google Sheets API (v4) 経由で取得開始: $sheetsApiUrl")
                val request = Request.Builder()
                    .url(sheetsApiUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = "Sheets API エラー: ${response.code} ${response.message}"
                        Log.e("BookStock", errMsg)
                        throw Exception(errMsg)
                    }
                    val jsonStr = response.body?.string() ?: ""
                    parseSheetsApiJson(jsonStr)
                }
            } else {
                Log.d("BookStock", "公開CSVエクスポート経由で取得開始: $sheetCsvUrl")
                val request = Request.Builder().url(sheetCsvUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = "HTTPエラー: ${response.code} ${response.message}"
                        Log.e("BookStock", errMsg)
                        throw Exception(errMsg)
                    }
                    val csv = response.body?.string() ?: ""
                    parseLedgerCsv(csv)
                }
            }

            items = parsedItems
            lastSyncTime = System.currentTimeMillis()

            isbnMap.clear()
            for (item in items) {
                if (item.isbn.isNotEmpty()) {
                    isbnMap[item.isbn] = item
                }
            }
            Log.d("BookStock", "Ledger集計成功: ${items.size} 作品・巻数グループ")
            Result.success(items.size)
        } catch (e: Exception) {
            Log.e("BookStock", "スプレッドシート取得例外: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Google Sheets API v4 のレスポンス JSON から行データを抽出
     */
    private fun parseSheetsApiJson(jsonStr: String): List<StockItem> {
        val root = JSONObject(jsonStr)
        val values = root.optJSONArray("values") ?: return emptyList()
        val rows = mutableListOf<List<String>>()
        for (i in 0 until values.length()) {
            val rowArr = values.optJSONArray(i) ?: continue
            val row = mutableListOf<String>()
            for (j in 0 until rowArr.length()) {
                row.add(rowArr.optString(j, ""))
            }
            rows.add(row)
        }
        return processLedgerRows(rows)
    }

    /**
     * CSV文字列から行データを分割・パース
     */
    private fun parseLedgerCsv(csv: String): List<StockItem> {
        val lines = csv.lines().filter { it.isNotBlank() }
        val rows = lines.map { splitCsvLine(it) }
        return processLedgerRows(rows)
    }

    /**
     * キーワード（書名・シリーズ名・ISBN・著者）による高速検索
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
     * 作品名またはシリーズ名から所持している巻数一覧を取得（通常在庫）
     */
    fun getOwnedVolumesByTitle(titleOrSeries: String): List<String> {
        if (titleOrSeries.isBlank()) return emptyList()
        val q = normalizeTitle(titleOrSeries)

        return items.filter { item ->
            val normTitle = normalizeTitle(item.title)
            val normSeries = normalizeTitle(item.seriesTitle)
            val match = normTitle.contains(q) || q.contains(normTitle) ||
                        (normSeries.isNotEmpty() && (normSeries.contains(q) || q.contains(normSeries)))
            match && item.totalCount > 0 && item.volume != null
        }.mapNotNull { it.volume }.distinct().sorted()
    }

    /**
     * 特定のコンディション（例: "B" = 初版ではない帯付き）を満たす所持巻数一覧を取得
     */
    fun getOwnedVolumesByCondition(titleOrSeries: String, conditionKey: String): List<String> {
        if (titleOrSeries.isBlank()) return emptyList()
        val q = normalizeTitle(titleOrSeries)

        return items.filter { item ->
            val normTitle = normalizeTitle(item.title)
            val normSeries = normalizeTitle(item.seriesTitle)
            val match = normTitle.contains(q) || q.contains(normTitle) ||
                        (normSeries.isNotEmpty() && (normSeries.contains(q) || q.contains(normSeries)))
            val count = item.conditionCounts[conditionKey] ?: 0
            match && count > 0 && item.volume != null
        }.mapNotNull { it.volume }.distinct().sorted()
    }

    /**
     * 巻数ごとの詳細マップ（巻数 -> conditionCounts）を取得
     */
    fun getVolumeConditionMap(titleOrSeries: String): Map<String, Map<String, Int>> {
        if (titleOrSeries.isBlank()) return emptyMap()
        val q = normalizeTitle(titleOrSeries)

        val result = mutableMapOf<String, Map<String, Int>>()
        items.filter { item ->
            val normTitle = normalizeTitle(item.title)
            val normSeries = normalizeTitle(item.seriesTitle)
            val match = normTitle.contains(q) || q.contains(normTitle) ||
                        (normSeries.isNotEmpty() && (normSeries.contains(q) || q.contains(normSeries)))
            match && item.volume != null
        }.forEach { item ->
            item.volume?.let { vol ->
                result[vol] = item.conditionCounts
            }
        }
        return result
    }

    fun getTotalCount(): Int = items.size

    /**
     * Ledgerの個別行データ
     */
    private data class RawLedgerRow(
        val isbn: String,
        val author: String,
        val seriesTitle: String,
        val title: String,
        val subTitle: String,
        val condition: String,
        val status: String,
        val volume: String?
    )

    private fun processLedgerRows(rows: List<List<String>>): List<StockItem> {
        if (rows.size < 2) return emptyList()

        val rawRows = mutableListOf<RawLedgerRow>()
        var soldExcluded = 0
        var shortCols = 0
        var blankRows = 0
        val statusMap = mutableMapOf<String, Int>()

        // 0:仕入れID, 1:ISBN, 2:著者, 3:シリーズ, 4:書名, 5:Subtitle, 6:コンディション, ..., 10:ステータス
        for (i in 1 until rows.size) {
            val cols = rows[i]
            if (cols.all { it.isBlank() }) {
                blankRows++
                continue
            }
            if (cols.size < 5 || (cols.getOrElse(4) { "" }.isBlank() && cols.getOrElse(1) { "" }.isBlank())) {
                shortCols++
                continue
            }

            val isbn = cols.getOrElse(1) { "" }.replace(Regex("[^0-9X]"), "")
            val author = cols.getOrElse(2) { "" }
            val seriesTitle = cols.getOrElse(3) { "" }
            val title = cols.getOrElse(4) { "" }
            val subTitle = cols.getOrElse(5) { "" }
            var condition = cols.getOrElse(6) { "" }.trim()
            val status = cols.getOrElse(10) { "" }.trim()

            val normStatus = if (status.isBlank()) "(空欄)" else status
            statusMap[normStatus] = (statusMap[normStatus] ?: 0) + 1

            // 売却済み（Sold）や非売品（NotSale）は除外
            if (status.equals("Sold", ignoreCase = true) || status.equals("NotSale", ignoreCase = true)) {
                soldExcluded++
                continue
            }

            // 空欄は総合ランク「D」（標準コンディション）とみなす
            if (condition.isBlank()) {
                condition = "D"
            }

            val volume = extractVolume(title, subTitle)

            rawRows.add(
                RawLedgerRow(
                    isbn = isbn,
                    author = author,
                    seriesTitle = seriesTitle,
                    title = title,
                    subTitle = subTitle,
                    condition = condition,
                    status = status,
                    volume = volume
                )
            )
        }

        // 書名 + 巻数（またはシリーズ名 + 巻数）ごとに集約
        val grouped = rawRows.groupBy {
            val baseTitle = cleanTitleForGroup(it.title)
            val vol = it.volume ?: ""
            "${baseTitle}___${vol}"
        }

        lastSyncStats = SyncStats(
            totalRowsInSheet = rows.size - 1, // ヘッダー除く
            validRows = rawRows.size + soldExcluded,
            soldExcluded = soldExcluded,
            shortColumnsSkipped = shortCols,
            blankRowsSkipped = blankRows,
            totalPhysicalBooks = rawRows.size,
            distinctGroupCount = grouped.size,
            statusBreakdown = statusMap
        )

        Log.d("BookStock", "=== Ledger同期統計詳細 ===")
        Log.d("BookStock", "シート総行数: ${rows.size - 1} 行")
        Log.d("BookStock", "空行除外: $blankRows, 不正/短行除外: $shortCols")
        Log.d("BookStock", "ステータス内訳: $statusMap")
        Log.d("BookStock", "Sold/NotSale除外: $soldExcluded 行")
        Log.d("BookStock", "在庫物理冊数: ${rawRows.size} 冊")
        Log.d("BookStock", "重複集約後の作品・巻数グループ数: ${grouped.size} 件")
        Log.d("BookStock", "===========================")

        val result = mutableListOf<StockItem>()
        for ((_, rows) in grouped) {
            val first = rows.first()
            val baseTitle = cleanTitleForGroup(first.title)
            val volume = first.volume

            // コンディションごとの冊数を集計
            val condCounts = mutableMapOf<String, Int>()
            for (r in rows) {
                condCounts[r.condition] = (condCounts[r.condition] ?: 0) + 1
            }

            val total = rows.size
            val hasObi = condCounts.keys.any { it.contains("B", ignoreCase = true) }
            val hasFirst = condCounts.keys.any { it.contains("S", ignoreCase = true) }
            val isDamaged = condCounts.containsKey("D")

            result.add(
                StockItem(
                    isbn = first.isbn,
                    category = "",
                    seriesTitle = first.seriesTitle,
                    title = baseTitle,
                    subTitle = first.subTitle,
                    publisher = first.author, // 出版社または著者情報
                    volume = volume,
                    totalCount = total,
                    actualCount = total,
                    conditionCounts = condCounts,
                    isUnmarked = condCounts.containsKey("D") && condCounts.size == 1,
                    hasObi = hasObi,
                    isFirstEdition = hasFirst,
                    isDamaged = isDamaged
                )
            )
        }

        return result
    }

    private fun cleanTitleForGroup(title: String): String {
        return title
            .replace(Regex("\\s*0?\\d+\\s*(?:巻|話)?$"), "")
            .replace(Regex("\\s*(?:上|下|中)巻?$"), "")
            .trim()
    }

    private fun normalizeTitle(t: String): String {
        return t.replace(Regex("[（(].*?[）)]"), "").replace("\\s+".toRegex(), "").lowercase()
    }

    private fun extractVolume(title: String, subTitle: String): String? {
        val text = "$title $subTitle"
        if (Regex("[（(]?上[）)]?$").containsMatchIn(title) || text.contains("上巻")) return "上"
        if (Regex("[（(]?下[）)]?$").containsMatchIn(title) || text.contains("下巻")) return "下"
        if (Regex("[（(]?中[）)]?$").containsMatchIn(title) || text.contains("中巻")) return "中"

        // "海の闇、月の影 02" や "獣の奏者 1" 等の末尾数字
        val numMatch = Regex("(?:第)?\\s*0?(\\d+)\\s*(?:巻|話)?$").find(text)
            ?: Regex("(?:第)?\\s*0?(\\d+)\\s*(?:巻|話)").find(text)
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
