package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
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

class HelpCommand : CliktCommand(name = "help", help = "Помощь по проекту через RAG и MCP") {
    private val questionParts by argument("question", help = "Вопрос к ассистенту").multiple()
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama").default(AssistantConfig.defaultOllamaUrl)

    override fun run() = runBlocking {
        if (questionParts.isEmpty()) {
            printIntro()
            return@runBlocking
        }

        val question = questionParts.joinToString(" ").trim()
        
        // LLM принимает решение о том, какие инструменты использовать
        val llmService = OllamaLlmService(ollamaUrl)
        val toolsDecision = decideTool(llmService, question)
        
        answerWithTools(question, toolsDecision, llmService)
        
        llmService.close()
    }

    private fun printIntro() {
        println("Ассистент разработчика по текущему проекту.")
        println("Что умею: RAG по документации, чтение репозитория через безопасные MCP-команды (ветка, статус, diff, поиск, чтение файлов).")
        println()
        println("Примеры:")
        println("  /help как запустить проект?")
        println("  /help какие переменные окружения нужны?")
        println("  /help где в коде реализован эндпоинт /login?")
        println("  /help что изменено в текущей ветке?")
        println()
        println("Источники: README/документация проекта, style/линтер конфиги, openapi/swagger, schema/migrations, а также живое содержимое репозитория через MCP.")
    }

    private suspend fun decideTool(llmService: OllamaLlmService, question: String): ToolsDecision {
        val systemPrompt = """
            Ты — ассистент разработчика. Твоя задача определить, какие инструменты нужны для ответа на вопрос пользователя.
            
            Доступные инструменты:
            1. RAG — поиск по документации проекта (README, docs, API specs, schemas, style guides)
            2. GIT_INFO — получение информации о текущей ветке и статусе изменений в git
            3. CODE_SEARCH — поиск по коду проекта (файлы, функции, классы)
            
            Ответь СТРОГО в формате:
            RAG: yes/no
            GIT_INFO: yes/no
            CODE_SEARCH: yes/no
            KEYWORD: <ключевое слово для поиска или null>
            
            Примеры:
            - "как запустить проект?" → RAG: yes, GIT_INFO: no, CODE_SEARCH: no, KEYWORD: null
            - "что изменено в текущей ветке?" → RAG: no, GIT_INFO: yes, CODE_SEARCH: no, KEYWORD: null
            - "где реализован эндпоинт /login?" → RAG: no, GIT_INFO: no, CODE_SEARCH: yes, KEYWORD: login
            - "какие переменные окружения и в каких файлах используются?" → RAG: yes, GIT_INFO: no, CODE_SEARCH: yes, KEYWORD: env
        """.trimIndent()

        val userMessage = "Вопрос: $question"
        
        val response = llmService.generateAnswer(systemPrompt, userMessage)
        
        // Парсим ответ LLM
        val useRag = response.contains("RAG: yes", ignoreCase = true)
        val useGitInfo = response.contains("GIT_INFO: yes", ignoreCase = true)
        val useCodeSearch = response.contains("CODE_SEARCH: yes", ignoreCase = true)
        
        val keywordRegex = Regex("KEYWORD:\\s*(.+)", RegexOption.IGNORE_CASE)
        val keywordMatch = keywordRegex.find(response)
        val keyword = keywordMatch?.groupValues?.get(1)?.trim()?.takeIf { 
            it != "null" && it.isNotEmpty() 
        }
        
        return ToolsDecision(
            useRag = useRag,
            useGitInfo = useGitInfo,
            useCodeSearch = useCodeSearch,
            searchKeyword = keyword
        )
    }

