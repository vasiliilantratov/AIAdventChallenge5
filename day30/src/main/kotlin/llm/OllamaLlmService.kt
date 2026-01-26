package org.example.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

interface LlmService {
    suspend fun generateAnswer(systemPrompt: String, userMessage: String, retries: Int = 3): String
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class ChatResponseMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    @SerialName("message")
    val message: ChatResponseMessage
)

class OllamaLlmService(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "llama3.1:8b",
    private val logFile: Path = Path.of("llm-requests.log")
) : LlmService {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300000 // 5 минут
            connectTimeoutMillis = 30000 // 30 секунд
            socketTimeoutMillis = 300000 // 5 минут
        }
    }

    override suspend fun generateAnswer(systemPrompt: String, userMessage: String, retries: Int): String {
        var lastException: Exception? = null

        repeat(retries) { attempt ->
            try {
                val response = client.post("$baseUrl/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        ChatRequest(
                            model = model,
                            messages = listOf(
                                ChatMessage(role = "system", content = systemPrompt),
                                ChatMessage(role = "user", content = userMessage)
                            ),
                            stream = false
                        )
                    )
                }

                if (response.status.isSuccess()) {
                    val raw = response.body<String>()

                    // Логируем сырой ответ целиком (все строки NDJSON)
                    logCall(systemPrompt, userMessage, null, null, raw)

                    // Ollama /api/chat в стриминговом режиме возвращает NDJSON:
                    // каждая строка — JSON с полем message.content (инкрементальный кусок ответа).
                    // Собираем все content по порядку.
                    val json = Json { ignoreUnknownKeys = true }
                    val accumulated = StringBuilder()

                    raw
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { line ->
                            val chatResponse = json.decodeFromString(ChatResponse.serializer(), line)
                            accumulated.append(chatResponse.message.content)
                        }

                    val answer = accumulated.toString().trim()
                    logCall(systemPrompt, userMessage, answer, null, raw)
                    return answer
                } else {
                    val error = Exception("Ollama chat API returned status ${response.status}")
                    logCall(systemPrompt, userMessage, null, error, null)
                    throw error
                }
            } catch (e: Exception) {
                lastException = e
                logCall(systemPrompt, userMessage, null, e, null)
                if (attempt < retries - 1) {
                    delay((1000L * (attempt + 1)))
                }
            }
        }

        val finalError = Exception("Failed to get LLM answer after $retries retries", lastException)
        logCall(systemPrompt, userMessage, null, finalError, null)
        throw finalError
    }

    fun close() {
        client.close()
    }

    private fun logCall(
        systemPrompt: String,
        userMessage: String,
        answer: String?,
        error: Exception?,
        rawResponse: String?
    ) {
        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            val truncatedSystem = systemPrompt.take(2000)
            val truncatedUser = userMessage.take(2000)
            val truncatedAnswer = answer?.take(4000)
            val truncatedRaw = rawResponse?.take(8000)

            val builder = StringBuilder()
            builder.appendLine("==== LLM CALL [$timestamp] ====")
            builder.appendLine("Model: $model")
            builder.appendLine("--- System prompt ---")
            builder.appendLine(truncatedSystem)
            builder.appendLine("--- User message ---")
            builder.appendLine(truncatedUser)

            if (truncatedAnswer != null) {
                builder.appendLine("--- Answer ---")
                builder.appendLine(truncatedAnswer)
            }

            if (truncatedRaw != null) {
                builder.appendLine("--- Raw response ---")
                builder.appendLine(truncatedRaw)
            }

            if (error != null) {
                builder.appendLine("--- Error ---")
                builder.appendLine(error.toString())
            }

            builder.appendLine()

            val bytes = builder.toString().toByteArray(Charsets.UTF_8)
            Files.write(
                logFile,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (_: Exception) {
            // Не даём логированию уронить основную логику
        }
    }
}


