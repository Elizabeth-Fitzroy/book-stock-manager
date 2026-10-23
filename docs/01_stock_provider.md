# モジュール1: 在庫データ連携仕様書 (Stock Provider)

## 1. 概要
Googleスプレッドシート（またはCSV/ローカルキャッシュ）から在庫データを読み込み、
システム共通の形式に正規化してメモリ上で保持・高速検索を提供するモジュール。

## 2. 責務 (Responsibilities)
- 指定されたスプレッドシートURL（またはCSVエクスポートURL）からの非同期データフェッチ
- CSV/表形式データのパースおよびクレンジング（ISBNの標準化、巻数やタイトルの抽出）
- ブラウザのローカルストレージ（IndexedDB/LocalStorage）によるオフラインキャッシュ
- クエリ（ISBN、タイトル、シリーズ名）に対するミリ秒単位の高速フィルタリング
- シリーズ名・作品名ごとの所持巻リストの集計

## 3. インターフェース定義 (API)

```typescript
export interface StockItem {
  isbn: string;              // ISBN (13桁/10桁、ハイフンなし)
  category: string;          // 分類(レーベル) (例: TL, コミック単行本, ラノベ)
  seriesTitle: string;       // シリーズ名 (例: ガンダムさん, がんばれ元気)
  title: string;             // 書名
  subTitle: string;          // サブタイトル
  publisher: string;         // 出版社
  totalCount: number;        // 総数
  inStockCount: number;      // In stock
  readyCount: number;        // Ready
  listOnCount: number;       // List on
  actualCount: number;       // 実数
  condition: {               // 状態フラグ
    yake: boolean;
    ore: boolean;
    yabure: boolean;
    nure: boolean;
  };
}

export interface StockProvider {
  /** データを初期化・同期する */
  init(sheetUrl?: string, forceRefresh?: boolean): Promise<void>;

  /** タイトルまたはキーワードで部分一致検索（ミリ秒応答） */
  searchByKeyword(query: string): StockItem[];

  /** ISBN完全一致で1冊取得 */
  findByIsbn(isbn: string): StockItem | null;

  /** シリーズ名またはタイトルから、現在持っている巻数リストを抽出 */
  getOwnedVolumesByTitle(title: string): (number | string)[];

  /** 全件数を取得 */
  getTotalCount(): number;

  /** 最終同期日時を取得 */
  getLastSyncTime(): Date | null;
}
```

## 4. 差し替え・拡張ポイント
- **スプレッドシートAPI連携**: 現在は公開CSVエンドポイントを利用するが、将来Google Sheets API（OAuth2/サービスアカウント）による直接書き込み・同期に差し替え可能。
- **SQLite / IndexedDB化**: 将来的に数万件にスケールした場合、ブラウザ内SQLite（WASM）やIndexedDBへ内部実装を切り替え可能。
