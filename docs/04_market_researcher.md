# モジュール4: 相場・外部リサーチ仕様書 (Market Researcher) - 改定版

## 1. 概要
作品名や巻数情報から、主要販路（メルカリ、Amazon、駿河屋等）の
**「直近売り切れ相場・最安値」をアプリ内部で自動取得（フェッチ）し、画面上にダイレクト表示する** モジュール。
また、ユーザーが写真や出品状態をより精査したい場合のみ、ワンタップで公式アプリやブラウザを開く導線を提供する。

## 2. 責務 (Responsibilities)
1. **ダイレクト相場自動取得 (Direct Price Fetching)**:
   - **メルカリ**: 直近「売り切れ（SOLD OUT）」になったセット本商品の価格を取得し、平均成約相場・最高値・最安値を算出。
   - **Amazon**: 対象作品のセット本カタログにおける出品最安値（中古最安）を取得。
2. **自動粗利・利益率計算 (Auto Profit Engine)**:
   - 取得したメルカリ相場（またはAmazon最安値）をもとに、仕入原価（店頭購入冊数×110円など）、想定送料、販売手数料（10%）を自動差し引きし、**「見込み純利益額」と「利益率」を画面に即座に提示**する。
3. **詳細精査用ディープリンク生成 (Deep Link Generation)**:
   - 画面上のダイレクト数字を確認した上で「実際のコンディションや付属品を見たい」ときだけタップできる直通リンク（メルカリアプリ/Amazonアプリ起動リンク）の生成。

## 3. インターフェース定義 (Kotlin / TypeScript共通仕様)

```kotlin
data class MarketPriceSummary(
    val platform: String,          // "mercari", "amazon"
    val averagePrice: Int,         // 平均相場 (円)
    val minPrice: Int,             // 最安値 (円)
    val maxPrice: Int,             // 最高値 (円)
    val recentSoldPrices: List<Int>, // 直近成約価格リスト [3400, 3200, 3600]
    val lastUpdated: Long,         // 取得タイムスタンプ
    val detailUrl: String          // 精査用直通URL
)

data class DirectMarketResult(
    val title: String,
    val mercari: MarketPriceSummary?,
    val amazon: MarketPriceSummary?,
    val estimatedProfit: ProfitCalculationResult?
)

data class ProfitCalculationResult(
    val basePrice: Int,            // 基準とした相場価格 (円)
    val purchaseCost: Int,         // 仕入原価 (円)
    val shippingCost: Int,         // 想定送料 (円)
    val platformFee: Int,          // 販売手数料 (円)
    val netProfit: Int,            // 見込み純利益 (円)
    val profitMarginPercent: Float // 利益率 (%)
)

interface MarketResearcher {
    /** 作品名からダイレクト相場を非同期取得する */
    suspend fun fetchDirectMarketPrice(title: string, isCompleteSet: Boolean = true): DirectMarketResult

    /** 想定売価と冊数から利益を計算する */
    fun calculateProfit(
        salePrice: Int,
        storeBuyCount: Int,
        costPerBook: Int = 110,
        shippingCost: Int = 750
    ): ProfitCalculationResult
}
```

## 4. ダイレクト相場取得アルゴリズム
1. **メルカリ売切相場の取得**:
   - 検索クエリ: `{title} 全巻` または `{title} セット`
   - フィルタ: `status=sold_out`, `sort=created_time`, `order=desc`
   - 取得件数: 直近5件
   - 外れ値の除去: 単巻出品（数百円）やプレミアム限定版の混入を避けるため、一定の価格範囲（1,000円〜）でフィルタリングを行い、中央値・平均値を算出。
2. **Amazonカタログ相場の取得**:
   - 検索クエリ: `{title} 全巻セット`
   - 中古出品（Used）の最安値を抽出。

## 5. UIへのダイレクト表示仕様
カード内に以下のフォーマットで常時インライン表示する：
- 🛍️ **メルカリ売切相場**: **約 3,400 円** (直近: 3,200円〜3,600円)
- 📦 **Amazon出品最安**: **3,980 円**
- 💰 **見込み純利益**: **+1,810 円 (粗利率 47%)**
- ［メルカリで詳細を見る］［Amazonで詳細を見る］（※右下に控えめに配置）
