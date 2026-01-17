package org.example.indexing

import java.io.*
import java.security.MessageDigest

/**
 * Класс для потокового чтения файлов без загрузки всего содержимого в память
 */
class StreamingFileReader {
    
    private val bufferSize = 8192 // 8KB буфер для чтения
    
    /**
     * Потоковое вычисление хеша файла без загрузки всего содержимого в память.
     * Использует буферизованное чтение для эффективной обработки больших файлов.
     */
    fun calculateHashStreaming(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        
        file.inputStream().buffered(bufferSize).use { input ->
            val buffer = ByteArray(bufferSize)
            var bytesRead: Int
            
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

