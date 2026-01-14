package org.example.search

import org.example.model.RerankedResult
import org.example.model.SearchResult

/**
 * Интерфейс для фильтрации результатов по релевантности
 */
interface RelevanceFilter {
    /**
     * Фильтрует результаты по порогу релевантности
     * @param results Результаты поиска или реранкинга
     * @param threshold Порог релевантности (0.0 - 1.0)
     * @return Отфильтрованные результаты
     */
    fun filter(results: List<SearchResult>, threshold: Float): List<SearchResult>
    
    /**
     * Фильтрует результаты реранкинга по порогу
     * @param results Результаты реранкинга
     * @param threshold Порог релевантности (0.0 - 1.0)
     * @return Отфильтрованные результаты
     */
    fun filterReranked(results: List<RerankedResult>, threshold: Float): List<RerankedResult>
}

/**
 * Реализация фильтра по порогу релевантности
 */
class ThresholdRelevanceFilter : RelevanceFilter {
    
    override fun filter(results: List<SearchResult>, threshold: Float): List<SearchResult> {
        require(threshold >= 0.0f && threshold <= 1.0f) {
            "Порог должен быть в диапазоне [0.0, 1.0], получено: $threshold"
        }
        
        return results.filter { it.similarity >= threshold }
    }
    
    override fun filterReranked(results: List<RerankedResult>, threshold: Float): List<RerankedResult> {
        require(threshold >= 0.0f && threshold <= 1.0f) {
            "Порог должен быть в диапазоне [0.0, 1.0], получено: $threshold"
        }
        
        return results.filter { it.rerankScore >= threshold }
    }
}

