# モジュール3: 巻数突合・セット判定仕様書 (Set Matcher)

## 1. 概要
店頭で認識された巻一覧（`ShelfAnalyzer`の結果）と、自宅にある在庫の巻一覧（`StockProvider`の結果）を受け取り、
「店頭だけで全巻揃うか」「合体して揃うか」「全何巻中、残りの抜け巻は何巻か」を純粋な計算ロジックで判定するモジュール。

## 2. 責務 (Responsibilities)
- 作品名ごとの店頭巻リストのグルーピング
- 作品の「全巻数（完結巻数）」の推論・データベース照合
- 店頭所持巻 ＋ 自宅在庫巻の集合演算（Union、差集合）
- セット完成判定フラグの算出:
  - `canCompleteByStore`: 店頭の棚にある巻だけで1巻〜完結巻まで揃うか
  - `canCompleteByMerged`: 店頭の巻と在庫の巻を合わせれば全巻揃うか
  - `remainingMissing`: あと何巻集めれば完結するか

## 3. インターフェース定義 (API)

```typescript
export interface SeriesCompletionStatus {
  title: string;
  totalVolumes: number;               // 全巻数 (例: 16)
  isCompletedSeries: boolean;         // 完結しているか (true: 完結, false: 連載中/未確定)
  storeVolumes: (number | string)[];  // 店頭にある巻 [1, 2, 4]
  stockVolumes: (number | string)[];  // 自宅在庫の巻 [3]
  mergedVolumes: (number | string)[]; // 合わせると [1, 2, 3, 4]
  missingVolumes: (number | string)[];// 残りの抜け巻 [5, 6, ..., 16]
  
  canCompleteByStore: boolean;        // 店頭のみでコンプ (false)
  canCompleteByMerged: boolean;       // 在庫合体でコンプ (false)
  isWorthBuying: boolean;             // 仕入れ推奨度（例: 抜けが1〜2冊のみ、または店頭完結）
}

export interface SetMatcher {
  /** 店頭検出巻リストと在庫プロバイダをもとに、全シリーズの判定を行う */
  matchSets(
    scannedBooks: RecognizedSpine[],
    stockProvider: StockProvider
  ): SeriesCompletionStatus[];

  /** 単一の作品名・巻リストに対する判定 */
  evaluateSingleSeries(
    title: string,
    storeVols: (number | string)[],
    stockVols: (number | string)[],
    knownTotalVolumes?: number
  ): SeriesCompletionStatus;
}
```

## 4. 判定ロジック詳細
1. **正規化**: 巻数表現（"1", 1, "01", "一"）を数値または標準識別子に統一する。
2. **全巻数の特定アルゴリズム**:
   - 優先度1: 作品マスターDB / キャッシュ済みコミック完結データ
   - 優先度2: AI推論（Geminiの一般知識: 「天は赤い河のほとり 文庫版は全何巻？」）
   - 優先度3: 店頭・在庫にある最大巻数（フォールバック）
3. **仕入れ推奨度 (isWorthBuying) の判定ルール**:
   - 判定A: 店頭だけでコンプ（即買い候補）
   - 判定B: 在庫合体でコンプ（在庫が捌けるので最優先）
   - 判定C: 残り抜け巻が1〜2冊（補充しやすいので高優先度）

## 5. 差し替え・拡張ポイント
- **全巻数データベースの外部連携**: メディア芸術データベース（文化庁）APIやオープン書籍DBとの直接連携を追加可能。
