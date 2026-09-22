package com.readflow.app.parser

import com.readflow.app.data.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

object ArticleExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun extractFromUrl(url: String): Result<Article> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP 请求失败: ${response.code}")
            }

            val html = response.body?.string() ?: throw Exception("响应正文为空")
            parseHtml(url, html)
        }
    }

    private fun parseHtml(url: String, html: String): Article {
        val doc: Document = Jsoup.parse(html, url)

        // 兼容微信公众号与通用页面
        var title = doc.select("#activity-name").text().trim()
        if (title.isEmpty()) {
            title = doc.select("meta[property=og:title]").attr("content").trim()
        }
        if (title.isEmpty()) {
            title = doc.title().trim()
        }
        if (title.isEmpty()) {
            title = "未命名文章"
        }

        var author = doc.select("#js_name").text().trim()
        if (author.isEmpty()) {
            author = doc.select("meta[name=author]").attr("content").trim()
        }

        // 核心正文容器选择器
        val contentElement: Element? = doc.selectFirst("#js_content")
            ?: doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst(".post-content")
            ?: doc.selectFirst(".article-content")
            ?: doc.body()

        // 转换图片懒加载标签（微信特定 data-src -> src）
        contentElement?.select("img")?.forEach { img ->
            val dataSrc = img.attr("data-src")
            if (dataSrc.isNotEmpty()) {
                img.attr("src", dataSrc)
            }
        }

        // 转简单 Markdown
        val markdown = convertElementToMarkdown(contentElement)
        val rawText = contentElement?.text() ?: ""

        return Article(
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
                "p" -> {
                    val text = child.text().trim()
                    if (text.isNotEmpty()) {
                        sb.append(text).append("\n\n")
                    }
                }
                "img" -> {
                    val src = child.attr("src").ifEmpty { child.attr("data-src") }
                    val alt = child.attr("alt").ifEmpty { "image" }
                    if (src.isNotEmpty()) {
                        sb.append("![").append(alt).append("](").append(src).append(")\n\n")
                    }
                }
                "blockquote" -> {
                    val quote = child.text().trim()
                    if (quote.isNotEmpty()) {
                        sb.append("> ").append(quote).append("\n\n")
                    }
                }
                else -> {
                    val subText = child.text().trim()
                    if (subText.isNotEmpty()) {
                        sb.append(subText).append("\n\n")
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
