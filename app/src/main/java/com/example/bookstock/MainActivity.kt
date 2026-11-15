package com.example.bookstock

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.content.FileProvider
import com.example.bookstock.modules.market.DirectMarketPrice
import com.example.bookstock.modules.market.MarketResearcher
import com.example.bookstock.modules.matcher.MatchedSeriesStatus
import com.example.bookstock.modules.matcher.SetMatcher
import com.example.bookstock.modules.stock.StockItem
import com.example.bookstock.modules.stock.StockProvider
import com.example.bookstock.modules.vision.ShelfAnalyzer
import kotlinx.coroutines.launch
import java.io.File

/**
 * テキストから検索キーワード（ISBN番号 または 作品名）を自動抽出する
 * 返り値: Pair(抽出キーワード, 抽出種別: "ISBN" / "TITLE" / "")
 */
fun extractSearchKeyword(raw: String): Pair<String, String> {
    if (raw.isBlank()) return Pair("", "")
    val text = raw.trim()

    // 1. 複数ISBNまたは単一ISBNの抽出 (日本語混じりリスト完全対応)
    val isbns = StockProvider.extractAllIsbns(text)
    if (isbns.size > 1) {
        return Pair(isbns.joinToString(" "), "ISBN_LIST")
    } else if (isbns.size == 1) {
        return Pair(isbns[0], "ISBN")
    }

    // 2. URL除去
    val urlRegex = Regex("https?://[^\\s]+")
    var cleaned = text.replace(urlRegex, "").trim()

    // 3. 照合用定型指示語（ノイズ）の自動除去
    val noisePhrases = listOf(
        "コンビニコミックか判別",
        "コンビニコミックか判定",
        "コンビニ版か判別",
        "コンビニ版か判定",
        "コンビニコミック",
        "全巻セット",
        "コミックスか判別",
        "コミックスか判定",
        "判型か判別",
        "判型か判定"
    )
    for (noise in noisePhrases) {
        cleaned = cleaned.replace(noise, "")
    }
    cleaned = cleaned.replace("\\s+".toRegex(), " ").trim()

    if (cleaned.isNotBlank()) {
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        val titleCandidate = lines.firstOrNull() ?: cleaned
        return Pair(titleCandidate, "TITLE")
    }

    return Pair("", "")
}

/**
 * クリップボードのテキストを安全に取得する
 */
fun getClipboardText(context: Context): String {
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString() ?: ""
        } else {
            ""
        }
    } catch (e: Exception) {
        ""
    }
}

class MainActivity : ComponentActivity() {
    private val stockProvider = StockProvider()
    private val shelfAnalyzer = ShelfAnalyzer()
    private val setMatcher = SetMatcher()
    private val marketResearcher = MarketResearcher()

    // 外部アプリ（Google レンズ等）からの共有テキスト管理ステート: Pair(キーワード, 種別)
    private var sharedQueryState by mutableStateOf<Pair<String, String>?>(null)

