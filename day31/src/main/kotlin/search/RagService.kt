package org.example.search

import org.example.embedding.OllamaEmbeddingService
import org.example.llm.LlmService
import org.example.model.Chunk
import org.example.model.RerankedResult
import org.example.model.SearchResult
import org.example.model.SourceInfo

data class RagAnswer(
    val question: String,
    val contextChunks: List<Chunk>,
    val answer: String,
    val stats: RagStats? = null,
    val sources: List<SourceInfo> = emptyList() // Источники данных для ответа
)

data class RagStats(
    val initialResultsCount: Int,
    val afterPreFilterCount: Int? = null,
    val afterRerankingCount: Int? = null,
    val afterFilterCount: Int,
    val finalResultsCount: Int,
    val rerankingEnabled: Boolean,
    val filteringEnabled: Boolean
)

interface RagService {
    suspend fun answerWithRag(
        question: String,
        topK: Int = 5,
        enableReranking: Boolean = false,
        relevanceThreshold: Float? = null,
        rerankTopK: Int? = null
    ): RagAnswer
    suspend fun answerWithoutRag(question: String): String
}

class RagServiceImpl(
    private val semanticSearch: SemanticSearch,
    private val embeddingService: OllamaEmbeddingService,
    private val llmService: LlmService,
    private val reranker: Reranker? = null,
    private val relevanceFilter: RelevanceFilter = ThresholdRelevanceFilter()
) : RagService {

    override suspend fun answerWithRag(
        question: String,
        topK: Int,
        enableReranking: Boolean,
        relevanceThreshold: Float?,
        rerankTopK: Int?
    ): RagAnswer {
        // Этап 1: SemanticSearch - получаем больше результатов для реранкинга
        val searchTopK = if (enableReranking && rerankTopK != null) {
            rerankTopK
        } else {
            topK
        }
        val initialResults: List<SearchResult> = semanticSearch.search(question, searchTopK)
        val initialCount = initialResults.size

        // Этап 2: Предварительная фильтрация по similarity (если включен реранкинг)
        // Отфильтровываем заведомо нерелевантные результаты перед реранкингом
        val preFilteredResults = if (enableReranking && relevanceThreshold != null) {
            // Используем более мягкий порог для предварительной фильтрации (например, 0.1)
            // чтобы не тратить время на совсем нерелевантные чанки
            val preFilterThreshold = minOf(relevanceThreshold * 0.5f, 0.1f)
            relevanceFilter.filter(initialResults, preFilterThreshold)
        } else {
            initialResults
        }
        val afterPreFilterCount = if (enableReranking) preFilteredResults.size else null

        // Этап 3: Реранкинг (если включен)
        val rerankedResults: List<RerankedResult>? = if (enableReranking && reranker != null) {
            reranker.rerank(question, preFilteredResults)
        } else {
            null
        }
        val afterRerankingCount = rerankedResults?.size

        // Этап 4: Фильтрация по порогу релевантности
        val (filteredReranked: List<RerankedResult>?, filteredSearch: List<SearchResult>?) = 
            if (relevanceThreshold != null) {
                if (rerankedResults != null) {
                    // Фильтруем по rerankScore
                    Pair(relevanceFilter.filterReranked(rerankedResults, relevanceThreshold), null)
                } else {
                    // Фильтруем по similarity
                    Pair(null, relevanceFilter.filter(preFilteredResults, relevanceThreshold))
                }
            } else {
                if (rerankedResults != null) {
                    Pair(rerankedResults, null)
                } else {
                    Pair(null, preFilteredResults)
                }
            }
        
        val afterFilterCount = filteredReranked?.size ?: filteredSearch?.size ?: 0

        // Этап 5: Берем topK лучших результатов
        val finalResultsWithDocs = if (filteredReranked != null) {
            // Если был реранкинг, берем topK по rerankScore
            filteredReranked.take(topK)
        } else {
            // Если реранкинга не было, берем topK по similarity
            filteredSearch?.take(topK)?.map { 
                RerankedResult(it, it.similarity) 
            } ?: emptyList()
        }
        val finalCount = finalResultsWithDocs.size
        
        // Извлекаем чанки для контекста
        val finalChunks = finalResultsWithDocs.map { it.chunk }
        
        // Собираем информацию об источниках (уникальные документы)
        val sources = finalResultsWithDocs
            .map { it.document }
            .distinctBy { it.id }
            .map { doc ->
                // Находим первый чанк из этого документа в финальных результатах
                val chunk = finalResultsWithDocs.firstOrNull { it.document.id == doc.id }?.chunk
                SourceInfo(
                    documentPath = doc.filePath,
                    documentName = doc.fileName,
                    documentType = doc.fileType,
                    chunkIndex = chunk?.chunkIndex ?: 0
                )
            }

        // Собираем статистику
        val stats = RagStats(
            initialResultsCount = initialCount,
            afterPreFilterCount = afterPreFilterCount,
            afterRerankingCount = afterRerankingCount,
            afterFilterCount = afterFilterCount,
            finalResultsCount = finalCount,
            rerankingEnabled = enableReranking,
            filteringEnabled = relevanceThreshold != null
        )

        val contextText = buildContext(finalChunks)

        val systemPrompt = """
            Ты — ассистент, который отвечает на вопросы, используя только предоставленные фрагменты документов.
            Если в контексте нет ответа на вопрос, честно скажи об этом.
            Отвечай по-русски, чётко и по делу.
        """.trimIndent()

        val userMessage = buildString {
            appendLine("Вопрос:")
            appendLine(question)
            appendLine()
            appendLine("Контекст:")
            appendLine(contextText)
        }.trimEnd()

        val answer = llmService.generateAnswer(systemPrompt, userMessage)

        return RagAnswer(
            question = question,
            contextChunks = finalChunks,
            answer = answer,
            stats = stats,
            sources = sources
        )
    }

    override suspend fun answerWithoutRag(question: String): String {
        val systemPrompt = """
            Ты — умный ассистент. Отвечай на вопросы пользователя по-русски, максимально понятно и структурированно.
        """.trimIndent()

        val userMessage = buildString {
            appendLine("Вопрос:")
            appendLine(question)
        }.trimEnd()

        return llmService.generateAnswer(systemPrompt, userMessage)
    }

    private fun buildContext(chunks: List<Chunk>): String {
        if (chunks.isEmpty()) return "Контекст не найден."

        return chunks.joinToString(separator = "\n\n----\n\n") { chunk ->
            chunk.content
        }
    }
}


