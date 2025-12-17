package org.example

sealed class ChatSessionResult {
    data class Success(val reply: String) : ChatSessionResult()
    data class Error(val message: String) : ChatSessionResult()
}

class ChatSession(
    private val apiClient: OllamaChatClient,
    initialModel: ModelOption,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val summaryThreshold: Int = 10,
    private val messageDatabase: MessageDatabase,
    private val mcpClientManager: McpClientManager? = null,
    private val weatherSubscriptionService: WeatherSubscriptionService? = null
) {
    var currentModel: ModelOption = initialModel
        private set

    var sessionPromptTokens: Long = 0
        private set
    var sessionCompletionTokens: Long = 0
        private set
    var sessionTotalTokens: Long = 0
        private set
    
    private var requestCount: Int = 0

    init {
        resetHistory()
    }

    fun sendUserMessage(text: String): ChatSessionResult {
        // Сохраняем сообщение пользователя в БД
        messageDatabase.saveMessage("user", text)

        // Загружаем все сообщения из БД для отправки в ИИ
        val messages = messageDatabase.getAllMessages().toMutableList()
        
        // Получаем доступные инструменты из MCP сервера и добавляем tool для подписок
        val mcpTools = mcpClientManager?.convertToolsForOllama() ?: emptyList()
        val subscriptionTool = getSubscriptionTool()
        val tools = if (subscriptionTool != null) {
            mcpTools + subscriptionTool
        } else {
            mcpTools
        }

        // Цикл для обработки tool calls
        val maxIterations = 5
        var iteration = 0
        
        while (iteration < maxIterations) {
            iteration++
            requestCount++
            
            val result = when (val apiResult = apiClient.sendChatRequest(currentModel.id, messages, tools)) {
                is ApiCallResult.Success -> {
                    val response = apiResult.response
                    val message = response.message
                    
                    // Проверяем, есть ли tool calls
                    if (message?.toolCalls != null && message.toolCalls.isNotEmpty()) {
                        // Обрабатываем tool calls
                        handleToolCalls(message.toolCalls, messages)
                        continue // Повторяем запрос с результатами tool calls
                    } else {
                        // Обычный ответ без tool calls
                        handleSuccess(response)
                    }
                }
                is ApiCallResult.NetworkError -> ChatSessionResult.Error("Ошибка сети: ${apiResult.message}. Попробуйте ещё раз.")
                is ApiCallResult.HttpError -> ChatSessionResult.Error("Ошибка API: HTTP ${apiResult.statusCode}. Текст ответа: ${apiResult.bodySnippet}")
                is ApiCallResult.JsonError -> ChatSessionResult.Error(
                    "Ошибка обработки ответа API (JSON): ${apiResult.errorMessage}\n" +
                    "Тело ответа: ${apiResult.responseBody.take(500)}"
                )
                is ApiCallResult.UnknownError -> ChatSessionResult.Error("Неизвестная ошибка: ${apiResult.message}")
            }
            
            // После получения ответа проверяем, нужно ли делать summary
            // Проверяем после добавления ответа ассистента (в handleSuccess)
            return result
        }
        
        return ChatSessionResult.Error("Превышено максимальное количество итераций вызова инструментов")
    }
    
    private fun handleToolCalls(toolCalls: List<ToolCall>, currentMessages: MutableList<ChatMessage>) {
        // Добавляем сообщение ассистента с tool calls
        currentMessages.add(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = toolCalls
        ))
        
        println("\n🔧 ИИ вызывает инструменты:")
        
        // Выполняем каждый tool call
        for (toolCall in toolCalls) {
            val toolName = toolCall.function.name
            val arguments = toolCall.function.arguments
            
            println("  - $toolName с аргументами: $arguments")
            
            // Проверяем, является ли это tool call для подписок
            val toolResult = if (toolName == "manage_weather_subscription") {
                handleSubscriptionToolCall(arguments)
            } else {
                // Выполняем tool call через MCP
                kotlinx.coroutines.runBlocking {
                    try {
                        val argsJson = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(arguments)
                        val result = mcpClientManager?.callTool(toolName, argsJson) ?: "Ошибка: MCP клиент не доступен"
                        
                        // Логируем tool call
                        val isError = result.startsWith("Ошибка")
                        ApiLogger.logToolCall(toolName, arguments, result, isError)
                        
                        result
                    } catch (e: Exception) {
                        val errorMessage = "Ошибка парсинга аргументов: ${e.message}"
                        ApiLogger.logToolCall(toolName, arguments, errorMessage, isError = true)
                        errorMessage
                    }
                }
            }
            
            println("  ✓ Результат: ${toolResult.take(200)}${if (toolResult.length > 200) "..." else ""}")
            
            // Добавляем результат tool call как сообщение от tool
            currentMessages.add(ChatMessage(
                role = "tool",
                content = toolResult
            ))
        }
        
        println()
    }
    
    /**
     * Обрабатывает tool call для управления подписками на погоду
     */
    private fun handleSubscriptionToolCall(arguments: String): String {
        if (weatherSubscriptionService == null) {
            return "Ошибка: сервис подписок на погоду не доступен"
        }
        
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val argsJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(arguments)
            
            val action = (argsJson["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val city = (argsJson["city"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val intervalSeconds = (argsJson["interval_seconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            
            when (action) {
                "add" -> {
                    if (city == null) {
                        "Ошибка: не указан город для подписки"
                    } else {
                        weatherSubscriptionService.addSubscription(city, intervalSeconds ?: 60)
                    }
                }
                "remove" -> {
                    if (city == null) {
                        "Ошибка: не указан город для удаления подписки"
                    } else {
                        weatherSubscriptionService.removeSubscription(city)
                    }
                }
                "list" -> {
                    weatherSubscriptionService.listSubscriptions()
                }
                "update_interval" -> {
                    if (city == null || intervalSeconds == null) {
                        "Ошибка: не указан город или интервал для обновления"
                    } else {
                        weatherSubscriptionService.updateInterval(city, intervalSeconds)
                    }
                }
                else -> {
                    "Ошибка: неизвестное действие '$action'. Доступные действия: add, remove, list, update_interval"
                }
            }
        } catch (e: Exception) {
            "Ошибка при обработке команды подписки: ${e.message}"
        }
    }
    
    /**
     * Возвращает определение tool для управления подписками на погоду
     */
    private fun getSubscriptionTool(): Map<String, Any>? {
        if (weatherSubscriptionService == null) {
            return null
        }
        
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "manage_weather_subscription",
                "description" to "Управление подписками на периодические уведомления о погоде. " +
                        "Позволяет добавлять, удалять, просматривать подписки и изменять интервал уведомлений. " +
                        "При добавлении подписки пользователь будет периодически получать уведомления о погоде " +
                        "в указанном городе с рекомендациями по одежде.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to listOf("add", "remove", "list", "update_interval"),
                            "description" to "Действие: 'add' - добавить подписку, 'remove' - удалить подписку, " +
                                    "'list' - показать список подписок, 'update_interval' - изменить интервал уведомлений"
                        ),
                        "city" to mapOf(
                            "type" to "string",
                            "description" to "Название города (например: Moscow, London, New York). " +
                                    "Обязательно для действий 'add', 'remove', 'update_interval'"
                        ),
                        "interval_seconds" to mapOf(
                            "type" to "integer",
                            "description" to "Интервал уведомлений в секундах (по умолчанию 60). " +
                                    "Используется для действий 'add' и 'update_interval'. " +
                                    "Например: 30 для каждых 30 секунд, 60 для каждой минуты, 3600 для каждого часа"
                        )
                    ),
                    "required" to listOf("action")
                )
            )
        )
    }

    fun clearHistory() {
        // Очищаем историю в БД
        messageDatabase.clearHistory()
        resetHistory()
    }

    fun changeModel(newModel: ModelOption) {
        currentModel = newModel
        // Очищаем историю при смене модели
        messageDatabase.clearHistory()
        resetHistory()
    }

    private fun handleSuccess(response: OllamaChatResponse): ChatSessionResult {
        val assistantText = response.message?.content
            ?.takeIf { it.isNotBlank() }
            ?: "Ответ модели отсутствует."

        // Сохраняем ответ ассистента в БД
        messageDatabase.saveMessage("assistant", assistantText)

        // Проверяем, нужно ли делать summary после добавления ответа ассистента
        val messageCount = messageDatabase.getMessageCount()
        if (messageCount >= summaryThreshold) {
            val summaryResult = createSummary()
            if (summaryResult is ChatSessionResult.Error) {
                // Если не удалось создать summary, продолжаем с обычным диалогом
                println("Предупреждение: не удалось создать summary. Продолжаем с обычным диалогом.")
            }
        }

        return ChatSessionResult.Success(assistantText)
    }

    /**
     * Создает summary предыдущих сообщений и заменяет их на summary в истории
     */
    private fun createSummary(): ChatSessionResult {
        // Загружаем все сообщения из БД
        val allMessages = messageDatabase.getAllMessages()
        
        // Находим system сообщение (обычно первое)
        val systemMessage = allMessages.firstOrNull { it.role == "system" }
        if (systemMessage == null) {
            return ChatSessionResult.Error("System сообщение не найдено")
        }

        // Берем сообщения для summary (все кроме system и последних двух сообщений: user и assistant)
        // Последние user и assistant сообщения только что добавлены и их не нужно включать в summary
        val messagesToSummarize = if (allMessages.size >= 3) {
            // Пропускаем system и последние два сообщения
            allMessages.drop(1).dropLast(2)
        } else {
            // Если сообщений недостаточно, берем все кроме system
            allMessages.drop(1)
        }
        
        if (messagesToSummarize.isEmpty()) {
            return ChatSessionResult.Error("Нет сообщений для summary")
        }

        // Формируем промпт для summary
        val conversationText = messagesToSummarize.joinToString("\n") { msg ->
            "${msg.role}: ${msg.content}"
        }
        
        val summaryPrompt = """
            Создай краткое изложение следующего диалога, сохраняя ключевую информацию и контекст:
            
            $conversationText
            
            Краткое изложение:
        """.trimIndent()

        // Создаем запрос для summary (используем только system prompt и summary prompt)
        val summaryMessages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = summaryPrompt)
        )

        return when (val result = apiClient.sendChatRequest(currentModel.id, summaryMessages)) {
            is ApiCallResult.Success -> {
                val summaryText = result.response.message?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: "Не удалось создать summary."

                // Удаляем старые сообщения из БД (кроме system и последних двух)
                messageDatabase.deleteMessagesForSummary()
                
                // Сохраняем summary в БД
                messageDatabase.saveMessage("assistant", summaryText, isSummary = true)

                println("✓ Создан summary предыдущих сообщений")
                ChatSessionResult.Success(summaryText)
            }
            is ApiCallResult.NetworkError -> ChatSessionResult.Error("Ошибка сети при создании summary: ${result.message}")
            is ApiCallResult.HttpError -> ChatSessionResult.Error("Ошибка API при создании summary: HTTP ${result.statusCode}")
            is ApiCallResult.JsonError -> ChatSessionResult.Error("Ошибка обработки ответа при создании summary: ${result.errorMessage}")
            is ApiCallResult.UnknownError -> ChatSessionResult.Error("Неизвестная ошибка при создании summary: ${result.message}")
        }
    }

    private fun resetHistory() {
        // Проверяем, есть ли уже system сообщение в БД
        val existingMessages = messageDatabase.getAllMessages()
        val hasSystemMessage = existingMessages.any { it.role == "system" }
        
        // Если system сообщения нет, добавляем его
        if (!hasSystemMessage) {
            messageDatabase.saveMessage("system", systemPrompt)
        }
    }

    fun updateTokenCounts(promptTokens: Long, completionTokens: Long) {
        sessionPromptTokens += promptTokens
        sessionCompletionTokens += completionTokens
        sessionTotalTokens += promptTokens + completionTokens
        
        // Логируем статистику каждые 5 запросов
        if (requestCount % 5 == 0) {
            ApiLogger.logSessionStats(
                sessionPromptTokens,
                sessionCompletionTokens,
                sessionTotalTokens,
                requestCount
            )
        }
    }

    fun getCurrentMessages(): List<ChatMessage> = messageDatabase.getAllMessages()
    
    /**
     * Выводит статистику сессии
     */
    fun printSessionStats() {
        ApiLogger.logSessionStats(
            sessionPromptTokens,
            sessionCompletionTokens,
            sessionTotalTokens,
            requestCount
        )
    }
}

