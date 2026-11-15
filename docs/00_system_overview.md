# システム全体概要仕様書 (System Overview)

## 1. システム目的
ブックオフ（BO）等の古本・コミック仕入れ現場および仕入れ前のリサーチにおいて、
棚の背表紙撮影やキーワード検索から「店頭にある巻」「自宅在庫（スプレッドシート）の巻」「全巻数」「抜け巻」「相場（メルカリ/Amazon）」を瞬時に判定・可視化し、仕入れ判断を劇的に効率化する総合支援システム。

## 2. 設計方針: プラグイン型・疎結合モジュール設計
後からの機能追加（リサーチ先販路の追加、新AIモデルへの切り替え、データベースの変更等）が頻発することを前提とし、各機能は以下のルールでモジュール化する。

1. **単一責任の原則**: 各モジュールは1つの関心事（データ取得、画像認識、突合判定、相場生成）のみを担当する。
2. **入出力インターフェースの固定（契約プロトコル）**: モジュール同士は直接依存せず、あらかじめ定義された純粋なJSONデータ構造のみをやり取りする。
3. **ホットスワップ（差し替え容易性）**: 内部実装を別のAPIやローカル処理に置き換えても、入力と出力が同じであれば他のモジュールやUIに影響を与えない。
4. **仕様書のモジュール別分割管理**: 機能ごとに独立した仕様書を用意し、機能変更時は該当する仕様書のみを更新する。

## 3. モジュール構成とデータフロー

```text
[カメラ撮影 / ギャラリー選択 / ユーザー入力]
          │
          ▼
┌─────────────────────────────────┐
│ Module 2: Vision/Shelf Analyzer │ ── (背表紙AI画像解析: Gemini 3.5 Flash-Lite等)
└─────────────────────────────────┘
          │ 撮影抽出リスト (ScannedSpine[])
          ▼
┌─────────────────────────────────┐
│ Module 3: Set Matcher Engine    │ ◄─── Module 1: Stock Provider (動的ヘッダー参照在庫)
└─────────────────────────────────┘
          │ 突合判定結果 (MatchedSeriesStatus[])
          ▼
┌─────────────────────────────────┐
│ Module 4: Market Researcher     │ ── (相場リンク・価格データ生成)
└─────────────────────────────────┘
          │ 表示カード情報 (CardDisplayItem[])
          ▼
┌─────────────────────────────────┐
│ UI Layer (Jetpack Compose)      │ ── (棚判定・在庫検索・Lens連携・設定)
└─────────────────────────────────┘
```

## 4. 仕様書一覧
- `00_system_overview.md`: 全体アーキテクチャ・共通データ構造（本書）
- `01_stock_provider.md`: 在庫データ連携モジュール仕様書（動的ヘッダー・数量コンディション）
- `02_shelf_analyzer.md`: 背表紙・画像解析モジュール仕様書（AIモデル手動更新・Vision解析）
- `03_set_matcher.md`: 巻数突合・セット判定モジュール仕様書（指輪物語/トワイライト等の複合巻数対応）
- `04_market_researcher.md`: 相場・外部リサーチモジュール仕様書

## 5. 共通データ型定義 (Kotlin定義)

### 5.1 書籍在庫アイテム (StockItem)
```kotlin
data class StockItem(
    val isbn: String,             // ISBNコード（ハイフンなし）
    val category: String,         // 分類(レーベル) (例: 文庫, コミック単行本)
    val seriesTitle: String,      // シリーズ名
    val title: String,            // 書名
    val subTitle: String,         // サブタイトル
    val publisher: String,        // 出版社
    val volume: String?,          // 巻数（"1", "上", "上1", "2-上", "完結" 等）
    val totalCount: Int,          // 総数
    val actualCount: Int,         // 実数
    val obiCount: Int,            // 帯あり冊数
    val firstEditionCount: Int,   // 初版冊数
    val newEditionCount: Int,     // 新装版冊数
    val damagedCount: Int,        // ダメージ品冊数（ヤケ/折れ/破れ/濡れ/汚）
    val unmarkedCount: Int        // 通常・無印冊数
) {
    val isZeroStock: Boolean get() = maxOf(totalCount, actualCount) <= 0
}
```

### 5.2 店頭検出書籍 (ScannedSpine)
```kotlin
data class ScannedSpine(
    val title: String,            // 認識された作品名
    val volume: String,           // 認識された巻数
    val author: String? = null,   // 著者名
    val format: String? = null    // 判型 ("コミックス" または "文庫")
)
```

### 5.3 セット突合結果 (MatchedSeriesStatus)
```kotlin
data class MatchedSeriesStatus(
    val title: String,                    // 作品名
    val format: String? = null,           // 判型
    val totalVolumes: Int,                // 全巻数
    val isCompletedSeries: boolean,       // 完結しているか
    val storeVolumes: List<String>,       // 店頭にある巻一覧
    val stockVolumes: List<String>,       // 自宅在庫にある巻一覧
    val stockVolumeCounts: Map<String, Int>, // 巻ごとの在庫冊数
    val stockVolumeDetails: Map<String, VolumeStockDetail>, // 巻ごとのコンディション詳細
    val mergedVolumes: List<String>,      // 店頭＋在庫を合わせた巻一覧
    val missingVolumes: List<String>,     // 不足している抜け巻一覧
    val canCompleteByStore: Boolean,      // 店頭だけで全巻コンプ可能か
    val canCompleteByMerged: Boolean,     // 在庫と合体して全巻コンプ可能か
    val note: String                      // 判定注記 (例: "全10巻完結")
)
```

### 5.4 相場情報 (DirectMarketPrice)
```kotlin
data class DirectMarketPrice(
    val title: String,
    val mercariSoldPrice: Int?,      // メルカリ直近売切相場 (推論値・円)
    val amazonLowestPrice: Int?,    // Amazon中古最安値 (推論値・円)
    val estimatedProfit: Int?,      // 見込み純利益 (円)
    val profitMarginPercent: Float?,// 粗利率 (%)
    val mercariDetailUrl: String,   // タップで開くメルカリ検索URL
    val amazonDetailUrl: String     // タップで開くAmazon検索URL
)
```
