package org.example.indexing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.chunking.ChunkConfig
import org.example.chunking.StreamingTextChunker
import org.example.chunking.TextChunker
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.model.Chunk
import org.example.model.Document
import org.example.model.Embedding
import java.io.File
import java.time.Instant

class DocumentIndexer(
    private val repository: Repository,
    private val embeddingService: OllamaEmbeddingService,
    private val chunkConfig: ChunkConfig = ChunkConfig(),
    private val useStreaming: Boolean = true // Всегда использовать потоковое чтение
) {
    
    // Порог размера файла для использования потокового чтения (0 = всегда использовать)
    private val streamingThreshold = 0L
    
    suspend fun indexDirectory(directoryPath: String, onProgress: ((Int, Int) -> Unit)? = null) {
        val scanner = FileScanner()
        val files = scanner.scanDirectory(directoryPath)
        
        var processed = 0
        files.forEach { fileInfo ->
            try {
                indexFile(fileInfo)
                processed++
                onProgress?.invoke(processed, files.size)
            } catch (e: Exception) {
                println("Error indexing file ${fileInfo.path}: ${e.message}")
                e.printStackTrace()
                // Продолжаем обработку других файлов
            }
        }
    }
    
    suspend fun indexFile(fileInfo: FileInfo) {
        val file = File(fileInfo.path)
        val streamingReader = StreamingFileReader()
        
        // Вычисляем хеш потоково
        val contentHash = streamingReader.calculateHashStreaming(file)
        
        // Проверяем, нужно ли индексировать
        val existingDoc = repository.findDocumentByPath(fileInfo.path)
        val needsIndexing = when {
            existingDoc == null -> true
            existingDoc.contentHash != contentHash -> true
            existingDoc.lastModified != fileInfo.lastModified -> true
            else -> false
        }
        
        if (!needsIndexing) {
            return // Файл уже проиндексирован и не изменился
        }
        
        // Если документ существует, удаляем старые данные
        existingDoc?.id?.let { repository.deleteDocument(it) }
        
        // Создаем новый документ
        val now = Instant.now().epochSecond
        val document = Document(
            filePath = fileInfo.path,
            fileName = fileInfo.name,
            fileSize = fileInfo.size,
            lastModified = fileInfo.lastModified,
            contentHash = contentHash,
            indexedAt = now,
            fileType = fileInfo.extension
        )
        
        val documentId = repository.saveDocument(document)
        
        // Всегда используем потоковое чтение для экономии памяти
        val chunks = if (useStreaming) {
            // Потоковое чтение - не загружает весь файл в память
            indexFileStreaming(file, documentId, now)
        } else {
            // Обычное чтение (только если явно отключено потоковое чтение)
            indexFileNormal(file, documentId, now)
        }
        
        val chunkIds = repository.saveChunks(chunks)
        
        // Получаем эмбеддинги для всех чанков
        val embeddings = coroutineScope {
            chunks.mapIndexed { index, chunk ->
                async {
                    val embeddingVector = embeddingService.getEmbedding(chunk.content)
                    Embedding(
                        chunkId = chunkIds[index],
                        embedding = embeddingVector,
                        modelName = "nomic-embed-text:latest",
                        dimension = embeddingVector.size,
                        createdAt = now
                    )
                }
            }.awaitAll()
        }
        
        repository.saveEmbeddings(embeddings)
    }
    
    /**
     * Обычная индексация для маленьких файлов (загружает весь файл в память)
     */
    private fun indexFileNormal(file: File, documentId: Long, now: Long): List<Chunk> {
        val content = file.readText(Charsets.UTF_8)
        val chunker = TextChunker(chunkConfig)
        val chunkInfos = chunker.chunk(content)
        
        return chunkInfos.map { chunkInfo ->
            Chunk(
                documentId = documentId,
                chunkIndex = chunkInfo.chunkIndex,
                content = chunkInfo.content,
                startChar = chunkInfo.startChar,
                endChar = chunkInfo.endChar,
                createdAt = now
            )
        }
    }
    
    /**
     * Потоковая индексация для больших файлов (не загружает весь файл в память)
     */
    private fun indexFileStreaming(file: File, documentId: Long, now: Long): List<Chunk> {
        val streamingChunker = StreamingTextChunker(chunkConfig)
        val chunks = mutableListOf<Chunk>()
        
        // Читаем файл потоково и обрабатываем чанки по мере чтения
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            streamingChunker.chunkStreaming(reader).forEach { chunkInfo ->
                chunks.add(
                    Chunk(
                        documentId = documentId,
                        chunkIndex = chunkInfo.chunkIndex,
                        content = chunkInfo.content,
                        startChar = chunkInfo.startChar,
                        endChar = chunkInfo.endChar,
                        createdAt = now
                    )
                )
            }
        }
        
        return chunks
    }
}

