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
 * Gemini Decision-1 (Decisions API) 評価モジュール
 * 
 * 超低遅延（約150〜250ms）かつ出力トークン0で、
 * ① 完結ステータス（5択）
 * ② 全巻数（1〜256巻）
 * ③ 店頭コンプ判定（Yes/No NOUL型）
 * ④ 刊行形態・判型（9択）
 * ⑤ 帯付き本の有無（Yes/No NOUL型）
 * を1リクエストで事前判定・評価します。
 */
data class DecisionResult(
    val completionStatus: String? = null,
    val totalVolumes: Int? = null,
    val isStoreComplete: Boolean? = null,
    val formatType: String? = null,
    val hasObiInPhoto: Boolean? = null,
    val confidenceScores: Map<String, Double> = emptyMap(),
    val rawResponse: String = ""
)

class DecisionAnalyzer(
    var apiKey: String = "",
    var modelName: String = "gemini-decision-1"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 1巻〜256巻の選択肢テーブルを生成
    private val totalVolumeOptions: List<String> = (1..256).map { "${it}巻" }

    // 完結ステータスの5択テーブル
    private val completionStatusOptions: List<String> = listOf(
        "完結済み（最終巻刊行済み）",
        "現在進行系（定期的に新刊が刊行されている）",
        "未完結で更新停止（作者逝去・無期限休載等により事実上の既刊全巻扱い。例: NANA、イタズラなKiss）",
        "未完結だが長期休載・刊行間隔が年単位（例: HUNTER×HUNTER、バガボンド、ガラスの仮面）",
        "変則連載・設定改変等で巻数把握が極めて特殊（例: ファイブスター物語）"
    )

    // 刊行形態（判型）の9択テーブル
    private val formatTypeOptions: List<String> = listOf(
        "文庫",
        "コミック単行本",
        "完全版＆愛蔵版",
        "大判コミック",
        "コンビニコミック",
        "新書",
        "小説単行本",
        "ムックサイズ",
        "その他特殊判型"
    )

    /**
     * 画像Bitmapを直接渡してDecision-1評価を実行する
     */
    suspend fun evaluateBitmap(bitmap: Bitmap): Result<DecisionResult> = withContext(Dispatchers.IO) {
        val resized = resizeBitmap(bitmap, 1280)
        val stream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        evaluateBase64(base64Image)
    }

    /**
     * Base64エンコード済み画像でDecision-1評価を実行する
     */
    suspend fun evaluateBase64(base64Image: String): Result<DecisionResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("APIキーが未設定のためDecision-1評価をスキップします"))
        }

        try {
            val cleanModel = modelName.removePrefix("models/")
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:evaluate?key=$apiKey"

            // リクエストJSON構築
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })

                put("questions", JSONArray().apply {
                    // ① 完結ステータス判定（CHOICE型: 5択）
                    put(JSONObject().apply {
                        put("id", "completion_status")
                        put("question", "この作品の刊行・連載ステータスはどれですか？")
                        put("type", "CHOICE")
                        put("options", JSONArray(completionStatusOptions))
                    })

                    // ② 全巻数テーブル判定（CHOICE型: 1〜256）
                    put(JSONObject().apply {
                        put("id", "total_volumes")
                        put("question", "この作品は全何巻（既刊何巻）ですか？")
                        put("type", "CHOICE")
                        put("options", JSONArray(totalVolumeOptions))
                    })

                    // ③ 店頭コンプ判定（Yes/No NOUL型）
                    put(JSONObject().apply {
                        put("id", "is_store_complete")
                        put("question", "写真に写っている巻数だけで最初から最終巻まで全巻揃っていますか？")
                        put("type", "NOUL")
                    })

                    // ④ 刊行形態・判型判定（CHOICE型: 9択）
                    put(JSONObject().apply {
                        put("id", "format_type")
                        put("question", "写真に写っている本の刊行形態・判型はどれですか？")
                        put("type", "CHOICE")
                        put("options", JSONArray(formatTypeOptions))
                    })

                    // ⑤ 帯付き本の有無（Yes/No NOUL型）
                    put(JSONObject().apply {
                        put("id", "has_obi_in_photo")
                        put("question", "写真の中に帯（オビ）が付いている本は写っていますか？")
                        put("type", "NOUL")
                    })
                })
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("DecisionAnalyzer", "Decisions API応答エラー (${response.code}): $bodyStr")
                    return@withContext Result.failure(Exception("Decision-1 APIエラー (${response.code}): $bodyStr"))
                }

                val decisionResult = parseDecisionResponse(bodyStr)
                Log.d("DecisionAnalyzer", "Decision-1 判定成功: 完結=${decisionResult.completionStatus}, 全巻=${decisionResult.totalVolumes}, 店頭揃い=${decisionResult.isStoreComplete}, 判型=${decisionResult.formatType}, 帯あり=${decisionResult.hasObiInPhoto}")
                Result.success(decisionResult)
            }
        } catch (e: Exception) {
            Log.e("DecisionAnalyzer", "Decision-1 呼び出し例外: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Decisions API レスポンス解析処理
     */
    private fun parseDecisionResponse(bodyStr: String): DecisionResult {
        var completionStatus: String? = null
        var totalVolumes: Int? = null
        var isStoreComplete: Boolean? = null
        var formatType: String? = null
        var hasObiInPhoto: Boolean? = null
        val scores = mutableMapOf<String, Double>()

        try {
            val root = JSONObject(bodyStr)

            // decisions, answers, または results 配列を柔軟に探索
            val items = root.optJSONArray("decisions")
                ?: root.optJSONArray("answers")
                ?: root.optJSONArray("results")

            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val qId = item.optString("id").ifEmpty { item.optString("questionId") }
                    val answer = item.optString("answer").ifEmpty {
                        item.optString("selectedOption").ifEmpty {
                            item.optJSONObject("decision")?.optString("choice") ?: ""
                        }
                    }
                    val score = item.optDouble("score", item.optDouble("confidence", 0.0))
                    if (qId.isNotEmpty()) {
                        scores[qId] = score
                    }

                    when (qId) {
                        "completion_status" -> {
                            completionStatus = answer.takeIf { it.isNotBlank() }
                        }
                        "total_volumes" -> {
                            val num = answer.replace("巻", "").trim().toIntOrNull()
                            if (num != null) {
                                totalVolumes = num
                            }
                        }
                        "is_store_complete" -> {
                            val lower = answer.lowercase()
                            isStoreComplete = when {
                                lower in listOf("yes", "true", "1", "はい") -> true
                                lower in listOf("no", "false", "0", "いいえ") -> false
                                else -> null
                            }
                        }
                        "format_type" -> {
                            formatType = answer.takeIf { it.isNotBlank() }
                        }
                        "has_obi_in_photo" -> {
                            val lower = answer.lowercase()
                            hasObiInPhoto = when {
                                lower in listOf("yes", "true", "1", "はい") -> true
                                lower in listOf("no", "false", "0", "いいえ") -> false
                                else -> null
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DecisionAnalyzer", "レスポンスパース警告: ${e.message}")
        }

        return DecisionResult(
            completionStatus = completionStatus,
            totalVolumes = totalVolumes,
            isStoreComplete = isStoreComplete,
            formatType = formatType,
            hasObiInPhoto = hasObiInPhoto,
            confidenceScores = scores,
            rawResponse = bodyStr
        )
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
