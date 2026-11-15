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
 * 判型識別（コミックス vs 文庫）＆混雑回避ルーティング対応
 */

data class ScannedSpine(
    val title: String,
    val volume: String,
    val author: String? = null,
    val format: String? = null // "コミックス" または "文庫"
)

class ShelfAnalyzer(
    var apiKey: String = "",
    var modelName: String = "gemini-3.5-flash-lite"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeShelfBitmap(bitmap: Bitmap): Result<List<ScannedSpine>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val msg = "Gemini APIキーが設定されていません。右上の設定から入力してください。"
            Log.e("BookStock", msg)
            return@withContext Result.failure(Exception(msg))
        }

        try {
            Log.d("BookStock", "【ShelfAnalyzer】画像リサイズ開始 (元: ${bitmap.width}x${bitmap.height})")
            val resized = resizeBitmap(bitmap, 2048)
            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val imageBytes = stream.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            Log.d("BookStock", "【ShelfAnalyzer】高精細Base64エンコード完了(長辺2048px): ${imageBytes.size / 1024} KB")

            val prompt = """あなたは古本・コミック仕入れの超一流プロフェッショナルです。
提供された画像に写っている本の「背表紙」（または表紙）を、左から順に1冊ずつ厳密に確認してください。
写っているすべての本について、作品タイトル・巻数（またはサブタイトル/編名）・判型（コミックスか文庫か）を特定し、以下の形式のJSON配列のみを出力してください。

【厳格な遵守ルール】
1. 勝手な連番推測・補完の絶対禁止:
   - 本が複数冊並んでいるからといって、印刷されていない巻数を「1巻、2巻、3巻…」と連番で捏造・推測してはいけません。
   - 必ず背表紙に印刷されている文字・記号（数字、ローマ数字、漢数字、または「王獣編」「完結編」等の編名）だけを忠実に読み取ってください。
2. 同一巻・重複の忠実な出力:
   - 同じ巻や同じ本が複数冊並んでいる場合（例: 王獣編が3冊、完結編が2冊並んでいる等）、必ず見えた通りに重複して1冊ずつ（計5要素）出力してください。
3. 著者別作品との混同禁止:
   - 著者が同じ別の代表作（例: 『獣の奏者』と『鹿の王』など）と混同しないよう、背表紙のタイトル文字を1文字ずつ正確に認識してください。
4. 判型の正確な判定:
   - 背表紙のレーベルロゴ（「講談社文庫」「集英社文庫」「新潮文庫」等の文庫マーク vs コミックスのジャンプ・マガジンマーク）や縦横比から、"format" には「文庫」または「コミックス」（単行本）を正確に指定してください。

Markdownコードブロック等は含めず、純粋なJSON文字列だけを返してください。

[
  {
    "title": "作品タイトル（シリーズ名。巻数やサブタイトルは除く。例: 獣の奏者）",
    "volume": "背表紙に書かれた巻数または編名（例: '2', '王獣編', '完結編', 'Ⅱ', '外伝'など、見えた通りに出力）",
    "author": "著者名（例: 上橋菜穂子）",
    "format": "文庫 または コミックス"
  }
]"""

            val candidateModels = linkedSetOf(
                modelName.removePrefix("models/").trim(),
                "gemini-3.6-flash",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite"
            ).filter { it.isNotBlank() }

            val cleanKey = apiKey.trim()
            var lastErrorMsg = ""

            for (targetModel in candidateModels) {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$cleanKey"
                val startTime = System.currentTimeMillis()
                Log.d("BookStock", "【ShelfAnalyzer】高速リクエスト送信 ➔ $targetModel")

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

                try {
                    client.newCall(request).execute().use { response ->
                        val elapsed = System.currentTimeMillis() - startTime
                        val bodyStr = response.body?.string() ?: ""
                        Log.d("BookStock", "【ShelfAnalyzer】$targetModel 応答: code=${response.code} (${elapsed}ms)")

                        if (response.isSuccessful) {
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
                                val format = item.optString("format").takeIf { it.isNotBlank() }
                                if (title.isNotEmpty()) {
                                    spines.add(ScannedSpine(title = title, volume = vol, author = author, format = format))
                                }
                            }
                            Log.d("BookStock", "【ShelfAnalyzer】🎉 認識成功！冊数=${spines.size} (所要時間: ${elapsed}ms / モデル: $targetModel)")
                            return@withContext Result.success(spines)
                        } else {
                            lastErrorMsg = "$targetModel エラー (${response.code})"
                            Log.w("BookStock", "【ShelfAnalyzer】$lastErrorMsg: $bodyStr")
                        }
                    }
                } catch (netEx: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    lastErrorMsg = "$targetModel 通信例外: ${netEx.message}"
                    Log.w("BookStock", "【ShelfAnalyzer】$lastErrorMsg (${elapsed}ms)")
                }
            }

            Result.failure(Exception("全候補モデルでの解析に失敗しました: $lastErrorMsg"))
        } catch (e: Exception) {
            Log.e("BookStock", "【ShelfAnalyzer】例外: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Google AI Studio から現在利用可能なモデル一覧を手動取得し、
     * 画像認識（Vision）およびテキスト生成に対応した Gemini シリーズのみを安全に抽出する
     */
    suspend fun fetchAvailableVisionModels(key: String): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext Result.failure(Exception("APIキーを入力してください"))
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("モデル取得エラー: ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val root = JSONObject(bodyStr)
                val modelsArray = root.optJSONArray("models") ?: JSONArray()
                val list = mutableListOf<Pair<String, String>>()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val rawName = m.optString("name", "") // e.g. "models/gemini-2.5-flash"
                    val modelId = rawName.removePrefix("models/")
                    val displayName = m.optString("displayName", modelId)
                    val methods = m.optJSONArray("supportedGenerationMethods")

                    // フィルタリング: geminiシリーズ かつ generateContent に対応（画像・対話対応）
                    val isGemini = modelId.startsWith("gemini-")
                    // 音声合成(TTS)、文字起こし(transcribe)、画像生成(image/banana)、ロボット、廃止予定(3.6)等を除外
                    val isExcluded = modelId.contains("tts") ||
                            modelId.contains("transcribe") ||
                            modelId.contains("image") ||
                            modelId.contains("banana") ||
                            modelId.contains("robotics") ||
                            modelId.contains("computer-use") ||
                            modelId.contains("customtools") ||
                            modelId.contains("embedding") ||
                            modelId.contains("imagen") ||
                            modelId.contains("aqa") ||
                            modelId.contains("3.6-flash")
                    var canGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") {
                                canGenerate = true
                                break
                            }
                        }
                    }

                    if (isGemini && canGenerate && !isExcluded) {
                        list.add(Pair(modelId, displayName))
                    }
                }

                // 推奨モデルが上に来るようにソート
                val sorted = list.sortedBy { (id, _) ->
                    when {
                        id.contains("3.5-flash-lite") -> 0
                        id.contains("3.8-flash") -> 1
                        id.contains("4-hydrogen") -> 2
                        id.contains("flash") -> 3
                        else -> 4
                    }
                }
                Result.success(sorted)
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
