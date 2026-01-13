package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.example.config.AssistantConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.indexing.ProjectIndexer
import org.example.llm.OllamaLlmService
import org.example.mcp.McpClient
import org.example.search.LlmReranker
import org.example.search.RagServiceImpl
import org.example.search.SemanticSearch

class ReviewCommand : CliktCommand(name = "review", help = "Ревью последнего коммита с использованием RAG и MCP") {
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama").default(AssistantConfig.defaultOllamaUrl)
    private val topK by option("--top-k", help = "Количество релевантных чанков из базы знаний для контекста").default("5")

    override fun run() = runBlocking {
        println("🔍 Анализ последнего коммита...")
        println()

        val mcp = McpClient()
        
        // Получаем информацию о последнем коммите
        val commitInfo = try {
            mcp.gitLastCommitInfo()
        } catch (e: Exception) {
            println("❌ Ошибка при получении информации о коммите: ${e.message}")
            println("Убедитесь, что в репозитории есть хотя бы один коммит.")
            return@runBlocking
        }

        println("📝 Информация о коммите:")
        println(commitInfo)
        println()

        // Получаем список измененных файлов
        val changedFiles = try {
            mcp.gitChangedFilesLastCommit()
        } catch (e: Exception) {
            println("❌ Ошибка при получении списка измененных файлов: ${e.message}")
            return@runBlocking
        }

        if (changedFiles.isEmpty()) {
            println("ℹ️  В последнем коммите нет измененных файлов.")
            return@runBlocking
        }

        println("📁 Измененные файлы (${changedFiles.size}):")
        changedFiles.forEachIndexed { index, file ->
            println("  ${index + 1}. $file")
        }
        println()

        // Получаем diff последнего коммита
        val diff = try {
            mcp.gitDiffLastCommit()
        } catch (e: Exception) {
            println("❌ Ошибка при получении diff: ${e.message}")
            return@runBlocking
        }

        if (diff.isBlank()) {
            println("ℹ️  Diff пуст или не удалось получить изменения.")
            return@runBlocking
        }

        println("📊 Получен diff (${diff.length} символов)")
        println()

        // Инициализируем RAG для получения контекста из базы знаний
        println("🔎 Поиск релевантного контекста в базе знаний...")
        ProjectIndexer.ensureIndexed(ollamaUrl = ollamaUrl, dbPath = AssistantConfig.dbPath)
        DatabaseManager.initialize(AssistantConfig.dbPath)

        val repository = Repository()
        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val semanticSearch = SemanticSearch(repository, embeddingService)
        val llmService = OllamaLlmService(ollamaUrl)
        val ragService = RagServiceImpl(
            semanticSearch = semanticSearch,
            embeddingService = embeddingService,
            llmService = llmService,
            reranker = LlmReranker(llmService)
        )

        // Формируем запрос для RAG на основе измененных файлов и diff
        val ragQuery = buildRagQuery(changedFiles, diff)
        
        val ragAnswer = ragService.answerWithRag(
            question = ragQuery,
            topK = topK.toInt(),
            enableReranking = true,
            relevanceThreshold = 0.3f,
            rerankTopK = topK.toInt() * 2
        )

        val contextFromRag = if (ragAnswer.contextChunks.isNotEmpty()) {
            ragAnswer.contextChunks.joinToString("\n\n") { it.content }
        } else {
            "Релевантный контекст из базы знаний не найден."
        }

        println("✅ Найдено ${ragAnswer.contextChunks.size} релевантных фрагментов из базы знаний")
        if (ragAnswer.sources.isNotEmpty()) {
            println("📚 Источники контекста:")
            ragAnswer.sources.forEachIndexed { index, source ->
                println("  ${index + 1}. ${source.documentPath}")
            }
        }
        println()

        // Формируем промпт для ревью
        val reviewPrompt = buildReviewPrompt(commitInfo, changedFiles, diff, contextFromRag)

        println("🤖 Генерация ревью кода...")
        println()

        val systemPrompt = """
            Ты — опытный code reviewer, который проводит ревью кода.
            
            Твоя задача:
            1. Проанализировать изменения в коде (diff)
            2. Использовать контекст из базы знаний проекта (документация, стиль кода, best practices)
            3. Выявить потенциальные проблемы:
               - Ошибки и баги
               - Нарушения стиля кода и соглашений проекта
               - Проблемы с производительностью
               - Проблемы с безопасностью
               - Проблемы с архитектурой
               - Отсутствие обработки ошибок
               - Проблемы с тестированием
            4. Предложить улучшения и альтернативные решения
            5. Отметить хорошие практики, если они есть
            
            Формат ответа:
            - Начни с краткого резюме изменений
            - Затем перечисли замечания по категориям (критичные, важные, рекомендации)
            - Для каждого замечания укажи файл и строки (если возможно)
            - Предложи конкретные исправления
            - В конце укажи общую оценку и рекомендации
            
            Отвечай на русском языке, структурированно и конкретно.
        """.trimIndent()

        val review = llmService.generateAnswer(systemPrompt, reviewPrompt)

        // Выводим ревью
        println("=".repeat(80))
        println("📋 РЕВЬЮ КОДА")
        println("=".repeat(80))
        println()
        println(review)
        println()
        println("=".repeat(80))

        // Закрываем ресурсы
        embeddingService.close()
        llmService.close()
    }

