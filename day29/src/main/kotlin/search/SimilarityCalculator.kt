package org.example.search

object SimilarityCalculator {
    
    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must have the same dimension" }
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        
        val denominator = kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }
    
    fun normalize(vector: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() }.toFloat())
        return if (norm > 0f) {
            vector.map { it / norm }.toFloatArray()
        } else {
            vector
        }
    }
}

