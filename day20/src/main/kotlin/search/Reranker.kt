package org.example.search

import org.example.llm.LlmService
import org.example.model.RerankedResult
import org.example.model.SearchResult
import kotlinx.coroutines.runBlocking

/**
 * Интерфейс для реранкинга результатов поиска
 */
interface Reranker {
    /**
     * Переранжирует результаты поиска, используя более точную оценку релевантности
     * @param query Исходный запрос пользователя
     * @param results Результаты семантического поиска
     * @return Переранжированные результаты с новыми оценками релевантности
     */
    suspend fun rerank(query: String, results: List<SearchResult>): List<RerankedResult>
}

/**
 * Реализация реранкера на основе LLM
 * Использует LLM для оценки релевантности каждого чанка к запросу
 */
class LlmReranker(
    private val llmService: LlmService
) : Reranker {

    override suspend fun rerank(query: String, results: List<SearchResult>): List<RerankedResult> {
        val rerankedResults = mutableListOf<RerankedResult>()

        for (result in results) {
            try {
                val rerankScore = evaluateRelevance(query, result)
                rerankedResults.add(
                    RerankedResult(
                        searchResult = result,
                        rerankScore = rerankScore
                    )
                )
            } catch (e: Exception) {
                // При ошибке реранкинга исключаем чанк (согласно требованиям)
                // Просто не добавляем его в список
                continue
            }
        }

        // Сортируем по убыванию rerankScore
        return rerankedResults.sortedByDescending { it.rerankScore }
    }

    /**
     * Оценивает релевантность чанка к запросу с помощью LLM
     * @return Оценка релевантности от 0.0 до 1.0
     */
    private suspend fun evaluateRelevance(query: String, result: SearchResult): Float {
        val systemPrompt = """
            Ты — эксперт по оценке релевантности текста к запросу.
            Оцени, насколько релевантен предоставленный фрагмент текста к заданному запросу.
            Верни ТОЛЬКО число от 0.0 до 1.0, где:
            - 0.0 — полностью нерелевантно
            - 1.0 — полностью релевантно
            Не добавляй никаких пояснений, только число.
        """.trimIndent()

        val userMessage = buildString {
            appendLine("Запрос: $query")
            appendLine()
            appendLine("Фрагмент текста:")
            appendLine(result.content)
            appendLine()
            appendLine("Оценка релевантности (только число от 0.0 до 1.0):")
        }

        val llmResponse = llmService.generateAnswer(systemPrompt, userMessage)
        return parseRelevanceScore(llmResponse)
    }

    /**
     * Парсит оценку релевантности из ответа LLM
     * Извлекает первое найденное число от 0.0 до 1.0
     */
    private fun parseRelevanceScore(response: String): Float {
        // Убираем пробелы и переносы строк
        val cleaned = response.trim().replace("\n", " ").replace("\r", " ")
        
        // Ищем число с плавающей точкой в диапазоне [0.0, 1.0]
        val regex = Regex("""(?:^|\s)(0\.\d+|1\.0|1|0)(?:\s|$)""")
        val match = regex.find(cleaned)
        
        if (match != null) {
            val value = match.groupValues[1].toFloatOrNull()
            if (value != null && value >= 0.0f && value <= 1.0f) {
                return value
            }
        }
        
        // Если не удалось распарсить, пробуем найти любое число и нормализовать его
        val numberRegex = Regex("""\d+\.?\d*""")
        val numberMatch = numberRegex.find(cleaned)
        if (numberMatch != null) {
            val value = numberMatch.value.toFloatOrNull()
            if (value != null) {
                // Нормализуем к диапазону [0.0, 1.0]
                return when {
                    value > 1.0f -> 1.0f
                    value < 0.0f -> 0.0f
                    else -> value
                }
            }
        }
        
        // Если ничего не найдено, выбрасываем исключение (чанк будет исключен)
        throw IllegalArgumentException("Не удалось распарсить оценку релевантности из ответа: $response")
    }
}

