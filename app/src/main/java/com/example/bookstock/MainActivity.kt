package com.example.bookstock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.bookstock.modules.market.DirectMarketPrice
import com.example.bookstock.modules.market.MarketResearcher
import com.example.bookstock.modules.matcher.MatchedSeriesStatus
import com.example.bookstock.modules.matcher.SetMatcher
import com.example.bookstock.modules.stock.GoogleAuthHelper
import com.example.bookstock.modules.stock.TokenResult
import com.example.bookstock.modules.stock.StockItem
import com.example.bookstock.modules.stock.StockProvider
import com.example.bookstock.modules.vision.ShelfAnalyzer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val stockProvider = StockProvider()
    private val shelfAnalyzer = ShelfAnalyzer()
    private val setMatcher = SetMatcher()
    private val marketResearcher = MarketResearcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        shelfAnalyzer.apiKey = prefs.getString("gemini_api_key", "") ?: ""
        shelfAnalyzer.modelName = prefs.getString("gemini_model_name", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        shelfAnalyzer.enableDecisionPrecheck = prefs.getBoolean("use_gemini_decision", true)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    MainScreen(
                        stockProvider = stockProvider,
                        shelfAnalyzer = shelfAnalyzer,
                        setMatcher = setMatcher,
                        marketResearcher = marketResearcher,
                        onSaveConfig = { key, model, useDecision ->
                            shelfAnalyzer.apiKey = key
                            shelfAnalyzer.modelName = model
                            shelfAnalyzer.enableDecisionPrecheck = useDecision
                            prefs.edit()
                                .putString("gemini_api_key", key)
                                .putString("gemini_model_name", model)
                                .putBoolean("use_gemini_decision", useDecision)
                                .apply()
                        }
                    )
                }
            }
        }
    }
}

