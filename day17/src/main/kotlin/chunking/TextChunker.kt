package org.example.chunking

data class ChunkConfig(
    val chunkSize: Int = 512,
    val overlapSize: Int = 50,
    val strategy: ChunkStrategy = ChunkStrategy.CHARACTERS
)

enum class ChunkStrategy {
    CHARACTERS,  // По символам
    TOKENS       // По токенам (если доступен токенайзер)
}

class TextChunker(private val config: ChunkConfig) {
    
    fun chunk(text: String): List<ChunkInfo> {
        return when (config.strategy) {
            ChunkStrategy.CHARACTERS -> chunkByCharacters(text)
            ChunkStrategy.TOKENS -> chunkByCharacters(text) // Упрощенная версия, можно улучшить с токенайзером
        }
    }
    
    private fun chunkByCharacters(text: String): List<ChunkInfo> {
        if (text.isEmpty()) return emptyList()
        
        val chunks = mutableListOf<ChunkInfo>()
        var startIndex = 0
        var chunkIndex = 0
        
        while (startIndex < text.length) {
            val endIndex = minOf(startIndex + config.chunkSize, text.length)
            val chunkText = text.substring(startIndex, endIndex)
            
            chunks.add(
                ChunkInfo(
                    content = chunkText,
                    startChar = startIndex,
                    endChar = endIndex,
                    chunkIndex = chunkIndex
                )
            )
            
            // Перемещаемся с учетом overlap
            startIndex = endIndex - config.overlapSize
            if (startIndex >= text.length) break
            if (startIndex < 0) startIndex = 0
            chunkIndex++
        }
        
        return chunks
    }
}

data class ChunkInfo(
    val content: String,
    val startChar: Int,
    val endChar: Int,
    val chunkIndex: Int
)

