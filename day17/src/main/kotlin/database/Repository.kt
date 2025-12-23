package org.example.database

import org.example.model.Chunk
import org.example.model.Document
import org.example.model.Embedding
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Repository {
    
    fun saveDocument(doc: Document): Long = org.jetbrains.exposed.sql.transactions.transaction {
        val existing = Documents.select { Documents.filePath eq doc.filePath }.firstOrNull()
        
        if (existing != null) {
            val docId = existing[Documents.id].value
            // Удаляем старые чанки (эмбеддинги удалятся каскадно)
            Chunks.deleteWhere { Chunks.documentId eq docId }
            // Обновляем документ
            Documents.update({ Documents.id eq docId }) {
                it[fileName] = doc.fileName
                it[fileSize] = doc.fileSize
                it[lastModified] = doc.lastModified
                it[contentHash] = doc.contentHash
                it[indexedAt] = doc.indexedAt
                it[fileType] = doc.fileType
            }
            docId
        } else {
            Documents.insert {
                it[filePath] = doc.filePath
                it[fileName] = doc.fileName
                it[fileSize] = doc.fileSize
                it[lastModified] = doc.lastModified
                it[contentHash] = doc.contentHash
                it[indexedAt] = doc.indexedAt
                it[fileType] = doc.fileType
            }[Documents.id].value
        }
    }
    
    fun findDocumentByPath(path: String): Document? = org.jetbrains.exposed.sql.transactions.transaction {
        Documents.select { Documents.filePath eq path }.firstOrNull()?.let {
            Document(
                id = it[Documents.id].value,
                filePath = it[Documents.filePath],
                fileName = it[Documents.fileName],
                fileSize = it[Documents.fileSize],
                lastModified = it[Documents.lastModified],
                contentHash = it[Documents.contentHash],
                indexedAt = it[Documents.indexedAt],
                fileType = it[Documents.fileType]
            )
        }
    }
    
    fun saveChunks(chunks: List<Chunk>): List<Long> = org.jetbrains.exposed.sql.transactions.transaction {
        chunks.map { chunk ->
            Chunks.insert {
                it[documentId] = chunk.documentId
                it[chunkIndex] = chunk.chunkIndex
                it[content] = chunk.content
                it[startChar] = chunk.startChar
                it[endChar] = chunk.endChar
                it[tokenCount] = chunk.tokenCount
                it[createdAt] = chunk.createdAt
            }[Chunks.id].value
        }
    }
    
    fun saveEmbeddings(embeddings: List<Embedding>) = org.jetbrains.exposed.sql.transactions.transaction {
        embeddings.forEach { embedding ->
            val bytes = floatArrayToBytes(embedding.embedding)
            Embeddings.insert {
                it[chunkId] = embedding.chunkId
                it[Embeddings.embedding] = bytes
                it[modelName] = embedding.modelName
                it[dimension] = embedding.dimension
                it[createdAt] = embedding.createdAt
            }
        }
    }
    
    fun findAllEmbeddings(): List<Pair<Long, FloatArray>> = org.jetbrains.exposed.sql.transactions.transaction {
        Embeddings.selectAll().map {
            val chunkId = it[Embeddings.chunkId]
            val embedding = bytesToFloatArray(it[Embeddings.embedding])
            chunkId to embedding
        }
    }
    
    fun getChunkWithDocument(chunkId: Long): Pair<Chunk, Document>? = org.jetbrains.exposed.sql.transactions.transaction {
        val chunkRow = Chunks.select { Chunks.id eq chunkId }.firstOrNull() ?: return@transaction null
        val docId = chunkRow[Chunks.documentId]
        val docRow = Documents.select { Documents.id eq docId }.firstOrNull() ?: return@transaction null
        
        val chunk = Chunk(
            id = chunkRow[Chunks.id].value,
            documentId = chunkRow[Chunks.documentId],
            chunkIndex = chunkRow[Chunks.chunkIndex],
            content = chunkRow[Chunks.content],
            startChar = chunkRow[Chunks.startChar],
            endChar = chunkRow[Chunks.endChar],
            tokenCount = chunkRow[Chunks.tokenCount],
            createdAt = chunkRow[Chunks.createdAt]
        )
        
        val document = Document(
            id = docRow[Documents.id].value,
            filePath = docRow[Documents.filePath],
            fileName = docRow[Documents.fileName],
            fileSize = docRow[Documents.fileSize],
            lastModified = docRow[Documents.lastModified],
            contentHash = docRow[Documents.contentHash],
            indexedAt = docRow[Documents.indexedAt],
            fileType = docRow[Documents.fileType]
        )
        
        chunk to document
    }
    
    fun deleteDocument(documentId: Long) = org.jetbrains.exposed.sql.transactions.transaction {
        // Каскадное удаление через foreign key constraints
        Documents.deleteWhere { Documents.id eq documentId }
    }
    
    fun getStats(): Map<String, Any> = org.jetbrains.exposed.sql.transactions.transaction {
        val docCount = Documents.selectAll().count().toInt()
        val chunkCount = Chunks.selectAll().count().toInt()
        val embeddingCount = Embeddings.selectAll().count().toInt()
        
        mapOf(
            "documents" to docCount,
            "chunks" to chunkCount,
            "embeddings" to embeddingCount
        )
    }
    
    fun clearAll() = org.jetbrains.exposed.sql.transactions.transaction {
        Embeddings.deleteAll()
        Chunks.deleteAll()
        Documents.deleteAll()
    }
    
    private fun floatArrayToBytes(floatArray: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floatArray.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        floatArray.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
    
    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatArray = FloatArray(bytes.size / 4)
        for (i in floatArray.indices) {
            floatArray[i] = buffer.getFloat()
        }
        return floatArray
    }
}

