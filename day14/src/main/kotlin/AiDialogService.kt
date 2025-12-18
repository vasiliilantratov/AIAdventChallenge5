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
    private val fileSaverMcpClient: FileSaverMcpClient? = null
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
    private var lastRequestUsedWeatherTool: Boolean = false

    init {
        resetHistory()
    }

    fun sendUserMessage(text: String): ChatSessionResult {
        // Сохраняем сообщение пользователя в БД
        messageDatabase.saveMessage("user", text)

        // Загружаем все сообщения из БД для отправки в ИИ
        val messages = messageDatabase.getAllMessages().toMutableList()
        
        // Получаем доступные инструменты из MCP сервера
        val tools = mcpClientManager?.convertToolsForOllama()

        // Сбрасываем флаг перед новым запросом
        lastRequestUsedWeatherTool = false

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
            
            // Выполняем tool call через MCP
            val toolResult = kotlinx.coroutines.runBlocking {
                try {
                    val argsJson = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(arguments)
                    val result = mcpClientManager?.callTool(toolName, argsJson) ?: "Ошибка: MCP клиент не доступен"
                    
                    // Логируем tool call
                    val isError = result.startsWith("Ошибка")
                    ApiLogger.logToolCall(toolName, arguments, result, isError)
                    
                    if (toolName == "get_current_weather" && !result.startsWith("Ошибка")) {
                        lastRequestUsedWeatherTool = true
                    }

                    result
                } catch (e: Exception) {
                    val errorMessage = "Ошибка парсинга аргументов: ${e.message}"
                    ApiLogger.logToolCall(toolName, arguments, errorMessage, isError = true)
                    errorMessage
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

        // Если в этом запросе использовался инструмент погоды, запрашиваем советы по одежде и сохраняем их в файл
        if (lastRequestUsedWeatherTool && fileSaverMcpClient != null) {
            try {
                val clothingAdvice = requestClothingAdvice(assistantText)
                if (clothingAdvice != null && clothingAdvice.isNotBlank()) {
                    println("\n👕 Рекомендации по одежде:")
                    println(clothingAdvice)

                    // Сохраняем рекомендации в файл через новый MCP сервер
                    kotlinx.coroutines.runBlocking {
                        val saveResult = fileSaverMcpClient.saveText(
                            content = buildString {
                                appendLine("=== Рекомендации по одежде ===")
                                appendLine()
                                appendLine("Время: ${java.time.LocalDateTime.now()}")
                                appendLine()
                                appendLine("Описание погоды (ответ модели):")
                                appendLine(assistantText)
                                appendLine()
                                appendLine("Рекомендации по одежде:")
                                appendLine(clothingAdvice)
                            },
                            filename = null
                        )
                        println("\n💾 Сохранение рекомендаций: $saveResult")
                    }
                }
            } catch (e: Exception) {
                println("⚠ Не удалось получить или сохранить рекомендации по одежде: ${e.message}")
            }
        }

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
     * Дополнительный запрос к ИИ: на основе описания погоды дать рекомендации по одежде
     */
    private fun requestClothingAdvice(weatherDescription: String): String? {
        val prompt = """
            Пользователь запросил погоду, и модель уже вернула следующее описание:
            
            $weatherDescription
            
            На основе этой информации составь краткие, но практичные рекомендации по одежде для человека,
            который собирается выйти на улицу. Укажи:
            - стоит ли брать зонт или дождевик;
            - нужна ли шапка, перчатки, шарф;
            - какой верх (куртка, пальто, пуховик, худи и т.п.) и какой низ лучше выбрать;
            - есть ли риски перегрева или переохлаждения.
            
            Ответь 2–4 абзацами на русском языке.
        """.trimIndent()

        val messages = listOf(
            ChatMessage(role = "system", content = "Ты — стилист и консультант по одежде. Даешь практичные советы на каждый день."),
            ChatMessage(role = "user", content = prompt)
        )

        return when (val result = apiClient.sendChatRequest(currentModel.id, messages)) {
            is ApiCallResult.Success -> {
                result.response.message?.content?.takeIf { it.isNotBlank() }
            }
            is ApiCallResult.NetworkError -> {
                println("Ошибка сети при запросе рекомендаций по одежде: ${result.message}")
                null
            }
            is ApiCallResult.HttpError -> {
                println("HTTP-ошибка при запросе рекомендаций по одежде: ${result.statusCode}")
                null
            }
            is ApiCallResult.JsonError -> {
                println("JSON-ошибка при запросе рекомендаций по одежде: ${result.errorMessage}")
                null
            }
            is ApiCallResult.UnknownError -> {
                println("Неизвестная ошибка при запросе рекомендаций по одежде: ${result.message}")
                null
            }
        }
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