data class CardDisplayItem(
    val match: MatchedSeriesStatus,
    val market: DirectMarketPrice?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    stockProvider: StockProvider,
    shelfAnalyzer: ShelfAnalyzer,
    setMatcher: SetMatcher,
    marketResearcher: MarketResearcher,
    onSaveConfig: (String, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleAuthHelper = remember { GoogleAuthHelper(context) }
    var currentAccount by remember { mutableStateOf(googleAuthHelper.getSavedAccountName()) }

    var selectedTab by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var displayCards by remember { mutableStateOf<List<CardDisplayItem>>(emptyList()) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StockItem>>(emptyList()) }
    var stockCount by remember { mutableStateOf(0) }

    // Google 権限同意画面（初回認証）ランチャー
    var pendingConsentIntent by remember { mutableStateOf<Intent?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("BookStock", "ユーザーによるSheetsアクセス同意を確認しました")
            Toast.makeText(context, "Google権限が付与されました。同期中...", Toast.LENGTH_SHORT).show()
            // 同意完了後に同期を再実行
            scope.launch {
                isLoading = true
                loadingMessage = "Google Sheets API (v4) からデータ取得中..."
                val tokenRes = googleAuthHelper.fetchToken(currentAccount)
                if (tokenRes is TokenResult.Success) {
                    val res = stockProvider.syncFromSheet(tokenRes.token)
                    isLoading = false
                    if (res.isSuccess) {
                        stockCount = res.getOrDefault(0)
                        searchResults = stockProvider.searchByKeyword("")
                        val stats = stockProvider.lastSyncStats
                        val toastMsg = "同期完了: ${stockCount}種 (実在庫${stats.totalPhysicalBooks}冊 / Sold${stats.soldExcluded}冊除外) [Sheets API]"
                        Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "同期エラー: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    isLoading = false
                    Toast.makeText(context, "トークン再取得失敗", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Log.w("BookStock", "権限同意がキャンセルまたは失敗しました: resultCode=${result.resultCode}")
            Toast.makeText(context, "Google権限の許可がキャンセルされました", Toast.LENGTH_LONG).show()
        }
    }

    // スプレッドシート同期用関数
    val syncStockData: () -> Unit = {
        scope.launch {
            isLoading = true
            loadingMessage = if (currentAccount != null) {
                "Googleアカウント認証中 (${currentAccount})..."
            } else {
                "スプレッドシート(Ledger)を同期中..."
            }

            var token: String? = null
            if (currentAccount != null) {
                when (val tokenRes = googleAuthHelper.fetchToken(currentAccount)) {
                    is TokenResult.Success -> {
                        token = tokenRes.token
                        loadingMessage = "Google Sheets API (v4) から非公開データを取得中..."
                    }
                    is TokenResult.NeedConsent -> {
                        isLoading = false
                        Log.d("BookStock", "初回同意画面を起動します")
                        consentLauncher.launch(tokenRes.intent)
                        return@launch
                    }
                    is TokenResult.Error -> {
                        Log.e("BookStock", "トークン取得エラー: ${tokenRes.message}")
                        loadingMessage = "トークン取得失敗。公開URLから試行中..."
                    }
                }
            }

            val res = stockProvider.syncFromSheet(token)
            isLoading = false
            if (res.isSuccess) {
                stockCount = res.getOrDefault(0)
                searchResults = stockProvider.searchByKeyword("")
                val stats = stockProvider.lastSyncStats
                val methodDesc = if (token != null) "Sheets API" else "公開CSV"
                val toastMsg = "同期完了: ${stockCount}種 (実在庫${stats.totalPhysicalBooks}冊 / Sold${stats.soldExcluded}冊除外) [$methodDesc]"
                Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()
            } else {
                val err = res.exceptionOrNull()?.message ?: "不明なエラー"
                Log.e("BookStock", "同期失敗: $err")
                Toast.makeText(context, "同期エラー: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Google アカウント選択ランチャー
    val accountPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrEmpty()) {
                googleAuthHelper.saveAccountName(accountName)
                currentAccount = accountName
                Toast.makeText(context, "連携アカウント: $accountName", Toast.LENGTH_SHORT).show()
                syncStockData()
            }
        }
    }

    // 初回自動同期（保存済みアカウントがある場合は認証トークン付きで取得）
    LaunchedEffect(Unit) {
        syncStockData()
    }

    // カメラ撮影後の解析処理（Decision-1 前処理 ＋ Flash 背表紙解析）
    val processBitmap: (Bitmap) -> Unit = { bitmap ->
        scope.launch {
            isLoading = true
            loadingMessage = if (shelfAnalyzer.enableDecisionPrecheck) {
                "Gemini Decision-1 高速判定 ＆ 背表紙解析中..."
            } else {
                "AIが背表紙を解析中..."
            }
            Log.d("BookStock", "画像解析開始: 幅=${bitmap.width}, 高さ=${bitmap.height}, Decision前処理=${shelfAnalyzer.enableDecisionPrecheck}")
            val visionRes = shelfAnalyzer.analyzeShelf(bitmap)
            if (visionRes.isSuccess) {
                val output = visionRes.getOrNull()
                val scannedBooks = output?.spines ?: emptyList()
                val decision = output?.decision

                Log.d("BookStock", "背表紙認識成功: ${scannedBooks.size} 冊検出, Decision=$decision")
                loadingMessage = "在庫突合＆ダイレクト相場を取得中..."
                val matches = setMatcher.match(scannedBooks, stockProvider, decision)

                val cards = matches.map { match ->
                    val marketPrice = try {
                        marketResearcher.fetchDirectMarketPrice(
                            title = match.title,
                            storeBuyCount = match.storeVolumes.size,
                            isCompleteSet = match.canCompleteByStore || match.canCompleteByMerged || match.canCompleteByB || match.canCompleteByBS
                        )
                    } catch (e: Exception) {
                        Log.e("BookStock", "相場取得エラー(${match.title}): ${e.message}")
                        null
                    }
                    CardDisplayItem(match = match, market = marketPrice)
                }
                displayCards = cards
                selectedTab = 0
            } else {
                val err = visionRes.exceptionOrNull()?.message ?: "解析エラー"
                Log.e("BookStock", "Vision解析エラー: $err")
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
            isLoading = false
        }
    }

    // カメラ起動ランチャー
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            processBitmap(bitmap)
        } else {
            Log.d("BookStock", "カメラ撮影がキャンセルされました")
        }
    }

    // カメラ権限ランチャー
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d("BookStock", "カメラ権限が付与されました。カメラを起動します。")
            cameraLauncher.launch(null)
        } else {
            Log.e("BookStock", "カメラ権限が拒否されました")
            Toast.makeText(context, "カメラの使用権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // 画像ギャラリー選択ランチャー
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                @Suppress("DEPRECATION")
                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                processBitmap(bitmap)
            } catch (e: Exception) {
                Log.e("BookStock", "ギャラリー画像読み込みエラー: ${e.message}", e)
                Toast.makeText(context, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Book Stock Manager", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text("在庫: ${stockCount}件 (Ledger)", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            isLoading = true
                            loadingMessage = "スプレッドシート再同期中..."
                            val res = stockProvider.syncFromSheet()
                            isLoading = false
                            if (res.isSuccess) {
                                stockCount = res.getOrDefault(0)
                                searchResults = stockProvider.searchByKeyword(searchQuery)
                                Toast.makeText(context, "最新化完了！", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("🔄", fontSize = 18.sp)
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Text("⚙️", fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E293B))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E293B)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📸") },
                    label = { Text("棚判定＆相場") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🔍") },
                    label = { Text("在庫検索") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> {
                    // スキャン・判定画面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                        cameraLauncher.launch(null)
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                            ) {
                                Text("📷 棚を撮影", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { galleryLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                            ) {
                                Text("🖼️ 写真選択")
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (displayCards.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF1E293B), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "ブックオフの棚を撮影すると、\n作品名・全巻数・店頭巻・自宅在庫・\n帯付き(B/BS)判定・見込み利益が一瞬で出ます！\n(Gemini Decision-1 高速ガードレール対応)",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(displayCards) { card ->
                                    ResultCardView(card = card, onOpenUrl = { url ->
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    })
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // 在庫検索画面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                searchResults = stockProvider.searchByKeyword(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("書名、シリーズ、ISBNで検索...") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF1E293B),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("${searchResults.size} 件ヒット", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults.take(60)) { item ->
                                StockRowView(item = item)
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(loadingMessage, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        var tempKey by remember { mutableStateOf(shelfAnalyzer.apiKey) }
        var tempModel by remember { mutableStateOf(shelfAnalyzer.modelName) }
        var tempUseDecision by remember { mutableStateOf(shelfAnalyzer.enableDecisionPrecheck) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("アプリ設定") },
            text = {
                Column {
                    Text("Gemini APIキー:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("AIモデル名 (背表紙認識):", fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempModel,
                        onValueChange = { tempModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("gemini-3.5-flash-lite") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tempUseDecision,
                            onCheckedChange = { tempUseDecision = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Gemini Decision-1 高速前処理", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("約0.15秒・トークン無料。全巻数・完結判定・刊行形態を事前確定し誤認識を防ぎます", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Googleアカウント連携 (非公開シート同期):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    if (currentAccount != null) {
                        Text("連携中: $currentAccount", fontSize = 12.sp, color = Color(0xFF34D399))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { syncStockData() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("今すぐ再同期", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    googleAuthHelper.signOut()
                                    currentAccount = null
                                    Toast.makeText(context, "Google連携を解除しました", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("解除", fontSize = 12.sp, color = Color(0xFFF87171))
                            }
                        }
                    } else {
                        Text("非公開スプレッドシートを安全に同期するにはGoogleアカウントを選択してください", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val intent = googleAuthHelper.newChooseAccountIntent()
                                accountPickerLauncher.launch(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("Googleアカウントを選択して連携", fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveConfig(tempKey, tempModel, tempUseDecision)
                    showSettingsDialog = false
                    Toast.makeText(context, "設定を保存しました", Toast.LENGTH_SHORT).show()
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("閉じる") }
            }
        )
    }
}

@Composable
fun ResultCardView(card: CardDisplayItem, onOpenUrl: (String) -> Unit) {
    val match = card.match
    val market = card.market

    val borderColor = when {
        match.canCompleteByBS -> Color(0xFFFBBF24) // 初版帯付きコンプ: ゴールド枠
        match.canCompleteByB -> Color(0xFFEC4899)  // 重版帯付きコンプ: ピンク/ローズ枠
        match.canCompleteByStore -> Color(0xFF22C55E)
        match.canCompleteByMerged -> Color(0xFF38BDF8)
        else -> Color(0xFF334155)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            match.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        // 刊行形態（判型）バッジ
                        match.formatType?.let { format ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF0369A1),
                                modifier = Modifier.padding(0.dp)
                            ) {
                                Text(
                                    text = format,
                                    color = Color(0xFFE0F2FE),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (match.note.isNotBlank()) {
                        Text(
                            match.note,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                
                // 優先コンプバッジ表示
                if (match.canCompleteByBS) {
                    Text("👑 全巻初版帯付き(BS)コンプ!", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else if (match.canCompleteByB) {
                    Text("🎀 全巻帯付き(B重版)コンプ!", color = Color(0xFFEC4899), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else if (match.canCompleteByStore) {
                    Text("🎉 店頭で全巻揃う!", color = Color(0xFF22C55E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else if (match.canCompleteByMerged) {
                    Text("⚡ 在庫合体で全巻コンプ!", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else if (match.missingVolumes.isNotEmpty()) {
                    Text("残${match.missingVolumes.size}巻で完結", color = Color(0xFFF59E0B), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("🏪 店頭: ${match.storeVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 12.sp, color = Color(0xFF93C5FD))
                Text("📦 在庫: ${if (match.stockVolumes.isEmpty()) "なし" else match.stockVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 12.sp, color = Color(0xFF6EE7B7))
                if (match.storeObiVolumes.isNotEmpty()) {
                    Text("🎗️ 店頭帯付き: ${match.storeObiVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 11.sp, color = Color(0xFFFDE68A))
                }
                if (match.stockBVolumes.isNotEmpty()) {
                    Text("🎀 在庫帯付き(B): ${match.stockBVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 11.sp, color = Color(0xFFF472B6))
                }
                if (match.stockBSVolumes.isNotEmpty()) {
                    Text("👑 在庫初版帯付き(BS): ${match.stockBSVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 11.sp, color = Color(0xFFFCD34D))
                }
                if (match.missingVolumes.isNotEmpty()) {
                    Text("❌ 不足: ${match.missingVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 12.sp, color = Color(0xFFFCA5A5))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF172554), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("🛍️ メルカリ売切相場:", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    Text(
                        market?.mercariSoldPrice?.let { "約 ${String.format("%,d", it)} 円" } ?: "調査中...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFFF87171)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("📦 Amazon最安出品:", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    Text(
                        market?.amazonLowestPrice?.let { "${String.format("%,d", it)} 円" } ?: "調査中...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFFFBBF24)
                    )
                }
                if (market?.estimatedProfit != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = Color(0xFF1E3A8A))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💰 見込み純利益:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        Text(
                            "+${String.format("%,d", market.estimatedProfit)} 円 (粗利 ${String.format("%.1f", market.profitMarginPercent ?: 0f)}%)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                market?.mercariDetailUrl?.let { url ->
                    Text(
                        "［メルカリで精査］",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onOpenUrl(url) }
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                market?.amazonDetailUrl?.let { url ->
                    Text(
                        "［Amazonで精査］",
                        color = Color(0xFFFBBF24),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onOpenUrl(url) }
                    )
                }
            }
        }
    }
}

/**
 * 在庫一覧行（画像通りのコンディション内訳バッジ表示: D 2, B 3, BS 1, ST 1 等）
 */
@Composable
fun StockRowView(item: StockItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "在庫: ${item.totalCount}冊",
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                "${item.seriesTitle.takeIf { it.isNotEmpty() }?.let { "[$it] " } ?: ""}${item.volume?.let { "${it}巻 " } ?: ""}${item.publisher}",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // コンディション記号ごとの冊数バッジ（横スクロール可能）
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 表示順: D, B, BS, その他
                val sortedKeys = item.conditionCounts.keys.sortedWith(Comparator { a, b ->
                    val orderA = when (a) { "D" -> 0; "B" -> 1; "BS" -> 2; else -> 3 }
                    val orderB = when (b) { "D" -> 0; "B" -> 1; "BS" -> 2; else -> 3 }
                    if (orderA != orderB) orderA.compareTo(orderB) else a.compareTo(b)
                })

                items(sortedKeys) { condKey ->
                    val count = item.conditionCounts[condKey] ?: 0
                    ConditionCountBadge(conditionKey = condKey, count = count)
                }
            }
        }
    }
}

/**
 * 記号別バッジ（画像通りのカラーリング）
 * D 2: グレー/スレート系
 * B 3: ゴールド/黄褐色系
 * BS 1: ブルー/濃紺系
 * ST 1: ワインレッド/パープル系
 */
@Composable
fun ConditionCountBadge(conditionKey: String, count: Int) {
    val (bgColor, textColor) = when (conditionKey) {
        "D" -> Pair(Color(0xFF334155), Color(0xFFCBD5E1))
        "B" -> Pair(Color(0xFF854D0E), Color(0xFFFEF08A))
        "BS" -> Pair(Color(0xFF1E3A8A), Color(0xFF93C5FD))
        "ST", "S" -> Pair(Color(0xFF831843), Color(0xFFFBCFE8))
        else -> Pair(Color(0xFF374151), Color(0xFFE5E7EB))
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        modifier = Modifier.padding(0.dp)
    ) {
        Text(
            text = "$conditionKey $count",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
