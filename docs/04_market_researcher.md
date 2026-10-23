# モジュール4: 相場・外部リサーチ仕様書 (Market Researcher)

## 1. 概要
作品名や巻数情報から、主要販路（メルカリ、Amazon、駿河屋、ヤフオク等）の
「セット本・売り切れ相場」「出品カタログ」にピンポイントでジャンプできるURLを生成し、
将来的な相場価格スクレイピングや自動利益計算への拡張を担うモジュール。

## 2. 責務 (Responsibilities)
- 各販路のURLパラメータ仕様に基づいた、検索URLの動的生成
  - メルカリ: 売り切れフィルタ、価格の高い順/新しい順、全巻セットキーワード付与
  - Amazon: 「全巻セット」「コミックセット」カテゴリ絞り込みURL
  - 駿河屋: 完結セット買い取り・販売価格ページ直通URL
  - ヤフオク: 落札相場（終了したオークション）絞り込みURL
- 将来的な相場API/価格スクレイピングの受け口（インターフェース定義）
- 粗利計算シミュレータ（想定売価 - 仕入原価 - 送料 - プラットフォーム手数料 10%）

## 3. インターフェース定義 (API)

```typescript
export interface MarketLinkInfo {
  platform: 'mercari' | 'amazon' | 'surugaya' | 'yahoo_auction';
  label: string;             // ボタン表示名 (例: "メルカリ相場(売切)")
  url: string;               // 直通リンクURL
  icon: string;              // アイコン識別子
  badgeColor: string;        // UIバッジ色
}

export interface ProfitEstimate {
  expectedSalePrice: number; // 想定売価
  purchaseCost: number;      // 仕入原価（例: 110円×冊数）
  shippingCost: number;      // 送料（ゆうパケット/宅急便60〜80サイズ等）
  platformFee: number;       // 販売手数料 (メルカリ10%等)
  netProfit: number;         // 利益額
  profitMargin: number;      // 利益率 (%)
}

export interface MarketResearcher {
  /** タイトルおよび巻数から、全販路のリサーチリンク一覧を生成 */
  generateMarketLinks(title: string, isCompleteSet?: boolean): MarketLinkInfo[];

  /** メルカリ相場直通URLを生成 */
  getMercariSearchUrl(title: string, options?: { onlySoldOut?: boolean; setOnly?: boolean }): string;

  /** AmazonカタログURLを生成 */
  getAmazonSearchUrl(title: string, isSet?: boolean): string;

  /** 簡易利益シミュレーションを計算 */
  calculateProfit(
    salePrice: number,
    bookCount: number,
    costPerBook?: number
  ): ProfitEstimate;
}
```

## 4. URL生成ルール仕様
- **メルカリ (Mercari)**:
  - 検索語: `{title} 全巻` または `{title} セット`
  - パラメータ: `status=sold_out`（売り切れのみに絞り、実際に売れた相場を見る）
  - ソート: `sort=created_time&order=desc`（直近の成約相場）
- **Amazon**:
  - 検索語: `{title} コミック 全巻セット`
  - ノード絞り込み: `i=stripbooks`（本カテゴリ）

## 5. 差し替え・拡張ポイント
- **相場スクレイピング・API連携**: Keepa APIやMercari非公式スクレイパー等を差し込んで、画面上に「直近3ヶ月の平均落札相場: 〇〇円」と直接自動表示させる拡張が可能。
- **送料・手数料テーブルの更新**: メルカリ便や宅急便の送料改定時も、このモジュールの設定値変更のみで対応可能。
