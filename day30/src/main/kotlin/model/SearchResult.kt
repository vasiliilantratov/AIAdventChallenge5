package org.example.model

data class SearchResult(
    val chunk: Chunk,
    val document: Document,
    val similarity: Float,
    val content: String
)

