package org.example

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class ApiCallResult {
    data class Success(val response: OllamaChatResponse) : ApiCallResult()
    data class NetworkError(val message: String) : ApiCallResult()
    data class HttpError(val statusCode: Int, val bodySnippet: String) : ApiCallResult()
    data class JsonError(val errorMessage: String, val responseBody: String) : ApiCallResult()
    data class UnknownError(val message: String) : ApiCallResult()
}

class OllamaChatClient(
    private val baseUrl: String = "http://localhost:11434",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    /**
     * Парсит NDJSON ответ от Ollama (несколько JSON объектов, разделенных переносами строк)
     * Собирает полный контент из всех частей и возвращает финальный ответ
     */
    private fun parseOllamaResponse(responseBody: String): OllamaChatResponse {
        val lines = responseBody.lines().filter { it.isNotBlank() }
        
        if (lines.isEmpty()) {
            throw IllegalArgumentException("Пустой ответ от Ollama")
        }
        
        var fullContent = StringBuilder()
        var lastResponse: OllamaChatResponse? = null
        var modelName: String? = null
        var toolCalls: List<ToolCall>? = null
        
        // Парсим каждую строку как отдельный JSON объект
        for ((index, line) in lines.withIndex()) {
            try {
                val response = json.decodeFromString<OllamaChatResponse>(line)
                lastResponse = response
                modelName = response.model ?: modelName
                
                // Собираем контент из всех частей
                response.message?.content?.let { content ->
                    if (content.isNotEmpty()) {
                        fullContent.append(content)
                    }
                }
                
                // Сохраняем tool_calls если они есть
                if (response.message?.toolCalls != null && response.message.toolCalls.isNotEmpty()) {
                    toolCalls = response.message.toolCalls
                }
                
                // Если это последний объект (done: true), можем остановиться
                if (response.done) {
                    break
                }
            } catch (e: Exception) {
                // Пропускаем некорректные строки, но логируем с уровнем детализации
                ApiLogger.logError("Ошибка парсинга строки NDJSON #${index + 1}: ${e.message}. Строка: ${line.take(200)}", e)
            }
        }
        
        // Возвращаем финальный ответ с собранным контентом и tool_calls
        val finalMessage = if (lastResponse?.message != null) {
            OllamaMessage(
                role = lastResponse.message.role,
                content = if (fullContent.isEmpty() && toolCalls != null) null else fullContent.toString(),
                toolCalls = toolCalls
            )
        } else {
            OllamaMessage(
                role = "assistant", 
                content = fullContent.toString(),
                toolCalls = null
            )
        }
        
        return lastResponse?.copy(
            model = modelName,
            message = finalMessage,
            done = true
        ) ?: throw IllegalArgumentException("Не удалось распарсить ответ от Ollama")
    }

    fun sendChatRequest(model: String, messages: List<ChatMessage>, tools: List<Map<String, Any>>? = null): ApiCallResult {
        return try {
            val requestUrl = "$baseUrl/api/chat"
            val body = json.encodeToString(
                OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    tools = tools
                )
            )

            // Логируем запрос
            val requestHeaders = mapOf(
                "Content-Type" to "application/json"
            )
            ApiLogger.logRequest(
                url = requestUrl,
                method = "POST",
                headers = requestHeaders,
                body = body
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            // Логируем ответ
            val responseHeaders = response.headers().map().toMap()
            val responseBody = response.body()
            ApiLogger.logResponse(
                statusCode = response.statusCode(),
                headers = responseHeaders,
                body = responseBody
            )

            if (response.statusCode() !in 200..299) {
                val snippet = responseBody.orEmpty().take(500)
                ApiLogger.logError("HTTP Error: ${response.statusCode()}")
                return ApiCallResult.HttpError(response.statusCode(), snippet)
            }

            // Пытаемся сначала распарсить как один JSON объект (правильный режим при stream: false)
            // Если не получается, парсим как NDJSON (fallback для совместимости)
            val parsed = try {
                // Сначала пробуем распарсить как один JSON объект
                try {
                    val singleResponse = json.decodeFromString<OllamaChatResponse>(responseBody.trim())
                    // Если это действительно один объект и done: true, возвращаем его
                    if (singleResponse.done) {
                        singleResponse
                    } else {
                        // Если done: false, значит это NDJSON - парсим построчно
                        parseOllamaResponse(responseBody)
                    }
                } catch (e: Exception) {
                    // Если не получилось распарсить как один объект, пробуем NDJSON
                    parseOllamaResponse(responseBody)
                }
            } catch (e: Exception) {
                ApiLogger.logError("JSON parsing error: ${e.message}", e)
                return ApiCallResult.JsonError(
                    errorMessage = e.message ?: "Неизвестная ошибка парсинга",
                    responseBody = responseBody.take(1000)
                )
            }

            ApiCallResult.Success(parsed)
        } catch (e: HttpTimeoutException) {
            ApiLogger.logError("HTTP Timeout: ${e.message}", e)
            ApiCallResult.NetworkError("Timeout: ${e.message}")
        } catch (e: HttpConnectTimeoutException) {
            ApiLogger.logError("HTTP Connect Timeout: ${e.message}", e)
            ApiCallResult.NetworkError("Timeout: ${e.message}")
        } catch (e: ConnectException) {
            ApiLogger.logError("Connection refused to $baseUrl", e)
            ApiCallResult.NetworkError("Не удалось подключиться к Ollama. Убедитесь, что Ollama запущена на $baseUrl")
        } catch (e: UnknownHostException) {
            ApiLogger.logError("Unknown host: ${e.message}", e)
            ApiCallResult.NetworkError("Неизвестный хост: ${e.message}")
        } catch (e: IOException) {
            ApiLogger.logError("I/O error: ${e.message}", e)
            ApiCallResult.NetworkError("Ошибка ввода-вывода: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ApiLogger.logError("Interrupted: ${e.message}", e)
            ApiCallResult.NetworkError("Прервано: ${e.message}")
        } catch (e: Exception) {
            ApiLogger.logError("Unknown error: ${e.message}", e)
            ApiCallResult.UnknownError("Неизвестная ошибка: ${e.message}")
        }
    }
}
