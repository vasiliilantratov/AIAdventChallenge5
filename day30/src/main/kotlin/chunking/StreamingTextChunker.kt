package org.example.chunking

import java.io.Reader

/**
 * Потоковый чанкер для обработки больших файлов без загрузки всего содержимого в память
 */
class StreamingTextChunker(
    private val config: ChunkConfig
) {
    
    /**
     * Обрабатывает Reader потоково, возвращая чанки по мере чтения
     * Использует скользящее окно для поддержки overlap
     */
    fun chunkStreaming(reader: Reader): Sequence<ChunkInfo> = sequence {
        val readBufferSize = config.chunkSize * 2 // Буфер для чтения
        val buffer = CharArray(readBufferSize)
        var bufferStart = 0 // Начало данных в буфере
        var bufferLength = 0 // Количество данных в буфере
        var globalCharIndex = 0
        var chunkIndex = 0
        
        // Читатель управляется вызывающим кодом, не закрываем его здесь
        while (true) {
            // Читаем данные в буфер
            val bytesRead = reader.read(buffer, bufferLength, buffer.size - bufferLength)
            if (bytesRead == -1) {
                // Конец файла - обрабатываем остаток
                if (bufferLength > bufferStart) {
                    val remaining = String(buffer, bufferStart, bufferLength - bufferStart)
                    if (remaining.isNotEmpty()) {
                        yield(
                            ChunkInfo(
                                content = remaining,
                                startChar = globalCharIndex,
                                endChar = globalCharIndex + remaining.length,
                                chunkIndex = chunkIndex
                            )
                        )
                    }
                }
                break
            }
            
            bufferLength += bytesRead
            
            // Обрабатываем чанки пока в буфере достаточно данных
            while (bufferLength - bufferStart >= config.chunkSize) {
                val chunkText = String(buffer, bufferStart, config.chunkSize)
                
                yield(
                    ChunkInfo(
                        content = chunkText,
                        startChar = globalCharIndex,
                        endChar = globalCharIndex + chunkText.length,
                        chunkIndex = chunkIndex
                    )
                )
                
                // Сдвигаем окно с учетом overlap
                val step = config.chunkSize - config.overlapSize
                bufferStart += step
                globalCharIndex += step
                chunkIndex++
                
                // Если буфер почти пуст, сдвигаем данные в начало
                if (bufferStart > config.chunkSize) {
                    val remaining = bufferLength - bufferStart
                    System.arraycopy(buffer, bufferStart, buffer, 0, remaining)
                    bufferStart = 0
                    bufferLength = remaining
                }
            }
        }
    }
}