    private suspend fun answerWithTools(
        question: String,
        decision: ToolsDecision,
        llmService: OllamaLlmService
    ) {
        val contextParts = mutableListOf<String>()
        val sources = mutableListOf<String>()
        
        // 1. RAG — поиск по документации
        if (decision.useRag) {
            ProjectIndexer.ensureIndexed(ollamaUrl = ollamaUrl, dbPath = AssistantConfig.dbPath)
            DatabaseManager.initialize(AssistantConfig.dbPath)
            
            val repository = Repository()
            val embeddingService = OllamaEmbeddingService(ollamaUrl)
            val semanticSearch = SemanticSearch(repository, embeddingService)
            val ragService = RagServiceImpl(
                semanticSearch = semanticSearch,
                embeddingService = embeddingService,
                llmService = llmService,
                reranker = LlmReranker(llmService)
            )
            
            val ragAnswer = ragService.answerWithRag(
                question = question,
                topK = 5,
                enableReranking = false,
                relevanceThreshold = null,
                rerankTopK = null
            )
            
            if (ragAnswer.contextChunks.isNotEmpty()) {
                contextParts.add("=== Документация проекта ===")
                contextParts.add(ragAnswer.contextChunks.joinToString("\n\n") { it.content })
                sources.addAll(ragAnswer.sources.map { "${it.documentPath}" })
            }
            
            embeddingService.close()
        }
        
        // 2. GIT_INFO — информация о ветке и статусе
        if (decision.useGitInfo) {
            val mcp = McpClient()
            val branch = safeCall { mcp.gitBranch() }
            val status = safeCall { mcp.gitStatus() }
            
            contextParts.add("=== Git информация ===")
            contextParts.add("Текущая ветка: ${branch ?: "неизвестно"}")
            contextParts.add("Статус: ${if (status.isNullOrBlank()) "нет изменений" else "\n$status"}")
        }
        
        // 3. CODE_SEARCH — поиск по коду
        if (decision.useCodeSearch) {
            val mcp = McpClient()
            val keyword = decision.searchKeyword ?: extractKeyword(question)
            
            if (keyword != null) {
                val hits = safeCall { mcp.search(keyword, ".") } ?: emptyList()
                
                if (hits.isNotEmpty()) {
                    contextParts.add("=== Результаты поиска по коду (ключевое слово: '$keyword') ===")
                    hits.take(3).forEach { hit ->
                        val lines = safeCall { mcp.readFile(hit.file) } ?: emptyList()
                        val snippet = buildSnippet(lines, hit.line, context = 3)
                        contextParts.add("Файл: ${hit.file}:${hit.line}")
                        contextParts.add(snippet)
                        sources.add(hit.file)
                    }
                }
            }
        }
        
        // Если нет контекста, сообщаем об этом
        if (contextParts.isEmpty()) {
            println("Не удалось найти релевантную информацию.")
            println("Попробуй переформулировать вопрос или уточнить детали.")
            return
        }
        
        // 4. Формируем финальный ответ через LLM
        val fullContext = contextParts.joinToString("\n\n")
        
        val finalSystemPrompt = """
            Ты — ассистент разработчика по проекту.
            Отвечай кратко (3-10 предложений), конкретно и по делу.
            Используй только предоставленную информацию.
            Если информации недостаточно — честно скажи об этом.
            Отвечай на русском языке.
        """.trimIndent()
        
        val finalUserMessage = """
            Вопрос: $question
            
            Доступная информация:
            $fullContext
        """.trimIndent()
        
        val finalAnswer = llmService.generateAnswer(finalSystemPrompt, finalUserMessage)
        
        // Выводим результат
        println(finalAnswer)
        println()
        
        if (sources.isNotEmpty()) {
            println("Источники:")
            sources.distinct().forEachIndexed { idx, source ->
                println("  ${idx + 1}. $source")
            }
            println()
        }
        
        println("Следующие шаги:")
        when {
            decision.useCodeSearch -> println("  - Открой найденные файлы для детального изучения кода")
            decision.useRag -> println("  - Уточни вопрос для более детального ответа")
            decision.useGitInfo -> println("  - Используй git diff для просмотра изменений")
        }
        if (!decision.useRag && !decision.useCodeSearch) {
            println("  - Попробуй поиск по документации или коду для получения дополнительной информации")
        }
    }

    private fun extractKeyword(question: String): String? {
        val tokens = question
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}_/]+"))
            .filter { it.length >= 3 }
        return tokens.maxByOrNull { it.length }
    }

    private fun buildSnippet(lines: List<String>, centerLine: Int, context: Int): String {
        if (lines.isEmpty()) return "(файл пуст или не прочитан)"
        val start = (centerLine - context - 1).coerceAtLeast(0)
        val end = (centerLine + context - 1).coerceAtMost(lines.lastIndex)
        val builder = StringBuilder()
        for (i in start..end) {
            val lineNumber = i + 1
            builder.append(String.format("%4d | %s%n", lineNumber, lines[i]))
        }
        return builder.toString()
    }

    private inline fun <T> safeCall(block: () -> T): T? =
        try { block() } catch (_: Exception) { null }
}

private data class CodeSnippet(
    val path: String,
    val line: Int,
    val content: String
)

private data class ToolsDecision(
    val useRag: Boolean,
    val useGitInfo: Boolean,
    val useCodeSearch: Boolean,
    val searchKeyword: String?
)
