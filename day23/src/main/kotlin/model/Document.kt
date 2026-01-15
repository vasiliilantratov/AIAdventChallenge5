package org.example.model

data class Document(
    val id: Long? = null,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val contentHash: String,
    val indexedAt: Long,
    val fileType: String
)

