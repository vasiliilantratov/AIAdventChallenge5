package org.example

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ApiLogger {
    private val logFile = File("ollama_api.log")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    init {
        // Создаем файл, если его нет
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }
    
    fun logRequest(url: String, method: String, headers: Map<String, String>, body: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val logEntry = buildString {
            appendLine("=".repeat(80))
            appendLine("REQUEST [$timestamp]")
            appendLine("=".repeat(80))
            appendLine("URL: $url")
            appendLine("Method: $method")
            appendLine("Headers:")
            headers.forEach { (key, value) ->
                appendLine("  $key: $value")
            }
            appendLine("Body:")
            appendLine(body)
            appendLine()
        }
        
        logFile.appendText(logEntry)
    }
    
    fun logResponse(statusCode: Int, headers: Map<String, List<String>>, body: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val logEntry = buildString {
            appendLine("=".repeat(80))
            appendLine("RESPONSE [$timestamp]")
            appendLine("=".repeat(80))
            appendLine("Status Code: $statusCode")
            appendLine("Headers:")
            headers.forEach { (key, values) ->
                values.forEach { value ->
                    appendLine("  $key: $value")
                }
            }
            appendLine("Body:")
            appendLine(body)
            appendLine()
        }
        
        logFile.appendText(logEntry)
    }
    
    fun logError(error: String, exception: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val logEntry = buildString {
            appendLine("=".repeat(80))
            appendLine("ERROR [$timestamp]")
            appendLine("=".repeat(80))
            appendLine("Error: $error")
            if (exception != null) {
                appendLine("Exception: ${exception.javaClass.simpleName}")
                appendLine("Message: ${exception.message}")
                appendLine("Stack Trace:")
                exception.stackTrace.take(10).forEach { element ->
                    appendLine("  $element")
                }
            }
            appendLine()
        }
        
        logFile.appendText(logEntry)
    }
}
