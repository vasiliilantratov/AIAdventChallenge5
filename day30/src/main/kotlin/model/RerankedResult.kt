package org.example.model

/**
 * Результат реранкинга, содержащий исходный SearchResult и новую оценку релевантности
 */
data class RerankedResult(
    val searchResult: SearchResult,
    val rerankScore: Float
) {
    val originalSimilarity: Float get() = searchResult.similarity
    val chunk: Chunk get() = searchResult.chunk
    val document: Document get() = searchResult.document
    val content: String get() = searchResult.content
}

