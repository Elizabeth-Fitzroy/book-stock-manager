# モジュール1: 在庫データ連携仕様書 (Stock Provider)

## 1. 概要
Googleスプレッドシート（Web公開CSV: `Stock_Summary` シート）から在庫データを非同期で取得し、
**動的ヘッダー参照（INDEX + MATCH方式）**によって列順・列追加に左右されない堅牢なパースと高速検索を提供するモジュール。

## 2. 責務 (Responsibilities)
- **INDEX + MATCH 動的ヘッダー参照**:
  - 1行目のヘッダー文字列（`ISBN`, `分類`, `シリーズ名`, `書名`, `サブタイトル`, `出版社`, `総数`, `実数`, `帯`, `初版`, `新`, `ヤケ`, `折れ`, `破れ`, `濡れ`, `汚` 等）から動的に列インデックスを特定し、スプレッドシートのレイアウト変更に完全耐性を持つ。
- **コンディション別数量（Int）の厳密管理**:
  - 各コンディション（帯、初版、新装版、ダメージ5種）をフラグではなく**実際の冊数（Int）**としてパース。
  - 特殊コンディションを除いた残数を**通常・無印冊数**として自動算出。
- **高度な巻数・編名の正規化抽出 (`extractVolume`)**:
  - 『指輪物語』等の複合枝番巻: `上1`, `上2`, `下1`, `下2`
  - 『トワイライト』等のローマ数字上下巻: `2-上`, `2-下`, `3-上`, `3-下`, `4-最終`
  - 『獣の奏者』等の編名: `闘蛇` -> 1, `王獣` -> 2, `探求` -> 3, `完結` -> 4
  - 上記に該当しない場合のシリーズ名末尾通し番号フォールバック（例: `指輪物語1` -> 1）
- **高速在庫検索**:
  - 単一キーワードのスペース区切りAND検索
  - 複数行ペーストによる行単位まとめ検索
  - 全ISBN（10桁/13桁）自動抽出一括検索
  - ローマ数字（Ⅰ〜Ⅹ）の自動アラビア数字正規化
  - 判型フィルター（すべて / 文庫 / 単行本 / コミックス）
- **🚨 優先仕入れ（0冊在庫）ハイライト**:
  - `totalCount <= 0 && actualCount <= 0` の書籍を未所持（要仕入れ）として特定。

## 3. インターフェース定義 (Kotlin)

```kotlin
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
    val obiCount: Int,            // 帯あり冊数
    val firstEditionCount: Int,   // 初版冊数
    val newEditionCount: Int,     // 新装版冊数
    val damagedCount: Int,        // ダメージ品冊数 (ヤケ+折れ+破れ+濡れ+汚)
    val unmarkedCount: Int        // 通常・無印冊数
) {
    val hasObi: Boolean get() = obiCount > 0
    val isFirstEdition: Boolean get() = firstEditionCount > 0
    val isNewEdition: Boolean get() = newEditionCount > 0
    val isDamaged: Boolean get() = damagedCount > 0
    val isUnmarked: Boolean get() = unmarkedCount > 0
    val effectiveCount: Int get() = maxOf(totalCount, actualCount)
    val isZeroStock: Boolean get() = effectiveCount <= 0
}

data class VolumeStockDetail(
    val count: Int,
    val firstEditionCount: Int = 0,
    val obiCount: Int = 0,
    val newEditionCount: Int = 0,
    val damagedCount: Int = 0,
    val unmarkedCount: Int = 0
) {
    fun formatSummary(): String // 例: "初版1/帯4/傷み1/通常2"
}

class StockProvider {
    suspend fun syncFromSheet(): Result<Int>
    fun searchByKeyword(query: String, formatFilter: String = "すべて"): List<StockItem>
    fun getOwnedVolumeDetailsByTitle(titleOrSeries: String, targetFormat: String? = null): Map<String, VolumeStockDetail>
    fun getOwnedVolumeCountsByTitle(titleOrSeries: String, targetFormat: String? = null): Map<String, Int>
    fun getTotalCount(): Int
}
```
