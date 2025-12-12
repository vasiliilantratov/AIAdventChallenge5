package org.example

sealed class ChatSessionResult {
    data class Success(val reply: String) : ChatSessionResult()
    data class Error(val message: String) : ChatSessionResult()
}

class ChatSession(
    private val apiClient: OllamaChatClient,
    initialModel: ModelOption,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val summaryThreshold: Int = 10
) {
    var currentModel: ModelOption = initialModel
        private set

    private val messages: MutableList<ChatMessage> = mutableListOf()
    
    // Счетчик сообщений для отслеживания необходимости summary
    // Считаем только user и assistant сообщения (не system)
    private var messageCount: Int = 0

    var sessionPromptTokens: Long = 0
        private set
    var sessionCompletionTokens: Long = 0
        private set
    var sessionTotalTokens: Long = 0
        private set

    init {
        resetHistory()
    }

    fun sendUserMessage(text: String): ChatSessionResult {
        messages += ChatMessage(role = "user", content = text)
        messageCount++

        val result = when (val apiResult = apiClient.sendChatRequest(currentModel.id, messages)) {
            is ApiCallResult.Success -> handleSuccess(apiResult.response)
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

    fun clearHistory() {
        resetHistory()
    }

    fun changeModel(newModel: ModelOption) {
        currentModel = newModel
        resetHistory()
    }

    private fun handleSuccess(response: OllamaChatResponse): ChatSessionResult {
        val assistantText = response.message?.content
            ?.takeIf { it.isNotBlank() }
            ?: "Ответ модели отсутствует."

        messages += ChatMessage(role = "assistant", content = assistantText)
        messageCount++

        // Проверяем, нужно ли делать summary после добавления ответа ассистента
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
        // Находим индекс system сообщения (обычно первый)
        val systemIndex = messages.indexOfFirst { it.role == "system" }
        if (systemIndex == -1) {
            return ChatSessionResult.Error("System сообщение не найдено")
        }

        // Берем сообщения для summary (все кроме system и последних двух сообщений: user и assistant)
        // Последние user и assistant сообщения только что добавлены и их не нужно включать в summary
        val messagesToSummarize = if (messages.size >= systemIndex + 3) {
            messages.subList(systemIndex + 1, messages.size - 2)
        } else {
            // Если сообщений недостаточно, берем все кроме system
            messages.subList(systemIndex + 1, messages.size)
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

                // Заменяем старые сообщения на summary
                // Оставляем system сообщение и последние два сообщения (user и assistant)
                val newMessages = mutableListOf<ChatMessage>()
                newMessages += messages[systemIndex] // system сообщение
                newMessages += ChatMessage(role = "assistant", content = "Summary: $summaryText")
                
                // Добавляем последние два сообщения (user и assistant)
                // Они всегда должны быть, так как мы вызываем createSummary после добавления assistant ответа
                if (messages.size >= 2) {
                    newMessages += messages[messages.size - 2] // предпоследнее (user)
                    newMessages += messages[messages.size - 1] // последнее (assistant)
                }

                // Заменяем историю
                messages.clear()
                messages.addAll(newMessages)

                // Сбрасываем счетчик (summary + последние user и assistant = 3 сообщения, но считаем как 2 пары)
                messageCount = 2

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
        messages.clear()
        messages += ChatMessage(role = "system", content = systemPrompt)
        messageCount = 0
    }

    fun updateTokenCounts(promptTokens: Long, completionTokens: Long) {
        sessionPromptTokens += promptTokens
        sessionCompletionTokens += completionTokens
        sessionTotalTokens += promptTokens + completionTokens
    }

    fun getCurrentMessages(): List<ChatMessage> = messages.toList()
}

