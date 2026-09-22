package com.readflow.app.data

data class Article(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val author: String = "",
    val url: String,
    val contentMarkdown: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
)
