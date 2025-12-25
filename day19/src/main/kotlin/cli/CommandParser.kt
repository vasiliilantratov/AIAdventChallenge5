package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import kotlinx.coroutines.runBlocking
import org.example.chunking.ChunkConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.llm.OllamaLlmService
import org.example.indexing.DocumentIndexer
import org.example.search.SemanticSearch
import org.example.search.RagServiceImpl
import org.example.search.LlmReranker
import org.example.model.Message
import org.example.model.MessageRole
import org.example.model.SourceInfo
import org.example.model.Conversation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MainCommand : CliktCommand(name = "semantic-search", help = "Semantic search application") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    
    override fun run() {
        // Инициализация БД
        DatabaseManager.initialize(dbPath)
    }
}

class IndexCommand : CliktCommand(name = "index", help = "Index a directory") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    val path by argument("path", help = "Path to directory to index")
    val chunkSize by option("--chunk-size", help = "Chunk size in characters").convert { it.toInt() }.default(512)
    val overlap by option("--overlap", help = "Overlap size in characters").convert { it.toInt() }.default(50)
    
    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val chunkConfig = ChunkConfig(
            chunkSize = chunkSize,
            overlapSize = overlap
        )
        val indexer = DocumentIndexer(repository, embeddingService, chunkConfig)
        
        println("Starting indexing of directory: $path")
        var lastProgress = 0 to 0
        
        indexer.indexDirectory(path) { processed, total ->
            val (lastProcessed, lastTotal) = lastProgress
            if (processed != lastProcessed || total != lastTotal) {
                println("Progress: $processed/$total files indexed")
                lastProgress = processed to total
            }
        }
        
        println("Indexing completed!")
        embeddingService.close()
    }
}

class SearchCommand : CliktCommand(name = "search", help = "Search in indexed documents") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    val query by argument("query", help = "Search query")
    val topK by option("--top-k", help = "Number of results to return").convert { it.toInt() }.default(10)
    
    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val search = SemanticSearch(repository, embeddingService)
        
        println("Searching for: \"$query\"\n")
        
        val results = search.search(query, topK)
        
        if (results.isEmpty()) {
            println("No results found.")
        } else {
            results.forEachIndexed { index, result ->
                println("=".repeat(80))
                println("Result ${index + 1} (similarity: ${String.format("%.4f", result.similarity)})")
                println("File: ${result.document.filePath}")
                println("Type: ${result.document.fileType}")
                println("-".repeat(80))
                println(result.content)
                println()
            }
        }
        
        embeddingService.close()
    }
}

class StatsCommand : CliktCommand(name = "stats", help = "Show index statistics") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    
    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val stats = repository.getStats()
        
        println("Index Statistics:")
        println("  Documents: ${stats["documents"]}")
        println("  Chunks: ${stats["chunks"]}")
        println("  Embeddings: ${stats["embeddings"]}")
    }
}

class ClearCommand : CliktCommand(name = "clear", help = "Clear all indexed data") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    
    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        repository.clearAll()
        println("Index cleared successfully.")
    }
}

class RemoveCommand : CliktCommand(name = "remove", help = "Remove a document from index") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val path by argument("path", help = "Path to file to remove")
    
    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val document = repository.findDocumentByPath(path)
        
        if (document == null) {
            println("Document not found in index: $path")
        } else {
            document.id?.let { repository.deleteDocument(it) }
            println("Document removed from index: $path")
        }
    }
}

