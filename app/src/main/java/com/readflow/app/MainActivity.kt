package com.readflow.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.readflow.app.data.Article
import com.readflow.app.parser.ArticleExtractor
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val articles = mutableStateListOf<Article>()
    private var currentArticle by mutableStateOf<Article?>(null)
    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 处理系统一键分享传入的链接 (如微信/浏览器点击“分享到 ReadFlow”)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val activeArticle = currentArticle
                    if (activeArticle != null) {
                        ArticleDetailScreen(
                            article = activeArticle,
                            onBack = { currentArticle = null },
                            onCopyMarkdown = { copyToClipboard(it) },
                            onShare = { shareText(it) }
                        )
                    } else {
                        ArticleListScreen(
                            articles = articles,
                            isLoading = isLoading,
                            onAddUrl = { url -> fetchArticle(url) },
                            onArticleClick = { currentArticle = it },
                            onDelete = { articles.remove(it) }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val extractedUrl = extractUrl(sharedText)
            if (extractedUrl.isNotEmpty()) {
                fetchArticle(extractedUrl)
            }
        }
    }

    private fun extractUrl(text: String): String {
        val regex = Regex("https?://[a-zA-Z0-9./?=_%&\\-#]+")
        val match = regex.find(text)
        return match?.value ?: ""
    }

    private fun fetchArticle(url: String) {
        if (url.isBlank()) return
        isLoading = true
        lifecycleScope.launch {
            val result = ArticleExtractor.extractFromUrl(url)
            isLoading = false
            result.onSuccess { article ->
                articles.add(0, article)
                currentArticle = article
                Toast.makeText(this@MainActivity, "解析成功: ${article.title}", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "解析失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Markdown", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Markdown 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "分享文章"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleListScreen(
    articles: List<Article>,
    isLoading: Boolean,
    onAddUrl: (String) -> Unit,
    onArticleClick: (Article) -> Unit,
    onDelete: (Article) -> Unit
) {
    var inputUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReadFlow 稍后读", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 输入与解析卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("输入或粘贴微信/网页链接") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onAddUrl(inputUrl.trim())
                            inputUrl = ""
                        },
                        modifier = Modifier.align(Alignment.End),
                        enabled = !isLoading && inputUrl.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("抓取中...")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("解析并离线")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "离线文章列表 (${articles.size})",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (articles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无离线文章\n支持从微信/浏览器一键“分享”到本应用",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(articles, key = { it.id }) { article ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArticleClick(article) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = article.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        maxLines = 2
                                    )
                                    if (article.author.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = article.author,
                                            fontSize = 13.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(article) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    article: Article,
    onBack: () -> Unit,
    onCopyMarkdown: (String) -> Unit,
    onShare: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(article.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onCopyMarkdown(article.contentMarkdown) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制 Markdown")
                    }
                    IconButton(onClick = { onShare(article.contentMarkdown) }) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = article.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            if (article.author.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "作者 / 来源: ${article.author}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // 显示纯净排版正文
            Text(
                text = article.contentMarkdown,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        }
    }
}