    private fun buildRagQuery(changedFiles: List<String>, diff: String): String {
        // Формируем запрос для RAG на основе измененных файлов
        val fileNames = changedFiles.joinToString(", ")
        
        // Извлекаем ключевые слова из diff (имена функций, классов, переменных)
        val keywords = extractKeywordsFromDiff(diff)
        
        return buildString {
            append("Изменения в файлах: $fileNames. ")
            if (keywords.isNotEmpty()) {
                append("Ключевые элементы: ${keywords.joinToString(", ")}. ")
            }
            append("Нужна информация о стиле кода, соглашениях проекта, best practices, документации API и архитектуре для этих файлов.")
        }
    }

    private fun extractKeywordsFromDiff(diff: String): List<String> {
        // Простое извлечение ключевых слов: имена функций, классов, переменных
        val keywords = mutableSetOf<String>()
        
        // Ищем паттерны типа: function name, class name, const/let/var name
        val functionPattern = Regex("(?:function|def|fun|fn)\\s+([a-zA-Z_][a-zA-Z0-9_]*)")
        val classPattern = Regex("(?:class|interface|type|struct)\\s+([a-zA-Z_][a-zA-Z0-9_]*)")
        val constPattern = Regex("(?:const|let|var|val)\\s+([a-zA-Z_][a-zA-Z0-9_]*)")
        
        functionPattern.findAll(diff).forEach { keywords.add(it.groupValues[1]) }
        classPattern.findAll(diff).forEach { keywords.add(it.groupValues[1]) }
        constPattern.findAll(diff).take(5).forEach { keywords.add(it.groupValues[1]) }
        
        return keywords.take(10).toList() // Ограничиваем количество
    }

    private fun buildReviewPrompt(
        commitInfo: String,
        changedFiles: List<String>,
        diff: String,
        contextFromRag: String
    ): String {
        return buildString {
            appendLine("Информация о коммите:")
            appendLine(commitInfo)
            appendLine()
            
            appendLine("Измененные файлы:")
            changedFiles.forEachIndexed { index, file ->
                appendLine("  ${index + 1}. $file")
            }
            appendLine()
            
            appendLine("=== DIFF последнего коммита ===")
            appendLine(diff)
            appendLine()
            
            appendLine("=== Контекст из базы знаний проекта ===")
            appendLine(contextFromRag)
            appendLine()
            
            appendLine("Проведи ревью этого кода, используя контекст из базы знаний проекта.")
        }
    }
}
