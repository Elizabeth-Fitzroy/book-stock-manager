# 古本仕入れ総合支援システム (Book Stock Manager)

古本・コミック仕入れ現場（ブックオフ等）および仕入れ前リサーチにおいて、
棚の背表紙撮影やキーワード検索から「店頭にある巻」「自宅在庫（スプレッドシート）の巻」「全巻数」「抜け巻」「相場（メルカリ/Amazon）」を瞬時に判定・可視化する総合支援ツールです。

## 📁 モジュール別仕様書 (Documentation)

本システムは将来の機能追加・拡張を容易にするため、プラグイン型・疎結合モジュール設計を採用しています。

- [00. システム全体概要仕様書 (System Overview)](./docs/00_system_overview.md)
- [01. 在庫データ連携仕様書 (Stock Provider)](./docs/01_stock_provider.md)
- [02. 背表紙・画像解析仕様書 (Shelf Analyzer)](./docs/02_shelf_analyzer.md)
- [03. 巻数突合・セット判定仕様書 (Set Matcher)](./docs/03_set_matcher.md)
- [04. 相場・外部リサーチ仕様書 (Market Researcher)](./docs/04_market_researcher.md)

## 🧩 モジュール構成

```text
[カメラ撮影 / ユーザー入力]
          │
          ▼
┌─────────────────────────────────┐
│ Module 2: Vision/Shelf Analyzer │ ── (背表紙AI画像解析)
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
│ UI Layer (Web Dashboard)        │ ── (スマホ/PC対応画面)
└─────────────────────────────────┘
```
