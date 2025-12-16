package org.example

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ApiLogger {
    private val logFile = File("ollama_api.log")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    // Флаг для включения логирования в консоль (stderr)
    var consoleLogging = true
    
    init {
        // Создаем файл, если его нет
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }
    
    private fun log(message: String, toConsole: Boolean = false) {
        logFile.appendText(message)
        if (toConsole && consoleLogging) {
            System.err.print(message)
        }
    }
    
    fun logRequest(url: String, method: String, headers: Map<String, String>, body: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        
        // Полный лог в файл
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
        log(logEntry)
        
        // Краткий лог в консоль
        val consoleEntry = buildString {
            appendLine("\n[Ollama API] REQUEST [$timestamp]")
            appendLine("  → $method $url")
            
            // Парсим body для показа краткой информации
            try {
                if (body.contains("\"tools\"")) {
                    appendLine("  → С инструментами (tool calling enabled)")
                }
                if (body.contains("\"messages\"")) {
                    val messagesCount = body.split("\"role\"").size - 1
                    appendLine("  → Сообщений в контексте: $messagesCount")
                }
            } catch (e: Exception) {
                // Игнорируем ошибки парсинга
            }
        }
        log(consoleEntry, toConsole = true)
    }
    
    fun logResponse(statusCode: Int, headers: Map<String, List<String>>, body: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        
        // Полный лог в файл
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
        log(logEntry)
        
        // Краткий лог в консоль
        val consoleEntry = buildString {
            appendLine("[Ollama API] RESPONSE [$timestamp]")
            appendLine("  ← Status: $statusCode")
            
            // Парсим body для показа краткой информации
            try {
                if (body.contains("\"tool_calls\"")) {
                    appendLine("  ← Ответ содержит вызовы инструментов (tool calls)")
                }
                if (body.contains("\"done\":true")) {
                    appendLine("  ← Генерация завершена")
                }
                // Показываем длину контента
                if (body.contains("\"content\"")) {
                    val contentStart = body.indexOf("\"content\":\"")
                    if (contentStart > 0) {
                        val contentPreview = body.substring(contentStart + 11, 
                            minOf(contentStart + 61, body.length)).replace("\\n", " ")
                        appendLine("  ← Контент: ${contentPreview}${if (body.length > contentStart + 61) "..." else ""}")
                    }
                }
            } catch (e: Exception) {
                // Игнорируем ошибки парсинга
            }
        }
        log(consoleEntry, toConsole = true)
    }
    
    fun logError(error: String, exception: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        
        // Полный лог в файл
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
        log(logEntry)
        
        // Краткий лог в консоль
        val consoleEntry = buildString {
            appendLine("\n[Ollama API] ERROR [$timestamp]")
            appendLine("  ✗ $error")
            if (exception != null) {
                appendLine("  ✗ ${exception.javaClass.simpleName}: ${exception.message}")
            }
        }
        log(consoleEntry, toConsole = true)
    }
    
    /**
     * Логирование tool call (вызов инструмента)
     */
    fun logToolCall(toolName: String, arguments: String, result: String? = null, isError: Boolean = false) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        
        val logEntry = buildString {
            appendLine("=".repeat(80))
            appendLine("TOOL CALL [$timestamp]")
            appendLine("=".repeat(80))
            appendLine("Tool: $toolName")
            appendLine("Arguments: $arguments")
            if (result != null) {
                appendLine("Result:")
                appendLine(result)
                if (isError) {
                    appendLine("Status: ERROR")
                } else {
                    appendLine("Status: SUCCESS")
                }
            }
            appendLine()
        }
        log(logEntry)
        
        val consoleEntry = buildString {
            appendLine("\n[Tool Call] [$timestamp]")
            appendLine("  🔧 Инструмент: $toolName")
            appendLine("  📝 Аргументы: $arguments")
            if (result != null) {
                val icon = if (isError) "✗" else "✓"
                val preview = result.take(100).replace("\n", " ")
                appendLine("  $icon Результат: $preview${if (result.length > 100) "..." else ""}")
            }
        }
        log(consoleEntry, toConsole = true)
    }
    
    /**
     * Логирование статистики сессии
     */
    fun logSessionStats(
        promptTokens: Long, 
        completionTokens: Long, 
        totalTokens: Long,
        requestCount: Int
    ) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        
        val logEntry = buildString {
            appendLine("=".repeat(80))
            appendLine("SESSION STATISTICS [$timestamp]")
            appendLine("=".repeat(80))
            appendLine("Total Requests: $requestCount")
            appendLine("Prompt Tokens: $promptTokens")
            appendLine("Completion Tokens: $completionTokens")
            appendLine("Total Tokens: $totalTokens")
            appendLine()
        }
        log(logEntry)
    }
}