class QaCommand : CliktCommand(name = "qa", help = "Ask a question with or without RAG") {
    private val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    private val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    private val mode: String by option(
        "--mode",
        help = "Query mode: plain (no RAG) or rag (with retrieval-augmented generation)"
    )
        .convert { value ->
            when (value.lowercase()) {
                "plain", "rag" -> value.lowercase()
                else -> throw IllegalArgumentException("Invalid --mode value: $value. Allowed values are: plain, rag")
            }
        }
        .default("plain")
    private val topK by option("--top-k", help = "Number of chunks to use for RAG mode")
        .convert { it.toInt() }
        .default(5)
    private val enableRerankingFlag by option(
        "--enable-reranking",
        help = "Enable reranking using LLM (slower but more accurate)"
    )
    private val enableReranking: Boolean get() = enableRerankingFlag != null
    private val relevanceThreshold by option(
        "--relevance-threshold",
        help = "Threshold for filtering results by relevance (0.0-1.0)"
    )
        .convert { it.toFloat() }
    private val rerankTopK by option(
        "--rerank-top-k",
        help = "Number of results to retrieve for reranking (default: topK * 2)"
    )
        .convert { it.toInt() }
    private val questionParts by argument("question", help = "Question to ask the model").multiple()
    private val question: String get() = questionParts.joinToString(" ")

    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)

        val repository = Repository()
        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val semanticSearch = SemanticSearch(repository, embeddingService)
        val llmService = OllamaLlmService(ollamaUrl)
        
        // Создаем или получаем последнюю беседу
        val conversationId = repository.getLastConversation()?.id 
            ?: repository.createConversation()
        
        // Сохраняем вопрос пользователя
        val now = System.currentTimeMillis()
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = question,
            mode = mode,
            createdAt = now
        ))
        
        // Создаем реранкер только если он нужен
        val reranker = if (enableReranking) {
            LlmReranker(llmService)
        } else {
            null
        }
        
        val ragService = RagServiceImpl(
            semanticSearch = semanticSearch,
            embeddingService = embeddingService,
            llmService = llmService,
            reranker = reranker
        )

        when (mode) {
            "plain" -> {
                println("Режим: без RAG")
                println("Вопрос:")
                println(question)
                println()

                val answer = ragService.answerWithoutRag(question)
                println("Ответ:")
                println(answer)
                
                // Сохраняем ответ без источников
                repository.saveMessage(Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = answer,
                    mode = mode,
                    sourcesJson = null,
                    createdAt = System.currentTimeMillis()
                ))
            }

            "rag" -> {
                val rerankTopKValue = rerankTopK ?: (topK * 2)
                
                println("Режим: с RAG")
                println("  top-k: $topK")
                println("  реранкинг: ${if (enableReranking) "включен (rerank-top-k=$rerankTopKValue)" else "выключен"}")
                println("  порог релевантности: ${relevanceThreshold?.let { String.format("%.3f", it) } ?: "не задан"}")
                println()
                println("Вопрос:")
                println(question)
                println()

                val ragAnswer = ragService.answerWithRag(
                    question = question,
                    topK = topK,
                    enableReranking = enableReranking,
                    relevanceThreshold = relevanceThreshold,
                    rerankTopK = if (enableReranking) rerankTopKValue else null
                )
                
                // Выводим статистику
                ragAnswer.stats?.let { stats ->
                    println("Статистика обработки:")
                    println("  Начальных результатов: ${stats.initialResultsCount}")
                    stats.afterPreFilterCount?.let {
                        println("  После предварительной фильтрации: $it")
                    }
                    stats.afterRerankingCount?.let {
                        println("  После реранкинга: $it")
                    }
                    if (stats.filteringEnabled) {
                        println("  После фильтрации по порогу: ${stats.afterFilterCount}")
                    }
                    println("  Финальных результатов: ${stats.finalResultsCount}")
                    println()
                }
                
                println("Ответ:")
                println(ragAnswer.answer)
                
                // Выводим источники, если они есть
                if (ragAnswer.sources.isNotEmpty()) {
                    println()
                    println("Источники:")
                    ragAnswer.sources.forEachIndexed { index, source ->
                        println("  ${index + 1}. ${source.documentName} (${source.documentType})")
                        println("     Путь: ${source.documentPath}")
                    }
                }
                
                // Сохраняем ответ с источниками
                val sourcesJson = if (ragAnswer.sources.isNotEmpty()) {
                    Json.encodeToString(ragAnswer.sources)
                } else {
                    null
                }
                repository.saveMessage(Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = ragAnswer.answer,
                    mode = mode,
                    sourcesJson = sourcesJson,
                    createdAt = System.currentTimeMillis()
                ))
            }
        }

        embeddingService.close()
        llmService.close()
    }
}

