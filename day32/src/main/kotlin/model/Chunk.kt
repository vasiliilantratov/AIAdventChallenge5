package org.example.model

data class Chunk(
    val id: Long? = null,
    val documentId: Long,
    val chunkIndex: Int,
    val content: String,
    val startChar: Int,
    val endChar: Int,
    val tokenCount: Int? = null,
    val createdAt: Long
)

