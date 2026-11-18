package com.example.bookstock.modules.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * モジュール2: 背表紙・画像解析モジュール (Kotlin版)
 * 仕様書: docs/02_shelf_analyzer.md
 */

data class ScannedSpine(
    val title: String,
    val volume: String,
    val author: String? = null,
    val hasObi: Boolean = false // 各巻の帯（オビ）の有無
)

/**
 * 背表紙解析結果とDecision-1事前判定結果をまとめたクラス
 */
data class ShelfAnalysisOutput(
    val spines: List<ScannedSpine>,
    val decision: DecisionResult? = null
)

class ShelfAnalyzer(
    var apiKey: String = "",
    var modelName: String = "gemini-3.5-flash-lite",
    var enableDecisionPrecheck: Boolean = true
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    val decisionAnalyzer: DecisionAnalyzer by lazy {
        DecisionAnalyzer(apiKey = apiKey)
    }

    /**
     * 背表紙の解析および事前判定を実行する
     */
    suspend fun analyzeShelf(bitmap: Bitmap): Result<ShelfAnalysisOutput> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini APIキーが設定されていません。右上の設定から入力してください。"))
        }

        try {
            // 画像をBase64にエンコード（長辺1280pxにリサイズ）
            val resized = resizeBitmap(bitmap, 1280)
            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            var decisionResult: DecisionResult? = null

            // 1. Gemini Decision-1 による事前ガードレール判定（約150〜250ms）
            if (enableDecisionPrecheck) {
                decisionAnalyzer.apiKey = apiKey
                val decisionCall = decisionAnalyzer.evaluateBase64(base64Image)
                if (decisionCall.isSuccess) {
                    decisionResult = decisionCall.getOrNull()
                    Log.d("ShelfAnalyzer", "Decision-1 事前確定データ取得成功: $decisionResult")
                } else {
                    Log.w("ShelfAnalyzer", "Decision-1 判定スキップ/失敗: ${decisionCall.exceptionOrNull()?.message}")
                }
            }

            // 2. プロンプト生成（Decision-1 の結果を確定事実として注入）
            val decisionContextBuilder = StringBuilder()
            if (decisionResult != null) {
                decisionContextBuilder.append("\n【事前確定判定データ（Gemini Decision-1により確定済み）】\n")
                decisionResult.completionStatus?.let {
                    decisionContextBuilder.append("・刊行ステータス: $it\n")
                }
                decisionResult.totalVolumes?.let {
                    decisionContextBuilder.append("・全巻数（既刊数）: ${it}巻\n")
                }
                decisionResult.formatType?.let {
                    decisionContextBuilder.append("・刊行形態・判型: $it\n")
                }
                decisionResult.hasObiInPhoto?.let {
                    decisionContextBuilder.append("・画像内の帯付き本の有無: ${if (it) "あり" else "なし"}\n")
                }
                decisionResult.isStoreComplete?.let {
                    decisionContextBuilder.append("・店頭全巻揃い判定: ${if (it) "はい（全巻揃っている）" else "いいえ（抜け巻あり）"}\n")
                }
                decisionContextBuilder.append("※上記の事前確定データを前提事実として扱い、判型の誤認や巻数の独自推論は行わず、背表紙の文字認識（作品タイトル・巻数・帯の有無）に集中してください。\n")
            }

            val prompt = """あなたはブックオフや古本屋の仕入れプロフェッショナルです。
提供された画像に写っている本の「背表紙」（または表紙）を、左から順に確認してください。$decisionContextBuilder
写っているすべての本について、作品タイトル、巻数、および「帯（オビ）が巻かれているか(hasObi)」を特定し、以下の形式のJSON配列のみを出力してください。
Markdownコードブロック等は含めず、純粋なJSON文字列だけを返してください。

[
  {
    "title": "作品タイトル（シリーズ名。巻数やサブタイトルは除く）",
    "volume": "巻数（半角数字、または'上','下','中','I','II'など）",
    "author": "著者名",
    "hasObi": true または false（背表紙の下部等に帯が巻かれていればtrue、なければfalse）
  }
]"""

            val cleanModel = modelName.removePrefix("models/")
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                })
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("APIエラー (${response.code}): $bodyStr"))
                }

                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates")
                val text = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text") ?: "[]"

                val cleanJson = text.replace("```json", "").replace("```", "").trim()
                val jsonArr = JSONArray(cleanJson)
                val spines = mutableListOf<ScannedSpine>()
                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.getJSONObject(i)
                    val title = item.optString("title").trim()
                    val vol = item.optString("volume").trim()
                    val author = item.optString("author").takeIf { it.isNotBlank() }
                    val hasObi = item.optBoolean("hasObi", false)
                    if (title.isNotEmpty()) {
                        spines.add(ScannedSpine(title = title, volume = vol, author = author, hasObi = hasObi))
                    }
                }
                Result.success(ShelfAnalysisOutput(spines = spines, decision = decisionResult))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 従来の互換メソッド（List<ScannedSpine> のみ返す）
     */
    suspend fun analyzeShelfBitmap(bitmap: Bitmap): Result<List<ScannedSpine>> {
        return analyzeShelf(bitmap).map { it.spines }
    }

    private fun resizeBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxDimension && height <= maxDimension) return source

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int
        if (width > height) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }
}
