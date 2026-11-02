package com.example.bookstock.modules.vision

import android.graphics.Bitmap
import android.util.Base64
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
    val author: String? = null
)

class ShelfAnalyzer(
    var apiKey: String = "",
    var modelName: String = "gemini-3.8-flash"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeShelfBitmap(bitmap: Bitmap): Result<List<ScannedSpine>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini APIキーが設定されていません。右上の設定から入力してください。"))
        }

        try {
            // 画像をBase64にエンコード（最大長1280px程度にリサイズして高速化）
            val resized = resizeBitmap(bitmap, 1280)
            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val prompt = """あなたはブックオフや古本屋の仕入れプロフェッショナルです。
提供された画像に写っている本の「背表紙」（または表紙）を、左から順に確認してください。
写っているすべての本について、作品タイトルと巻数を特定し、以下の形式のJSON配列のみを出力してください。
Markdownコードブロック等は含めず、純粋なJSON文字列だけを返してください。

[
  {
    "title": "作品タイトル（シリーズ名。巻数やサブタイトルは除く）",
    "volume": "巻数（半角数字、または'上','下','中','I','II'など）",
    "author": "著者名"
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
                    if (title.isNotEmpty()) {
                        spines.add(ScannedSpine(title = title, volume = vol, author = author))
                    }
                }
                Result.success(spines)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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
