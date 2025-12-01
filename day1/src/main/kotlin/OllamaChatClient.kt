package org.example

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Клиент для работы с локальной моделью Ollama через OpenAI-совместимый API
 */
class OllamaChatClient(
    private val baseUrl: String = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434/v1",
    private val apiKey: String = System.getenv("OLLAMA_API_KEY") ?: "ollama",
    private val modelName: String = System.getenv("OLLAMA_MODEL") ?: "llama3.1:8b" // "llama3"
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    private val chatHistory = mutableListOf<ChatMessage>()
    
    @Serializable
    data class ChatMessage(
        val role: String,
        val content: String
    )
    
    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.7,
        val max_tokens: Int = 512
    )
    
    @Serializable
    data class ChatCompletionResponse(
        val choices: List<Choice>
    )
    
    @Serializable
    data class Choice(
        val message: ChatMessage
    )
    
    init {
        // Добавляем системное сообщение в историю
        val systemMessage = ChatMessage(
            role = "system",
            content = "Ты дружелюбный терминальный помощник. Отвечай кратко и по делу."
        )
        chatHistory.add(systemMessage)
    }
    
    /**
     * Отправляет сообщение пользователя модели и возвращает ответ
     */
    fun sendMessage(userText: String): String {
        try {
            // Добавляем сообщение пользователя в историю
            val userMessage = ChatMessage(role = "user", content = userText)
            chatHistory.add(userMessage)
            
            // Создаем запрос к модели
            val request = ChatCompletionRequest(
                model = modelName,
                messages = chatHistory,
                temperature = 0.7,
                max_tokens = 512
            )
            
            // Сериализуем запрос в JSON
            val requestBody = json.encodeToString(request)
            
            // Создаем HTTP запрос
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofMinutes(5))
                .build()
            
            // Отправляем запрос и получаем ответ
            val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            
            if (httpResponse.statusCode() != 200) {
                return "Ошибка HTTP: ${httpResponse.statusCode()}, ${httpResponse.body()}"
            }
            
            // Парсим ответ
            val response = json.decodeFromString<ChatCompletionResponse>(httpResponse.body())
            
            // Извлекаем текст ответа
            val assistantMessage = response.choices.firstOrNull()?.message
            val responseText = assistantMessage?.content ?: "Извините, не удалось получить ответ от модели."
            
            // Добавляем ответ модели в историю
            if (assistantMessage != null) {
                chatHistory.add(assistantMessage)
            }
            
            return responseText
            
        } catch (e: Exception) {
            return "Ошибка при обращении к модели: ${e.message}"
        }
    }
    
    /**
     * Возвращает имя используемой модели
     */
    fun getModelName(): String = modelName
    
    /**
     * Очищает историю чата (оставляет только системное сообщение)
     */
    fun clearHistory() {
        val systemMessage = chatHistory.firstOrNull { it.role == "system" }
        chatHistory.clear()
        if (systemMessage != null) {
            chatHistory.add(systemMessage)
        }
    }
}