class HistoryCommand : CliktCommand(name = "history", help = "Show conversation history") {
    private val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    private val conversationId by option(
        "--conversation-id",
        help = "Show messages from specific conversation ID"
    ).convert { it.toLong() }
    private val limit by option(
        "--limit",
        help = "Number of conversations or messages to show"
    ).convert { it.toInt() }.default(10)
    private val format: String by option(
        "--format",
        help = "Output format: short (default) or full"
    ).default("short")

    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()

        if (conversationId != null) {
            // Показываем сообщения конкретной беседы
            val conversation = repository.getConversation(conversationId!!)
            if (conversation == null) {
                println("Беседа с ID $conversationId не найдена.")
                return
            }

            val messages = repository.getMessages(conversationId!!)
            if (messages.isEmpty()) {
                println("В беседе нет сообщений.")
                return
            }

            println("=".repeat(80))
            println("Беседа #${conversation.id}")
            println("Создана: ${java.time.Instant.ofEpochMilli(conversation.createdAt)}")
            println("Обновлена: ${java.time.Instant.ofEpochMilli(conversation.updatedAt)}")
            println("=".repeat(80))
            println()

            messages.forEach { message ->
                when (message.role) {
                    MessageRole.USER -> {
                        println("👤 Вопрос (${message.mode}):")
                        println(message.content)
                    }
                    MessageRole.ASSISTANT -> {
                        println("🤖 Ответ (${message.mode}):")
                        println(message.content)
                        
                        // Выводим источники, если они есть
                        if (message.sourcesJson != null && message.mode == "rag") {
                            try {
                                val sources = Json.decodeFromString<List<SourceInfo>>(message.sourcesJson)
                                if (sources.isNotEmpty()) {
                                    println()
                                    println("📚 Источники:")
                                    sources.forEachIndexed { index, source ->
                                        println("  ${index + 1}. ${source.documentName} (${source.documentType})")
                                        if (format == "full") {
                                            println("     Путь: ${source.documentPath}")
                                            println("     Индекс чанка: ${source.chunkIndex}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Игнорируем ошибки парсинга JSON
                            }
                        }
                    }
                }
                println()
                println("-".repeat(80))
                println()
            }
        } else {
            // Показываем список бесед
            val conversations = repository.getAllConversations(limit)
            if (conversations.isEmpty()) {
                println("История диалогов пуста.")
                return
            }

            println("История диалогов (последние $limit):")
            println("=".repeat(80))
            println()

            conversations.forEach { conversation ->
                val messages = repository.getMessages(conversation.id!!)
                val userMessages = messages.count { it.role == MessageRole.USER }
                val assistantMessages = messages.count { it.role == MessageRole.ASSISTANT }
                
                println("Беседа #${conversation.id}")
                println("  Создана: ${java.time.Instant.ofEpochMilli(conversation.createdAt)}")
                println("  Обновлена: ${java.time.Instant.ofEpochMilli(conversation.updatedAt)}")
                println("  Сообщений: $userMessages вопросов, $assistantMessages ответов")
                
                if (format == "full" && messages.isNotEmpty()) {
                    println("  Последний вопрос:")
                    val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                    if (lastUserMessage != null) {
                        val preview = lastUserMessage.content.take(100)
                        println("    ${if (preview.length < lastUserMessage.content.length) "$preview..." else preview}")
                    }
                }
                
                println()
                println("-".repeat(80))
                println()
            }
            
            println("Используйте --conversation-id <ID> для просмотра сообщений конкретной беседы")
        }
    }
}

