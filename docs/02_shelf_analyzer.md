# モジュール2: 背表紙・画像解析仕様書 (Shelf Analyzer)

## 1. 概要
スマホのカメラ撮影画像や写真ファイルを入力とし、マルチモーダルAI（Gemini API等）を用いて
並んでいる背表紙の「作品タイトル」「巻数」「著者名」を解析・抽出し、構造化データとして返却するモジュール。

## 2. 責務 (Responsibilities)
- 画像の受け取り（カメラ撮影のBlob、ファイルアップロードのBase64）
- 通信軽量化のための適切なリサイズ・圧縮（最大長1600px等）
- Vision AIプロンプトの実行（背表紙認識に最適化した日本語抽出プロンプト）
- 認識結果のJSONパースとフォーマット正規化（漢数字「一・二」や「上・下」の巻数扱い）
- エラーハンドリング（不鮮明な画像、文字認識不可時のフォールバック）

## 3. インターフェース定義 (API)

```typescript
export interface RecognizedSpine {
  title: string;              // 作品名・書名（例: "不毛地帯", "天は赤い河のほとり"）
  volume: number | string;    // 巻数（例: 1, 2, "上", "下", "IV"）
  author?: string;            // 著者名（例: "山崎豊子", "篠原千絵"）
  rawSpineText?: string;      // 背表紙から読めた文字（検証用）
  confidence?: number;        // 信頼度
}

export interface VisionAnalysisResult {
  success: boolean;
  books: RecognizedSpine[];
  processingTimeMs: number;
  errorMessage?: string;
}

export interface ShelfAnalyzer {
  /** 画像（BlobまたはBase64データURL）を解析する */
  analyzeImage(imageData: Blob | string): Promise<VisionAnalysisResult>;

  /** 使用するAIバックエンド/モデルの切り替え */
  setModel(modelName: string): void;
}
```

## 4. プロンプト仕様（抜粋）
```text
あなたは古本・ブックオフのプロの仕入れリサーチャーです。
画像に写っている本（背表紙）を、左から順に特定してください。
各本について、以下をJSON配列で出力してください:
- title: 作品タイトル（シリーズ全体の名前。巻数やサブタイトルは除く）
- volume: 巻数（数字、または"上"、"下"、ローマ数字など）
- author: 著者名（判別できる場合）
```

## 5. 差し替え・拡張ポイント
- **モデルの切り替え**: Gemini 2.0 Flash、Gemini 1.5 Pro、ローカルLLM（LM Studio / Ollama等）への切り替えが可能。
- **端末内OCRとのハイブリッド**: OCRライブラリ（Tesseract.js等）で一次抽出したあとLLMで整形するパイプラインへの差し替えが可能。
