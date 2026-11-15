# モジュール3: 巻数突合・セット判定仕様書 (Set Matcher)

## 1. 概要
店頭で認識された巻一覧（`ShelfAnalyzer`の結果）と、自宅にある在庫の巻一覧（`StockProvider`の結果）を受け取り、
「店頭だけで全巻揃うか」「合体して揃うか」「全何巻中、残りの抜け巻は何巻か」を正確に判定・評価するモジュール。

## 2. 責務 (Responsibilities)
- **複雑な複合巻数の正規化 (`normalizeVolume`)**:
  - 枝番付き上下巻: `上1`, `上2`, `下1`, `下2` を単なる数字や「上」に丸めず、独立した識別子として保持。
  - ローマ数字＋上下巻: `Ⅱ上`, `Ⅱ下` を `2-上`, `2-下` に正規化。
  - ローマ数字・漢数字・特殊編名（闘蛇/王獣/探求/完結/追補編/最終章）の正規化。
- **全巻数（完結巻数）の特定**:
  - `knownSeries`（天は赤い河のほとり: 16, 指輪物語: 10, トワイライト: 10, 獣の奏者: 4 等）の優先参照。
  - 上下巻作品の2巻完結判定。
  - 未知作品における最大巻数からの推定。
- **店頭巻と自宅在庫巻の厳密な分離・合算**:
  - 店頭巻（画像から検出された巻）と自宅在庫巻を混同・二重加算せず、`mergedVolumes = (storeVols + stockVols).distinct()` によりコンプリート判定および不足巻（`missingVolumes`）を算出。

## 3. インターフェース定義 (Kotlin)

```kotlin
data class MatchedSeriesStatus(
    val title: String,
    val format: String? = null,
    val totalVolumes: Int,
    val isCompletedSeries: Boolean,
    val storeVolumes: List<String>,
    val stockVolumes: List<String>,
    val stockVolumeCounts: Map<String, Int> = emptyMap(),
    val stockVolumeDetails: Map<String, StockProvider.VolumeStockDetail> = emptyMap(),
    val mergedVolumes: List<String>,
    val missingVolumes: List<String>,
    val canCompleteByStore: Boolean,
    val canCompleteByMerged: Boolean,
    val note: String
)

class SetMatcher {
    fun match(scannedBooks: List<ScannedSpine>, stockProvider: StockProvider): List<MatchedSeriesStatus>
    companion object {
        fun normalizeVolume(vol: String): String
    }
}
```

## 5. 差し替え・拡張ポイント
- **全巻数データベースの外部連携**: メディア芸術データベース（文化庁）APIやオープン書籍DBとの直接連携を追加可能。