    companion object {
        // 直近3件のデバッグログ保持 (最新が先頭)
        val debugLogs = mutableStateListOf<String>()

        fun addDebugLog(type: String, text: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
            val preview = if (text.length > 250) text.take(250) + "..." else text
            val entry = "[$time] [$type]\n$preview"
            Log.d("BookStockDebug", entry)
            debugLogs.add(0, entry)
            while (debugLogs.size > 3) {
                debugLogs.removeLast()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 設定読み込み（11/19廃止の3.6-flashが残っている場合は自動で3.5-flash-liteへ移行）
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        shelfAnalyzer.apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val savedModel = prefs.getString("gemini_model_name", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        shelfAnalyzer.modelName = if (savedModel == "gemini-3.6-flash") "gemini-3.5-flash-lite" else savedModel

        handleShareIntent(intent)

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
                        sharedQuery = sharedQueryState,
                        onClearSharedQuery = { sharedQueryState = null },
                        onSaveConfig = { key, model ->
                            shelfAnalyzer.apiKey = key
                            shelfAnalyzer.modelName = model
                            prefs.edit()
                                .putString("gemini_api_key", key)
                                .putString("gemini_model_name", model)
                                .apply()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val raw = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Log.d("BookStock", "外部共有テキスト受信: $raw")
            addDebugLog("外部共有受信", raw)

            // 1. 受信テキストからISBNやタイトルを抽出
            val (keyword, type) = extractSearchKeyword(raw)
            if (keyword.isNotBlank()) {
                sharedQueryState = Pair(keyword, type)
            } else {
                // 2. URLのみ（share.google等）でテキストが取れない場合、クリップボードの内容を自動フォールバック
                val clipText = getClipboardText(this)
                val (clipKeyword, clipType) = extractSearchKeyword(clipText)
                if (clipKeyword.isNotBlank()) {
                    Log.d("BookStock", "クリップボードから自動フォールバック: $clipKeyword ($clipType)")
                    sharedQueryState = Pair(clipKeyword, clipType)
                } else {
                    sharedQueryState = Pair("", "URL_ONLY")
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
    sharedQuery: Pair<String, String>?,
    onClearSharedQuery: () -> Unit,
    onSaveConfig: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var displayCards by remember { mutableStateOf<List<CardDisplayItem>>(emptyList()) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFormatFilter by remember { mutableStateOf("すべて") }
    var searchResults by remember { mutableStateOf<List<StockItem>>(emptyList()) }
    var stockCount by remember { mutableStateOf(0) }

    // Googleレンズ等から共有されて起動した場合、自動で在庫検索を開く
    LaunchedEffect(sharedQuery) {
        if (sharedQuery != null) {
            val (query, type) = sharedQuery
            if (query.isNotBlank()) {
                selectedTab = 1
                searchQuery = query
                searchResults = stockProvider.searchByKeyword(query, selectedFormatFilter)
                val msg = when (type) {
                    "ISBN_LIST" -> "ISBNリスト (${query.split(" ").size}冊) を一括抽出して検索！"
                    "ISBN" -> "ISBN「$query」で在庫検索中！"
                    else -> "「$query」でAND検索中！"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } else if (type == "URL_ONLY") {
                selectedTab = 1
                Toast.makeText(
                    context,
                    "Google AI共有URLを受信しました。レンズの回答横にある【📋コピー】を押して「📋 貼付」をタップすると即座に在庫照合できます！",
                    Toast.LENGTH_LONG
                ).show()
            }
            onClearSharedQuery()
        }
    }

    // スプレッドシート自動同期
    LaunchedEffect(Unit) {
        isLoading = true
        loadingMessage = "スプレッドシート(Stock_Summary)を同期中..."
        val res = stockProvider.syncFromSheet()
        isLoading = false
        if (res.isSuccess) {
            stockCount = res.getOrDefault(0)
            if (searchQuery.isBlank()) {
                searchResults = stockProvider.searchByKeyword("")
            }
            Toast.makeText(context, "在庫 ${stockCount}件 同期完了！", Toast.LENGTH_SHORT).show()
        } else {
            val err = res.exceptionOrNull()?.message ?: "不明なエラー"
            Log.e("BookStock", "初期同期失敗: $err")
            Toast.makeText(context, "同期エラー: $err", Toast.LENGTH_LONG).show()
        }
    }

    // カメラ撮影後の解析処理
    val processBitmap: (Bitmap) -> Unit = { bitmap ->
        scope.launch {
            isLoading = true
            loadingMessage = "AIが背表紙を解析中..."
            Log.d("BookStock", "画像解析開始: 幅=${bitmap.width}, 高さ=${bitmap.height}")
            try {
                val visionRes = shelfAnalyzer.analyzeShelfBitmap(bitmap)
                if (visionRes.isSuccess) {
                    val scannedBooks = visionRes.getOrDefault(emptyList())
                    Log.d("BookStock", "背表紙認識成功: ${scannedBooks.size} 冊検出")
                    loadingMessage = "在庫突合＆ダイレクト相場を取得中..."
                    val matches = setMatcher.match(scannedBooks, stockProvider)

                    val cards = matches.map { match ->
                        val marketPrice = try {
                            marketResearcher.estimateMarketPrice(
                                title = match.title,
                                storeBuyCount = match.storeVolumes.size,
                                totalVolumes = match.totalVolumes,
                                isCompleteSet = match.canCompleteByStore || match.canCompleteByMerged
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
            } finally {
                isLoading = false
            }
        }
    }

    // 写真一時ファイル（カメラ撮影およびギャラリー選択の両方で共用し、Googleレンズへ手渡す）
    val photoFile = remember { File(context.cacheDir, "temp_shelf_capture.jpg") }
    val photoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }

    // カメラ撮影ランチャー
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoFile.exists() && photoFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                if (bitmap != null) {
                    processBitmap(bitmap)
                } else {
                    Toast.makeText(context, "写真の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("BookStock", "撮影写真デコードエラー: ${e.message}", e)
                Toast.makeText(context, "画像処理に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("BookStock", "カメラ撮影がキャンセルされました")
        }
    }

    // カメラ権限ランチャー
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d("BookStock", "カメラ権限が付与されました。カメラを起動します。")
            cameraLauncher.launch(photoUri)
        } else {
            Log.e("BookStock", "カメラ権限が拒否されました")
            Toast.makeText(context, "カメラの使用権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // 画像ギャラリー選択ランチャー（選択画像をphotoFileに複製し、Lensと完全共有可能にする）
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    photoFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                if (bitmap != null) {
                    processBitmap(bitmap)
                } else {
                    Toast.makeText(context, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                }
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
                        Text("在庫: ${stockCount}件 (Stock_Summary)", fontSize = 11.sp, color = Color(0xFF94A3B8))
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
                                        cameraLauncher.launch(photoUri)
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
                                    "ブックオフの棚を撮影すると、\n作品名・判型・全巻数・店頭巻・自宅在庫・\nダイレクト相場と見込み利益が一瞬で出ます！",
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
                                    ResultCardView(
                                        card = card,
                                        onOpenUrl = { url ->
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(intent)
                                        },
                                        onLaunchLensWithPrompt = { prompt ->
                                            // 1. クリップボードへ定型文コピー
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("LensPrompt", prompt)
                                            clipboard.setPrimaryClip(clip)

                                            // 2. Google レンズに「画像そのもの」を手渡しして起動 (ACTION_SEND image/jpeg)
                                            if (photoFile.exists() && photoFile.length() > 0) {
                                                try {
                                                    val lensIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "image/jpeg"
                                                        putExtra(Intent.EXTRA_STREAM, photoUri)
                                                        setPackage("com.google.ar.lens")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(lensIntent)
                                                    Toast.makeText(context, "画像をレンズに送信しました！\n検索欄をタップして【貼り付け】してください", Toast.LENGTH_LONG).show()
                                                } catch (e: Exception) {
                                                    Log.w("BookStock", "com.google.ar.lens直接起動失敗: ${e.message}")
                                                    try {
                                                        val chooser = Intent(Intent.ACTION_SEND).apply {
                                                            type = "image/jpeg"
                                                            putExtra(Intent.EXTRA_STREAM, photoUri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(Intent.createChooser(chooser, "Googleレンズで照合"))
                                                    } catch (err: Exception) {
                                                        Toast.makeText(context, "レンズの起動に失敗しました: ${err.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } else {
                                                try {
                                                    val lensIntent = Intent(Intent.ACTION_VIEW).apply {
                                                        setPackage("com.google.ar.lens")
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(lensIntent)
                                                    Toast.makeText(context, "照合文をコピーしました！レンズ貼り付け欄でお使いください", Toast.LENGTH_SHORT).show()
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "Googleレンズを起動できませんでした", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )
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
                        // 検索入力欄 ＆ クリップボード貼付ボタン
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    searchResults = stockProvider.searchByKeyword(it, selectedFormatFilter)
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("書名、ISBN、出版形態(文庫等)でAND検索...") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1E293B),
                                    unfocusedContainerColor = Color(0xFF1E293B),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            // クリップボードからワンタップ貼付＆ISBN自動抽出ボタン
                            Button(
                                onClick = {
                                    val clip = getClipboardText(context)
                                    MainActivity.addDebugLog("クリップボード貼付", clip)
                                    val (kw, type) = extractSearchKeyword(clip)
                                    if (kw.isNotBlank()) {
                                        searchQuery = kw
                                        searchResults = stockProvider.searchByKeyword(kw, selectedFormatFilter)
                                        val msg = when (type) {
                                            "ISBN_LIST" -> "ISBNリスト (${kw.split(" ").size}冊) を一括抽出！"
                                            "ISBN" -> "ISBN「$kw」を抽出して検索！"
                                            else -> "「$kw」で絞り込み中"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    } else if (clip.isNotBlank()) {
                                        searchQuery = clip.trim()
                                        searchResults = stockProvider.searchByKeyword(clip.trim(), selectedFormatFilter)
                                        Toast.makeText(context, "コピー内容で検索中", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "クリップボードは空です", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text("📋 貼付", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ワンタップ判型フィルターチップ (すべて / 文庫 / 単行本 / コミックス)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("すべて", "文庫", "単行本", "コミックス").forEach { filterName ->
                                val isSelected = selectedFormatFilter == filterName
                                val chipBg = when {
                                    isSelected && filterName == "文庫" -> Color(0xFF7E22CE)
                                    isSelected && filterName == "単行本" -> Color(0xFF2563EB)
                                    isSelected && filterName == "コミックス" -> Color(0xFF059669)
                                    isSelected -> Color(0xFF38BDF8)
                                    else -> Color(0xFF1E293B)
                                }
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = chipBg,
                                    modifier = Modifier.clickable {
                                        selectedFormatFilter = filterName
                                        searchResults = stockProvider.searchByKeyword(searchQuery, filterName)
                                    }
                                ) {
                                    Text(
                                        text = filterName,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${searchResults.size} 件ヒット", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(6.dp))

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
        var isFetchingModels by remember { mutableStateOf(false) }
        var availableModels by remember {
            mutableStateOf<List<Pair<String, String>>>(
                listOf(
                    Pair("gemini-3.5-flash-lite", "🪶 Gemini 3.5 Flash-Lite (最速・推奨)"),
                    Pair("gemini-3.8-flash", "⚡ Gemini 3.8 Flash (安定・後継標準)"),
                    Pair("gemini-4-hydrogen", "🚀 Gemini 4 Hydrogen (最新エージェント特化)")
                )
            )
        }

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AIモデル選択:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(
                            onClick = {
                                if (tempKey.isBlank()) {
                                    Toast.makeText(context, "APIキーを入力してください", Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        isFetchingModels = true
                                        val res = shelfAnalyzer.fetchAvailableVisionModels(tempKey)
                                        isFetchingModels = false
                                        if (res.isSuccess) {
                                            val list = res.getOrDefault(emptyList())
                                            if (list.isNotEmpty()) {
                                                availableModels = list
                                                Toast.makeText(context, "${list.size} 件の有効なGeminiモデルを取得しました！", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "利用可能なモデルが見つかりませんでした", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            val err = res.exceptionOrNull()?.message ?: "取得失敗"
                                            Toast.makeText(context, "取得エラー: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(if (isFetchingModels) "取得中..." else "🔄 一覧手動更新", fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableModels.take(5).forEach { (modelId, label) ->
                            val isSelected = tempModel == modelId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E293B),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { tempModel = modelId }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Text("✓", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("カスタムモデル名 (直接入力も可能):", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    OutlinedTextField(
                        value = tempModel,
                        onValueChange = { tempModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("gemini-3.5-flash-lite") },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("📜 直近の受信・貼付データ (最新3件):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.height(4.dp))
                    if (MainActivity.debugLogs.isEmpty()) {
                        Text("まだログはありません", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MainActivity.debugLogs.forEach { logItem ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF0F172A),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        logItem,
                                        fontSize = 11.sp,
                                        color = Color(0xFFE2E8F0),
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveConfig(tempKey, tempModel)
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
fun ResultCardView(
    card: CardDisplayItem,
    onOpenUrl: (String) -> Unit,
    onLaunchLensWithPrompt: (String) -> Unit
) {
    val match = card.match
    val market = card.market

    val borderColor = when {
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
            // タイトル・判型バッジ・完結ステータス行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        match.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    match.format?.let { fmt ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF334155)
                        ) {
                            Text(
                                fmt,
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (match.canCompleteByStore) {
                    Text("🎉 店頭で全巻揃う!", color = Color(0xFF22C55E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else if (match.canCompleteByMerged) {
                    Text("⚡ 在庫合体で全巻コンプ!", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else if (match.missingVolumes.isNotEmpty()) {
                    Text("残${match.missingVolumes.size}巻で完結", color = Color(0xFFF59E0B), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 巻数・在庫コンディション内訳
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("🏪 店頭: ${match.storeVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 12.sp, color = Color(0xFF93C5FD))

                // 自宅在庫の内訳詳細（初版/帯/傷み/通常）を表示
                val stockDetailText = if (match.stockVolumes.isEmpty()) {
                    "なし"
                } else {
                    match.stockVolumes.joinToString(", ") { vol ->
                        val detail = match.stockVolumeDetails[vol]
                        if (detail != null) {
                            "${vol}巻<${detail.formatSummary()}>"
                        } else {
                            "${vol}巻"
                        }
                    }
                }
                Text("📦 在庫: $stockDetailText", fontSize = 12.sp, color = Color(0xFF6EE7B7))

                if (match.missingVolumes.isNotEmpty()) {
                    Text("❌ 不足: ${match.missingVolumes.joinToString(", ") { "${it}巻" }}", fontSize = 12.sp, color = Color(0xFFFCA5A5))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 相場・利益試算ボックス
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

            Spacer(modifier = Modifier.height(10.dp))

            // アクションボタン列（レンズ照合、メルカリ精査、Amazon精査）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1タップ Google レンズ照合ボタン（画像付き）
                Button(
                    onClick = {
                        val fmt = match.format?.takeIf { it.isNotBlank() } ?: "コミックス"
                        val prompt = "${match.title} $fmt コンビニコミックか判別 全巻セット"
                        onLaunchLensWithPrompt(prompt)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("🔍 レンズ照合", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    market?.mercariDetailUrl?.let { url ->
                        Text(
                            "［メルカリで精査］",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onOpenUrl(url) }
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
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
}

/**
 * 在庫一覧行（出版形態による直感色分け ＆ 在庫0冊の優先仕入れハイライト）
 * 在庫0冊: 深いアンバー/琥珀ブラウン背景 + ゴールド枠線 + 「🚨要仕入!」
 * 在庫あり: 文庫=深紫, 単行本=濃紺, コミックス=深緑
 * コンディション: 各数量（通常 N冊, 帯あり N冊, 初版 N冊, 新装版 N冊, ダメージ N冊）を明示
 */
@Composable
fun StockRowView(item: StockItem) {
    val isZero = item.isZeroStock
    val isBunko = item.category.contains("文庫") || item.seriesTitle.contains("文庫")
    val isComics = item.category.contains("コミック") || item.category.contains("漫画")
    val isTankobon = item.category.contains("単行本") || item.category.contains("単")

    val (bgColor, borderColor, formatName) = when {
        isZero -> Triple(Color(0xFF451A03), Color(0xFFF59E0B), "要仕入") // 琥珀/ゴールド
        isBunko -> Triple(Color(0xFF2E1065), Color(0xFF7E22CE), "文庫")
        isTankobon -> Triple(Color(0xFF0F2942), Color(0xFF2563EB), "単行本")
        isComics -> Triple(Color(0xFF064E3B), Color(0xFF059669), "コミックス")
        else -> Triple(Color(0xFF1E293B), Color(0xFF334155), "一般")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(if (isZero) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isZero) {
                        Text(
                            "🚨要仕入! ",
                            color = Color(0xFFF59E0B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            "在庫: 0冊",
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            "[$formatName] ",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                        Text(
                            "在庫: ${item.effectiveCount}冊",
                            color = Color(0xFF34D399),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                "${item.seriesTitle.takeIf { it.isNotEmpty() }?.let { "[$it] " } ?: ""}${item.volume?.let { "${it}巻 " } ?: ""}${item.publisher} ${item.subTitle.takeIf { it.isNotEmpty() }?.let { "($it)" } ?: ""}",
                fontSize = 11.sp,
                color = Color(0xFFCBD5E1)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // コンディション別数量バッジ
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isZero) {
                    ConditionBadge(text = "🚨 自宅在庫なし (優先確保)", bgColor = Color(0x66B45309), textColor = Color(0xFFFDE68A))
                } else {
                    if (item.unmarkedCount > 0) {
                        ConditionBadge(text = "通常 ${item.unmarkedCount}冊", bgColor = Color(0x66334155), textColor = Color(0xFF94A3B8))
                    }
                    if (item.obiCount > 0) {
                        ConditionBadge(text = "帯あり ${item.obiCount}冊", bgColor = Color(0x66854D0E), textColor = Color(0xFFFEF08A))
                    }
                    if (item.firstEditionCount > 0) {
                        ConditionBadge(text = "初版 ${item.firstEditionCount}冊", bgColor = Color(0x661E3A8A), textColor = Color(0xFF93C5FD))
                    }
                    if (item.newEditionCount > 0) {
                        ConditionBadge(text = "新装版 ${item.newEditionCount}冊", bgColor = Color(0x664C1D95), textColor = Color(0xFFDDD6FE))
                    }
                    if (item.damagedCount > 0) {
                        ConditionBadge(text = "ダメージ ${item.damagedCount}冊", bgColor = Color(0x667F1D1D), textColor = Color(0xFFFCA5A5))
                    }
                }
            }
        }
    }
}

@Composable
fun ConditionBadge(text: String, bgColor: Color, textColor: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        modifier = Modifier.padding(0.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
