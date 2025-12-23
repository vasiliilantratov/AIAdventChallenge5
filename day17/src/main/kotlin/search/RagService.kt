package org.example.search

import org.example.embedding.OllamaEmbeddingService
import org.example.llm.LlmService
import org.example.model.Chunk
import org.example.model.SearchResult

data class RagAnswer(
    val question: String,
    val contextChunks: List<Chunk>,
    val answer: String
)

interface RagService {
    suspend fun answerWithRag(question: String, topK: Int = 5): RagAnswer
    suspend fun answerWithoutRag(question: String): String
}

class RagServiceImpl(
    private val semanticSearch: SemanticSearch,
    private val embeddingService: OllamaEmbeddingService,
    private val llmService: LlmService
) : RagService {

    override suspend fun answerWithRag(question: String, topK: Int): RagAnswer {
        // Используем существующий семантический поиск для получения релевантных чанков
        val searchResults: List<SearchResult> = semanticSearch.search(question, topK)
        val chunks = searchResults.map { it.chunk }

        val contextText = buildContext(chunks)

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
            contextChunks = chunks,
            answer = answer
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


