# モジュール2: 背表紙・画像解析仕様書 (Shelf Analyzer)

## 1. 概要
スマホのカメラ撮影画像や写真ファイルを入力とし、マルチモーダルAI（Google Gemini API）を用いて
並んでいる背表紙の「作品タイトル」「巻数」「判型（コミックス vs 文庫）」を解析・抽出し、構造化データとして返却するモジュール。

## 2. 責務 (Responsibilities)
- **画像の最適リサイズと圧縮**:
  - Xperia 10 II の端末負荷と送信レイテンシーを最小化するため、長辺2048px/JPEG 85%に最適リサイズ。
- **マルチAIモデル対応と動的切り替え**:
  - **`gemini-3.5-flash-lite`（最速・標準推奨）**: 深読み（Thinking）を排し、見たままの文字を素早く返す最速モデル。
  - **`gemini-3.8-flash`**: 安定した文字認識と相場推論を行う後継標準モデル。
  - **`gemini-4-hydrogen`**: エージェント特化の最新高速モデル。
  - 11/19にサービス終了する `gemini-3.6-flash` を完全排除。
- **🔄 有効なGeminiモデル一覧の手動更新**:
  - 起動時の無駄な通信を排除し、ユーザーが手動更新を押した時のみ `GET /v1beta/models` を呼び出す。
  - **厳格な除外フィルター**: 音声合成（`tts`）、文字起こし（`transcribe`）、画像生成（`image/banana`）、ロボティクス等の非対象モデルを完全に除外。
- **アンチハルシネーション厳格プロンプト**:
  - 勝手な連番推測・存在しない巻の捏造補完を厳格に禁止。
  - 同一巻・重複巻を並んでいる数だけ忠実に出力。
  - 著者別作品（例: 『獣の奏者』と『鹿の王』等）の混同を防止。

## 3. インターフェース定義 (Kotlin)

```kotlin
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
    /** ビットマップ画像から背表紙を認識 */
    suspend fun analyzeShelfBitmap(bitmap: Bitmap): Result<List<ScannedSpine>>

    /** Google APIから有効なVision対応Geminiモデルのみを手動取得 */
    suspend fun fetchAvailableVisionModels(key: String): Result<List<Pair<String, String>>>
}
```
