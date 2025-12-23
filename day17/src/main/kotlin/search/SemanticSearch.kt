package org.example.search

import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.model.SearchResult

class SemanticSearch(
    private val repository: Repository,
    private val embeddingService: OllamaEmbeddingService
) {
    
    suspend fun search(query: String, topK: Int = 10): List<SearchResult> {
        // Получаем эмбеддинг запроса
        val queryEmbedding = embeddingService.getEmbedding(query)
        
        // Получаем все эмбеддинги из БД
        val allEmbeddings = repository.findAllEmbeddings()
        
        // Вычисляем сходство для каждого чанка
        val similarities = allEmbeddings.mapNotNull { (chunkId, embedding) ->
            val similarity = SimilarityCalculator.cosineSimilarity(queryEmbedding, embedding)
            val chunkWithDoc = repository.getChunkWithDocument(chunkId)
            
            chunkWithDoc?.let { (chunk, document) ->
                SearchResult(
                    chunk = chunk,
                    document = document,
                    similarity = similarity,
                    content = chunk.content
                ) to similarity
            }
        }
        
        // Сортируем по убыванию сходства и берем топ-K
        return similarities
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }
}

