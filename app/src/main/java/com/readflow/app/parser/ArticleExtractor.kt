package com.readflow.app.parser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.readflow.app.data.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

object ArticleExtractor {

    suspend fun extractWithWebView(context: Context, url: String): Result<Article> {
        return runCatching {
            val html = renderHtmlWithWebView(context, url)
            parseHtml(url, html)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderHtmlWithWebView(context: Context, url: String): String =
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            var isFinished = false
            var webView: WebView? = null

            fun cleanup() {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                } catch (_: Exception) {}
            }

            mainHandler.post {
                try {
                    val wv = WebView(context)
                    webView = wv
                    val settings = wv.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.48 NetType/WIFI Language/zh_CN"

                    val timeoutRunnable = Runnable {
                        if (!isFinished) {
                            isFinished = true
                            wv.evaluateJavascript("(function(){ return document.documentElement.outerHTML; })();") { rawHtml ->
                                val cleanHtml = unescapeJsString(rawHtml)
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resume(cleanHtml)
                                }
                            }
                        }
                    }

                    mainHandler.postDelayed(timeoutRunnable, 10000)

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            val checkScript = """
                                (function() {
                                    var content = document.getElementById('js_content') || document.querySelector('article') || document.body;
                                    var imgs = document.querySelectorAll('img[data-src]');
                                    for (var i = 0; i < imgs.length; i++) {
                                        imgs[i].setAttribute('src', imgs[i].getAttribute('data-src'));
                                    }
                                    return (content && content.innerText && content.innerText.trim().length > 80);
                                })();
                            """.trimIndent()

                            wv.evaluateJavascript(checkScript) { ready ->
                                if (ready == "true" && !isFinished) {
                                    mainHandler.removeCallbacks(timeoutRunnable)
                                    mainHandler.postDelayed({
                                        if (!isFinished) {
                                            isFinished = true
                                            wv.evaluateJavascript("(function(){ return document.documentElement.outerHTML; })();") { rawHtml ->
                                                val cleanHtml = unescapeJsString(rawHtml)
                                                cleanup()
                                                if (continuation.isActive) {
                                                    continuation.resume(cleanHtml)
                                                }
                                            }
                                        }
                                    }, 800)
                                }
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        cleanup()
                    }

                    wv.loadUrl(url)
                } catch (e: Exception) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.cancel(e)
                    }
                }
            }
        }

    private fun unescapeJsString(str: String?): String {
        if (str == null || str == "null") return ""
        var s = str
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private suspend fun parseHtml(url: String, html: String): Article = withContext(Dispatchers.Default) {
        val doc: Document = Jsoup.parse(html, url)

        // 微信特定与通用标题
        var title = doc.select("#activity-name").text().trim()
        if (title.isEmpty()) {
            title = doc.select("meta[property=og:title]").attr("content").trim()
        }
        if (title.isEmpty()) {
            title = doc.select("h1").firstOrNull()?.text()?.trim() ?: ""
        }
        if (title.isEmpty()) {
            title = doc.title().trim()
        }
        if (title.isEmpty()) {
            title = "未命名文章"
        }

        // 作者
        var author = doc.select("#js_name").text().trim()
        if (author.isEmpty()) {
            author = doc.select("meta[name=author]").attr("content").trim()
        }
        if (author.isEmpty()) {
            author = doc.select(".profile_nickname").text().trim()
        }

        // 核心正文容器选择器
        val contentElement: Element? = doc.selectFirst("#js_content")
            ?: doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst(".rich_media_content")
            ?: doc.selectFirst(".post-content")
            ?: doc.selectFirst(".article-content")

        // 过滤微信干扰元素
        contentElement?.select(".reward_area, #js_toobar3, .rich_media_tool, script, style")?.remove()

        // 修正懒加载图片标签
        contentElement?.select("img")?.forEach { img ->
            val dataSrc = img.attr("data-src").ifEmpty { img.attr("src") }
            if (dataSrc.isNotEmpty()) {
                img.attr("src", dataSrc)
            }
        }

        val markdown = convertElementToMarkdown(contentElement)
        val rawText = contentElement?.text()?.trim() ?: ""

        if (rawText.isEmpty() && markdown.isEmpty()) {
            throw Exception("未能提取到有效正文，页面可能触发风控或未完成加载")
        }

        Article(
            title = title,
            author = author,
            url = url,
            contentMarkdown = markdown,
            rawText = rawText
        )
    }

    private fun convertElementToMarkdown(element: Element?): String {
        if (element == null) return ""
        val sb = StringBuilder()

        for (child in element.children()) {
            when (child.tagName().lowercase()) {
                "h1" -> sb.append("# ").append(child.text().trim()).append("\n\n")
                "h2" -> sb.append("## ").append(child.text().trim()).append("\n\n")
                "h3" -> sb.append("### ").append(child.text().trim()).append("\n\n")
                "p", "section" -> {
                    val pImgs = child.select("img")
                    for (img in pImgs) {
                        val src = img.attr("src").ifEmpty { img.attr("data-src") }
                        if (src.isNotEmpty()) {
                            sb.append("![](").append(src).append(")\n\n")
                        }
                    }
                    val text = child.text().trim()
                    if (text.isNotEmpty()) {
                        sb.append(text).append("\n\n")
                    }
                }
                "img" -> {
                    val src = child.attr("src").ifEmpty { child.attr("data-src") }
                    if (src.isNotEmpty()) {
                        sb.append("![](").append(src).append(")\n\n")
                    }
                }
                "blockquote" -> {
                    val quote = child.text().trim()
                    if (quote.isNotEmpty()) {
                        sb.append("> ").append(quote).append("\n\n")
                    }
                }
                else -> {
                    val text = child.text().trim()
                    if (text.isNotEmpty()) {
                        sb.append(text).append("\n\n")
                    }
                }
            }
        }

        if (sb.isEmpty()) {
            sb.append(element.text())
        }
        return sb.toString().trim()
    }
}
