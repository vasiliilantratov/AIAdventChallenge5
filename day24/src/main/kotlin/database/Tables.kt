package org.example.database

import org.jetbrains.exposed.dao.id.LongIdTable

object Documents : LongIdTable("documents") {
    val filePath = text("file_path").uniqueIndex()
    val fileName = text("file_name")
    val fileSize = long("file_size")
    val lastModified = long("last_modified")
    val contentHash = text("content_hash")
    val indexedAt = long("indexed_at")
    val fileType = text("file_type")
}

object Chunks : LongIdTable("chunks") {
    val documentId = long("document_id").references(Documents.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val chunkIndex = integer("chunk_index")
    val content = text("content")
    val startChar = integer("start_char")
    val endChar = integer("end_char")
    val tokenCount = integer("token_count").nullable()
    val createdAt = long("created_at")
    
    init {
        uniqueIndex(documentId, chunkIndex)
    }
}

object Embeddings : LongIdTable("embeddings") {
    val chunkId = long("chunk_id").references(Chunks.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val embedding = binary("embedding")
    val modelName = text("model_name")
    val dimension = integer("dimension")
    val createdAt = long("created_at")
}

object Conversations : LongIdTable("conversations") {
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object Messages : LongIdTable("messages") {
    val conversationId = long("conversation_id").references(Conversations.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val role = text("role") // "user" или "assistant"
    val content = text("content")
    val mode = text("mode") // "plain" или "rag"
    val sourcesJson = text("sources_json").nullable() // JSON с информацией об источниках для RAG режима
    val createdAt = long("created_at")
}

