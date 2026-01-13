package org.example.indexing

import org.example.config.AssistantConfig
import org.example.chunking.ChunkConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import java.io.File

/**
 * Утилита, гарантирующая наличие индекса для целевого проекта.
 * При первом запуске строит индекс и кэширует его в БД, указанной в AssistantConfig.
 */
object ProjectIndexer {
    suspend fun ensureIndexed(
        chunkSize: Int = 512,
        overlap: Int = 50,
        ollamaUrl: String = AssistantConfig.defaultOllamaUrl,
        dbPath: String = AssistantConfig.dbPath,
        additionalExcludePatterns: List<String> = emptyList()
    ) {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val stats = repository.getStats()
        val documentsCount = (stats["documents"] as? Int) ?: 0

        if (documentsCount > 0) return

        // Собираем паттерны исключения: дефолтные + дополнительные
        val allExcludePatterns = mutableListOf<String>()
        allExcludePatterns.addAll(AssistantConfig.defaultExcludePatterns)
        allExcludePatterns.addAll(additionalExcludePatterns)

        // Пытаемся загрузить .gitignore, если он есть
        val gitignorePath = File(AssistantConfig.projectPath, ".gitignore").absolutePath
        val gitignorePatterns = IgnorePattern.fromGitignore(gitignorePath)
        if (gitignorePatterns.isNotEmpty()) {
            println("Найден .gitignore, добавлено ${gitignorePatterns.size} паттернов исключения")
            allExcludePatterns.addAll(gitignorePatterns)
        }

        val ignorePattern = IgnorePattern.from(allExcludePatterns)

        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val chunkConfig = ChunkConfig(chunkSize = chunkSize, overlapSize = overlap)
        val indexer = DocumentIndexer(repository, embeddingService, chunkConfig, ignorePattern = ignorePattern)

        println("Индекс не найден. Запускаю индексирование каталога: ${AssistantConfig.projectPath}")
        if (allExcludePatterns.isNotEmpty()) {
            println("Исключаемые паттерны: ${allExcludePatterns.take(10).joinToString(", ")}${if (allExcludePatterns.size > 10) "..." else ""}")
        }
        indexer.indexDirectory(AssistantConfig.projectPath) { processed, total ->
            println("Прогресс индексации: $processed/$total файлов")
        }
        println("Индексирование завершено.")
        embeddingService.close()
    }
}
