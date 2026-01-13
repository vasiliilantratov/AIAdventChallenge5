package org.example.model

data class Embedding(
    val chunkId: Long,
    val embedding: FloatArray,
    val modelName: String,
    val dimension: Int,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Embedding

        if (chunkId != other.chunkId) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (modelName != other.modelName) return false
        if (dimension != other.dimension) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + modelName.hashCode()
        result = 31 * result + dimension
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

