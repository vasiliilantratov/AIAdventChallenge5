package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.example.config.AssistantConfig
import org.example.llm.OllamaLlmService
import java.io.File

/**
 * Команда для анализа логов с помощью LLM.
 * Читает все файлы логов из папки logsForAnalysis и отправляет их на анализ.
 */
class AnalyzeLogsCommand : CliktCommand(
    name = "analyze-logs",
    help = "Анализ логов с помощью ИИ. Читает логи из папки logsForAnalysis и отвечает на вопрос."
) {
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama").default(AssistantConfig.defaultOllamaUrl)
    private val logsDir by option("--logs-dir", help = "Путь к папке с логами").default("./logsForAnalysis")
    private val questionParts by argument("question", help = "Вопрос для анализа логов").multiple()

    override fun run() = runBlocking {
        if (questionParts.isEmpty()) {
            echo("Ошибка: необходимо указать вопрос для анализа.")
            echo("Пример: analyze-logs \"Какие ошибки встречаются в логах?\"")
            return@runBlocking
        }

        val question = questionParts.joinToString(" ")
        val llmService = OllamaLlmService(ollamaUrl)

        try {
            // Читаем логи из папки
            val logsDirFile = File(logsDir)
            if (!logsDirFile.exists() || !logsDirFile.isDirectory) {
                echo("Ошибка: папка с логами не найдена: $logsDir")
                return@runBlocking
            }

            echo("📂 Чтение логов из папки: $logsDir")
            val logFilesArray = logsDirFile.listFiles { _, name -> name.endsWith(".jsonl") }
            val logFiles = logFilesArray?.sortedBy { it.name }?.toList() ?: emptyList<File>()

            if (logFiles.isEmpty()) {
                echo("⚠️  В папке не найдено файлов с расширением .jsonl")
                return@runBlocking
            }

            echo("Найдено файлов: ${logFiles.size}")
            logFiles.forEach { file ->
                echo("  • ${file.name} (${file.length()} байт)")
            }
            echo()

            // Читаем и парсим логи
            echo("📖 Чтение и парсинг логов...")
            val allLogs = mutableListOf<LogEntry>()
            var totalLines = 0

            for (file in logFiles) {
                try {
                    val fileLogs = readLogFile(file)
                    allLogs.addAll(fileLogs)
                    totalLines += fileLogs.size
                    echo("  ✓ ${file.name}: прочитано ${fileLogs.size} записей")
                } catch (e: Exception) {
                    echo("  ✗ Ошибка при чтении ${file.name}: ${e.message}")
                }
            }

            if (allLogs.isEmpty()) {
                echo("⚠️  Не удалось прочитать ни одной записи из логов")
                return@runBlocking
            }

            echo("Всего прочитано записей: $totalLines")
            echo()

            // Формируем контекст для LLM
            echo("🤖 Отправка запроса к ИИ...")
            val logsContext = formatLogsForAnalysis(allLogs)
            
            val systemPrompt = """
                Ты — эксперт по анализу логов приложений. Твоя задача — проанализировать предоставленные логи и дать КРАТКИЙ, КОНКРЕТНЫЙ ответ ТОЛЬКО на заданный вопрос.
                
                ВАЖНЫЕ ПРАВИЛА:
                - Отвечай ТОЛЬКО на заданный вопрос, ничего больше
                - НЕ давай рекомендаций, советов или общих выводов
                - НЕ перечисляй все найденные проблемы, если вопрос конкретный
                - Используй конкретные цифры, проценты, названия эндпоинтов из логов
                - Если данных недостаточно, скажи это кратко (1 предложение)
                
                Отвечай на русском языке, кратко и по делу. Только факты из логов, без интерпретаций и рекомендаций.
            """.trimIndent()

            val userMessage = """
                Логи для анализа:
                $logsContext
                
                Вопрос: $question
            """.trimIndent()

            try {
                val answer = llmService.generateAnswer(systemPrompt, userMessage)

                echo()
                echo("=".repeat(80))
                echo("📊 Результат анализа:")
                echo("=".repeat(80))
                echo()
                echo(answer)
            } catch (e: Exception) {
                echo()
                echo("❌ Ошибка при обращении к ИИ: ${e.message}")
                echo()
                echo("Убедитесь, что:")
                echo("  • Ollama запущен и доступен по адресу: $ollamaUrl")
                echo("  • Модель llama3.1:8b установлена в Ollama")
                echo()
                echo("Для запуска Ollama используйте: ollama serve")
                echo("Для установки модели: ollama pull llama3.1:8b")
                throw e
            }

        } finally {
            llmService.close()
        }
    }

    /**
     * Читает файл логов в формате JSONL и возвращает список записей.
     */
    private fun readLogFile(file: File): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()
        val json = Json { ignoreUnknownKeys = true }

        file.useLines { lines ->
            lines.forEach { line ->
                try {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val jsonObject = json.parseToJsonElement(trimmed).jsonObject
                        logs.add(parseLogEntry(jsonObject, file.name))
                    }
                } catch (e: Exception) {
                    // Пропускаем некорректные строки
                }
            }
        }

        return logs
    }

    /**
     * Парсит JSON объект в LogEntry.
     */
    private fun parseLogEntry(jsonObject: JsonObject, fileName: String): LogEntry {
        fun getString(key: String): String? {
            return jsonObject[key]?.jsonPrimitive?.contentOrNull
        }
        
        fun getInt(key: String): Int? {
            return jsonObject[key]?.jsonPrimitive?.intOrNull
        }
        
        fun getLong(key: String): Long? {
            return jsonObject[key]?.jsonPrimitive?.longOrNull
        }
        
        return LogEntry(
            timestamp = getString("ts") ?: "",
            level = getString("level")?.uppercase() ?: "UNKNOWN",
            service = getString("service") ?: "",
            message = getString("message") ?: "",
            requestId = getString("request_id"),
            userId = getString("user_id"),
            method = getString("method"),
            path = getString("path"),
            statusCode = getInt("status_code"),
            latencyMs = getLong("latency_ms"),
            errorCode = getString("error_code"),
            rawJson = jsonObject.toString(),
            sourceFile = fileName
        )
    }

    /**
     * Форматирует логи для отправки в LLM.
     * Ограничивает размер, чтобы не превысить лимиты контекста.
     */
    private fun formatLogsForAnalysis(logs: List<LogEntry>): String {
        // Ограничиваем количество логов для анализа (можно настроить)
        val maxLogs = 1000
        val logsToAnalyze = if (logs.size > maxLogs) {
            // Берем первые и последние записи, а также все ERROR
            val errors = logs.filter { it.level == "ERROR" }
            val warnings = logs.filter { it.level == "WARN" }
            val others = logs.filter { it.level !in listOf("ERROR", "WARN") }
            
            val selected = mutableListOf<LogEntry>()
            selected.addAll(errors)
            selected.addAll(warnings.take(100))
            
            val remaining = maxLogs - selected.size
            if (remaining > 0) {
                // Берем первые и последние записи из остальных
                val firstHalf = others.take(remaining / 2)
                val lastHalf = others.takeLast(remaining / 2)
                selected.addAll(firstHalf)
                selected.addAll(lastHalf)
            }
            
            selected.distinctBy { it.rawJson }.sortedBy { it.timestamp }
        } else {
            logs
        }

        val builder = StringBuilder()
        builder.appendLine("Всего записей в логах: ${logs.size}")
        if (logs.size > maxLogs) {
            builder.appendLine("Для анализа выбрано: ${logsToAnalyze.size} записей (все ERROR, часть WARN и репрезентативная выборка остальных)")
            builder.appendLine()
        }

        logsToAnalyze.forEach { log ->
            builder.appendLine("---")
            builder.appendLine("Файл: ${log.sourceFile}")
            builder.appendLine("Время: ${log.timestamp}")
            builder.appendLine("Уровень: ${log.level}")
            builder.appendLine("Сервис: ${log.service}")
            if (log.requestId != null) builder.appendLine("Request ID: ${log.requestId}")
            if (log.userId != null) builder.appendLine("User ID: ${log.userId}")
            if (log.method != null && log.path != null) {
                builder.appendLine("Запрос: ${log.method} ${log.path}")
            }
            if (log.statusCode != null) builder.appendLine("Статус: ${log.statusCode}")
            if (log.latencyMs != null) builder.appendLine("Задержка: ${log.latencyMs} мс")
            if (log.errorCode != null) builder.appendLine("Код ошибки: ${log.errorCode}")
            builder.appendLine("Сообщение: ${log.message}")
            builder.appendLine("Полный JSON: ${log.rawJson}")
        }

        return builder.toString()
    }
}

/**
 * Представление одной записи лога.
 */
data class LogEntry(
    val timestamp: String,
    val level: String,
    val service: String,
    val message: String,
    val requestId: String?,
    val userId: String?,
    val method: String?,
    val path: String?,
    val statusCode: Int?,
    val latencyMs: Long?,
    val errorCode: String?,
    val rawJson: String,
    val sourceFile: String
)
