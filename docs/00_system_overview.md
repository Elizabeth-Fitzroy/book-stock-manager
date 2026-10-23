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
[カメラ撮影 / ユーザー入力]
          │
          ▼
┌─────────────────────────────────┐
│ Module 2: Vision/Shelf Analyzer │ ── (画像解析)
└─────────────────────────────────┘
          │ 撮影抽出リスト (ScannedBook[])
          ▼
┌─────────────────────────────────┐
│ Module 3: Set Matcher Engine    │ ◄─── Module 1: Stock Provider (在庫リスト)
└─────────────────────────────────┘
          │ 突合判定結果 (MatchResult[])
          ▼
┌─────────────────────────────────┐
│ Module 4: Market Researcher     │ ── (相場リンク・価格データ生成)
└─────────────────────────────────┘
          │ リサーチ拡張結果 (EnrichedResult[])
          ▼
┌─────────────────────────────────┐
│ UI Layer (Web Dashboard)        │ ── (画面表示・ワンクリック遷移)
└─────────────────────────────────┘
```

## 4. 仕様書一覧
- `00_system_overview.md`: 全体アーキテクチャ・共通データ構造（本書）
- `01_stock_provider.md`: 在庫データ連携モジュール仕様書
- `02_shelf_analyzer.md`: 背表紙・画像解析モジュール仕様書
- `03_set_matcher.md`: 巻数突合・セット判定モジュール仕様書
- `04_market_researcher.md`: 相場・外部リサーチモジュール仕様書

## 5. 共通データ型定義 (TypeScript風表記)

### 5.1 書籍基本情報 (BookItem)
```typescript
interface BookItem {
  isbn: string;           // ISBNコード（ハイフンなし）
  title: string;          // 書籍名 / 作品名
  seriesTitle?: string;   // シリーズ名
  volume: number | string;// 巻数（1, 2, "上", "下" 等）
  author?: string;        // 著者名
  publisher?: string;     // 出版社 / レーベル
}
```

### 5.2 店頭検出書籍 (ScannedBook)
```typescript
interface ScannedBook {
  title: string;          // 認識されたタイトル
  volume: number | string;// 認識された巻数
  rawText?: string;       // 背表紙の生OCR文字列
  confidence?: number;    // 認識信頼度 (0.0〜1.0)
}
```

### 5.3 セット突合結果 (MatchResult)
```typescript
interface MatchResult {
  title: string;                    // タイトル
  totalVolumes: number;             // 全巻数（完結巻数、未完の場合は現在判明している最新巻）
  isCompletedSeries: boolean;       // 完結しているか
  storeVolumes: (number | string)[];// 店頭にある巻一覧
  stockVolumes: (number | string)[];// 自宅在庫にある巻一覧
  mergedVolumes: (number | string)[];// 店頭＋在庫を合わせた巻一覧
  missingVolumes: (number | string)[];// コンプリートに不足している抜け巻一覧
  canCompleteByStore: boolean;      // 店頭だけで全巻コンプ可能か
  canCompleteByMerged: boolean;     // 在庫と合体して全巻コンプ可能か
}
```

### 5.4 相場情報 (MarketInfo)
```typescript
interface MarketInfo {
  mercariSearchUrl: string;         // メルカリ相場直通URL（全巻セット/売り切れ/価格順）
  amazonSearchUrl: string;          // Amazonカタログ検索直通URL
  surugayaSearchUrl: string;        // 駿河屋検索直通URL
  estimatedPriceRange?: {           // 将来拡張用: 自動取得相場
    min: number;
    max: number;
    average: number;
  };
}
```
