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
    // コンディション別数量（Int）
    val obiCount: Int,            // 帯あり冊数
    val firstEditionCount: Int,   // 初版冊数
    val newEditionCount: Int,     // 新装版冊数
    val damagedCount: Int,        // ダメージ品冊数 (ヤケ/折れ/破れ/濡れ/汚れの合計)
    val unmarkedCount: Int        // 通常・無印冊数
) {
    // 互換性および状態チェック用のプロパティ
    val hasObi: Boolean get() = obiCount > 0
    val isFirstEdition: Boolean get() = firstEditionCount > 0
    val isNewEdition: Boolean get() = newEditionCount > 0
    val isDamaged: Boolean get() = damagedCount > 0
    val isUnmarked: Boolean get() = unmarkedCount > 0
    val effectiveCount: Int get() = maxOf(totalCount, actualCount)
    val isZeroStock: Boolean get() = effectiveCount <= 0
}

class StockProvider(
    private val sheetCsvUrl: String = "https://docs.google.com/spreadsheets/d/e/2PACX-1vRI1Jbou0d4HP9zoiK6neKU-ldXG8_aeobItFB5GxOIwoqGIIG6br_dpKDtadpVUTeC9RJOy9qCTSvZ/pub?gid=1166961180&single=true&output=csv"
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

    companion object {
        /**
         * テキストから含まれるすべてのISBN（13桁または10桁）を抽出する
         */
        fun extractAllIsbns(text: String): List<String> {
            val list = mutableListOf<String>()
            // 13桁 (978, 979)
            val isbn13Regex = Regex("(?<!\\d)97[89][\\d\\-]{10,14}(?!\\d)")
            isbn13Regex.findAll(text).forEach { m ->
                val clean = m.value.replace("-", "")
                if (clean.length == 13) list.add(clean)
            }
            // 10桁
            val isbn10Regex = Regex("(?<!\\d)[\\d\\-]{9,13}[\\dXx](?!\\d)")
            isbn10Regex.findAll(text).forEach { m ->
                val clean = m.value.replace("-", "").uppercase()
                if (clean.length == 10 && !list.contains(clean)) list.add(clean)
            }
            return list.distinct()
        }

        /**
         * 検索語内のローマ数字（Ⅰ〜Ⅹ）や丸数字を数字に正規化
         */
        fun normalizeSearchQuery(q: String): String {
            var res = q
            val romanMap = mapOf(
                "Ⅰ" to "1", "Ⅱ" to "2", "Ⅲ" to "3", "Ⅳ" to "4", "Ⅴ" to "5",
                "Ⅵ" to "6", "Ⅶ" to "7", "Ⅷ" to "8", "Ⅸ" to "9", "Ⅹ" to "10",
                "I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5",
                "VI" to "6", "VII" to "7", "VIII" to "8", "IX" to "9", "X" to "10"
            )
            for ((r, v) in romanMap) {
                res = res.replace(Regex("(^|[\\s（(])$r([\\s）)]|$)"), "$1$v$2")
            }
            return res
        }
    }

    /**
     * 高度な在庫検索:
     * 1. ISBNリストが含まれる場合 ➔ 該当する全ISBNの在庫を一括表示 (日本語混じりリスト完全対応)
     * 2. 複数行（改行区切りの書名リスト）の場合 ➔ 各行ごとに検索し、結果をまとめて一覧表示 (行単位まとめ検索)
     * 3. 単一行キーワードの場合 ➔ スペース区切りAND検索 (タイトル、出版形態/カテゴリ、巻数、サブタイトル、出版社を網羅)
     */
    fun searchByKeyword(query: String, formatFilter: String = "すべて"): List<StockItem> {
        val raw = query.trim()
        val isbns = extractAllIsbns(raw)

        val baseList = if (isbns.isNotEmpty()) {
            // 分岐①: ISBNリストモード (いずれかのISBNに合致する本を一括抽出)
            items.filter { item -> isbns.contains(item.isbn) }
        } else if (raw.isBlank()) {
            items.take(100)
        } else {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size > 1) {
                // 分岐②: 複数行の書名リストモード (各行ごとに検索して合算)
                val resultSet = LinkedHashSet<StockItem>()
                for (line in lines) {
                    val normLine = normalizeSearchQuery(line)
                    val tokens = normLine.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    val matched = items.filter { item ->
                        val searchable = "${item.title} ${item.seriesTitle} ${item.category} ${item.publisher} ${item.subTitle} ${item.volume ?: ""}".lowercase()
                        tokens.all { token -> searchable.contains(token) }
                    }
                    resultSet.addAll(matched)
                }
                resultSet.toList()
            } else {
                // 分岐③: 単一行のスペース区切りAND検索モード
                val normQuery = normalizeSearchQuery(raw)
                val tokens = normQuery.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
                items.filter { item ->
                    val searchable = "${item.title} ${item.seriesTitle} ${item.category} ${item.publisher} ${item.subTitle} ${item.volume ?: ""}".lowercase()
                    tokens.all { token -> searchable.contains(token) }
                }
            }
        }

        // フィルターチップ（すべて / 文庫 / 単行本 / コミックス）の適用
        return when (formatFilter) {
            "文庫" -> baseList.filter { it.category.contains("文庫") || it.seriesTitle.contains("文庫") }
            "単行本" -> baseList.filter { it.category.contains("単行本") || it.category.contains("単") }
            "コミックス" -> baseList.filter { it.category.contains("コミック") || it.category.contains("漫画") }
            else -> baseList
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

    /**
     * 巻ごとの在庫冊数マップを取得 (例: "1" -> 2冊)
     */
    /**
     * 作品名および判型（コミックス vs 文庫）から所持している巻数一覧を取得
     */
    fun getOwnedVolumesByTitle(titleOrSeries: String, targetFormat: String? = null): List<String> {
        return getOwnedVolumeCountsByTitle(titleOrSeries, targetFormat).keys.sorted()
    }

    /**
     * 巻ごとのコンディションサマリー情報
     */
    data class VolumeStockDetail(
        val count: Int,
        val firstEditionCount: Int = 0, // 初版冊数
        val obiCount: Int = 0,          // 帯あり冊数
        val newEditionCount: Int = 0,   // 新装版冊数
        val damagedCount: Int = 0,      // ヤケ/折れ/破れ/濡れ/汚れ冊数
        val unmarkedCount: Int = 0      // 無印/通常冊数
    ) {
        fun formatSummary(): String {
            val tags = mutableListOf<String>()
            if (firstEditionCount > 0) tags.add("初版$firstEditionCount")
            if (obiCount > 0) tags.add("帯$obiCount")
            if (newEditionCount > 0) tags.add("新装版$newEditionCount")
            if (damagedCount > 0) tags.add("傷み$damagedCount")
            if (unmarkedCount > 0) tags.add("通常$unmarkedCount")
            return if (tags.isNotEmpty()) tags.joinToString("/") else "通常$count"
        }
    }

    fun getOwnedVolumeDetailsByTitle(titleOrSeries: String, targetFormat: String? = null): Map<String, VolumeStockDetail> {
        if (titleOrSeries.isBlank()) return emptyMap()
        val q = titleOrSeries.trim().lowercase()
        val isBunkoTarget = targetFormat?.contains("文庫") == true

        val allTitleMatches = items.filter { item ->
            val matchTitle = item.title.lowercase().contains(q) || q.contains(item.title.lowercase())
            val matchSeries = item.seriesTitle.isNotEmpty() && (item.seriesTitle.lowercase().contains(q) || q.contains(item.seriesTitle.lowercase()))
            // 指輪物語・旅の仲間・二つの塔・王の帰還等の相互連動マッチング
            val matchTolkien = (q.contains("指輪") || q.contains("旅の仲間") || q.contains("二つの塔") || q.contains("王の帰還")) &&
                    (item.title.contains("旅の仲間") || item.title.contains("二つの塔") || item.title.contains("王の帰還") || item.seriesTitle.contains("指輪"))
            val hasStock = item.totalCount > 0 || item.actualCount > 0
            (matchTitle || matchSeries || matchTolkien) && hasStock && item.volume != null
        }

        var filtered = if (targetFormat != null) {
            allTitleMatches.filter { item ->
                val isItemBunko = item.category.contains("文庫") || item.seriesTitle.contains("文庫") || item.seriesTitle.contains("文")
                if (isBunkoTarget) isItemBunko else !isItemBunko
            }
        } else {
            allTitleMatches
        }

        // 指定判型で0件だが他判型に在庫がある場合、自動フォールバック
        if (filtered.isEmpty() && allTitleMatches.isNotEmpty()) {
            filtered = allTitleMatches
        }

        val map = mutableMapOf<String, VolumeStockDetail>()
        filtered.forEach { item ->
            val vol = item.volume!!
            val count = item.totalCount.coerceAtLeast(item.actualCount).coerceAtLeast(1)
            val current = map[vol] ?: VolumeStockDetail(0)

            map[vol] = VolumeStockDetail(
                count = current.count + count,
                firstEditionCount = current.firstEditionCount + item.firstEditionCount,
                obiCount = current.obiCount + item.obiCount,
                newEditionCount = current.newEditionCount + item.newEditionCount,
                damagedCount = current.damagedCount + item.damagedCount,
                unmarkedCount = current.unmarkedCount + item.unmarkedCount
            )
        }
        return map
    }

    /**
     * 作品名および判型（コミックス vs 文庫）から巻ごとの在庫冊数マップを取得
     */
    fun getOwnedVolumeCountsByTitle(titleOrSeries: String, targetFormat: String? = null): Map<String, Int> {
        return getOwnedVolumeDetailsByTitle(titleOrSeries, targetFormat).mapValues { it.value.count }
    }

    fun getTotalCount(): Int = items.size

    /**
     * CSVデータをパース（INDEX+MATCH 動的ヘッダー参照方式）
     * 列の並び順が変更・追加されても、ヘッダー行の名称から自動的に列インデックスを特定
     */
    private fun parseCsv(csvText: String): List<StockItem> {
        val lines = csvText.lines()
        if (lines.size < 2) return emptyList()

        // 1行目のヘッダー行を解析し、列名 -> 列インデックスのマップを作成
        val headerCols = splitCsvLine(lines[0])
        val headerMap = headerCols.mapIndexed { idx, name -> name.trim() to idx }.toMap()

        // 列名エイリアスから値を取得するヘルパー関数
        fun getColStr(cols: List<String>, vararg names: String): String {
            for (n in names) {
                val idx = headerMap[n]
                if (idx != null && idx < cols.size) {
                    val v = cols[idx].trim()
                    if (v.isNotEmpty()) return v
                }
            }
            return ""
        }

        fun getColInt(cols: List<String>, vararg names: String): Int {
            for (n in names) {
                val idx = headerMap[n]
                if (idx != null && idx < cols.size) {
                    val v = cols[idx].trim().toIntOrNull()
                    if (v != null) return v
                }
            }
            return 0
        }

        val result = mutableListOf<StockItem>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            val cols = splitCsvLine(line)
            if (cols.size < 2) continue

            val rawIsbn = getColStr(cols, "ISBN", "isbn")
            val isbn = rawIsbn.replace(Regex("[^0-9X]"), "")
            val category = getColStr(cols, "分類(レーベル)", "分類", "レーベル")
            val seriesTitle = getColStr(cols, "シリーズ名", "シリーズ")
            val title = getColStr(cols, "書名", "タイトル")
            val subTitle = getColStr(cols, "サブタイトル", "Subtitle")
            val publisher = getColStr(cols, "出版社")
            val totalCount = getColInt(cols, "総数")
            val actualCount = getColInt(cols, "実数")

            // 各コンディション数量（Int）
            val obiCount = getColInt(cols, "帯", "帯あり")
            val firstEditionCount = getColInt(cols, "初版")
            val newEditionCount = getColInt(cols, "新", "新装版")
            val yakeCount = getColInt(cols, "ヤケ")
            val oreCount = getColInt(cols, "折れ")
            val yabureCount = getColInt(cols, "破れ")
            val nureCount = getColInt(cols, "濡れ")
            val yogoreCount = getColInt(cols, "汚", "汚れ")

            // ダメージ品冊数（ヤケ/折れ/破れ/濡れ/汚れの合計）
            val damagedCount = yakeCount + oreCount + yabureCount + nureCount + yogoreCount

            // 有効在庫冊数
            val effectiveCount = maxOf(totalCount, actualCount)

            // 通常・無印冊数: 把握されている特殊コンディションを除いた残数
            val conditionMax = maxOf(obiCount, firstEditionCount, newEditionCount, damagedCount)
            val unmarkedCount = if (conditionMax == 0) {
                effectiveCount
            } else {
                maxOf(0, effectiveCount - conditionMax)
            }

            val volume = extractVolume(title, subTitle, seriesTitle)

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
                    obiCount = obiCount,
                    firstEditionCount = firstEditionCount,
                    newEditionCount = newEditionCount,
                    damagedCount = damagedCount,
                    unmarkedCount = unmarkedCount
                )
            )
        }
        return result
    }

    private fun extractVolume(title: String, subTitle: String, seriesTitle: String = ""): String? {
        val text = "$title $subTitle"

        // 1. 特殊編名（追補編、外伝、刹那等）
        if (text.contains("追補編") || text.contains("追補")) return "追補編"
        if (text.contains("最終章")) return "最終章"
        if (text.contains("外伝") || text.contains("刹那") || text.contains("新生者")) return "外伝"

        // 2. 「上下 ＋ 枝番数字」の最優先抽出 (指輪物語等: 上1, 上2, 下1, 下2)
        val joGeBranchMatch = Regex("([上下])\\s*([1-9])").find(text)
        if (joGeBranchMatch != null) {
            val (jog, num) = joGeBranchMatch.destructured
            return "$jog$num"
        }

        // 3. 「ローマ数字 ＋ 上下」の抽出 (トワイライト等: Ⅱ上, Ⅱ下, Ⅲ上, Ⅲ下, Ⅳ上, Ⅳ下)
        val romanMap = mapOf(
            "Ⅰ" to "1", "Ⅱ" to "2", "Ⅲ" to "3", "Ⅳ" to "4", "Ⅴ" to "5",
            "Ⅵ" to "6", "Ⅶ" to "7", "Ⅷ" to "8", "Ⅸ" to "9", "Ⅹ" to "10",
            "I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5",
            "VI" to "6", "VII" to "7", "VIII" to "8", "IX" to "9", "X" to "10"
        )
        for ((r, v) in romanMap) {
            val match = Regex("$r\\s*([上下])").find(text)
            if (match != null) {
                val jog = match.groupValues[1]
                return "$v-$jog" // 例: "2-上", "2-下"
            }
        }

        // 4. 単独の「上」「中」「下」判定
        if (Regex("[（(]?上[）)]?$").containsMatchIn(title) || text.contains("上巻") || text.endsWith("上")) return "上"
        if (Regex("[（(]?下[）)]?$").containsMatchIn(title) || text.contains("下巻") || text.endsWith("下")) return "下"
        if (Regex("[（(]?中[）)]?$").containsMatchIn(title) || text.contains("中巻") || text.endsWith("中")) return "中"

        // 5. 単独のローマ数字
        for ((r, v) in romanMap) {
            if (text.contains(r)) return v
        }

        // 6. 編名（獣の奏者等）
        val henMap = mapOf("闘蛇" to "1", "王獣" to "2", "探求" to "3", "完結" to "4")
        for ((h, v) in henMap) {
            if (text.contains(h)) return v
        }

        // 7. アラビア数字 (1巻, 第1巻, 末尾の数字)
        val numMatch = Regex("(\\d+)\\s*(?:巻|話)?$").find(text) ?: Regex("(?:第)?\\s*(\\d+)\\s*(?:巻|話)").find(text)
        if (numMatch != null) return numMatch.groupValues[1]

        // 8. 漢数字 (一巻〜十巻)
        val kanjiMatch = Regex("[（(]?([一二三四五六七八九十]+)[）)]?$").find(text)
        if (kanjiMatch != null) {
            val map = mapOf("一" to "1", "二" to "2", "三" to "3", "四" to "4", "五" to "5", "六" to "6", "七" to "7", "八" to "8", "九" to "9", "十" to "10")
            return map[kanjiMatch.groupValues[1]] ?: kanjiMatch.groupValues[1]
        }

        // 9. タイトル先頭の番号 (例: "1.旅の仲間 上1" -> 1)
        val prefixNum = Regex("^(\\d+)\\.").find(title)
        if (prefixNum != null) return prefixNum.groupValues[1]

        // 10. シリーズ名末尾の数字 (例: "指輪物語1" -> 1)
        if (seriesTitle.isNotEmpty()) {
            val seriesNum = Regex("(\\d+)$").find(seriesTitle)
            if (seriesNum != null) return seriesNum.groupValues[1].toInt().toString()
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
