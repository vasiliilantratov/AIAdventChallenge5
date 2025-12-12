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
    private val messageDatabase: MessageDatabase
) {
    var currentModel: ModelOption = initialModel
        private set

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
        // Сохраняем сообщение пользователя в БД
        messageDatabase.saveMessage("user", text)

        // Загружаем все сообщения из БД для отправки в ИИ
        val messages = messageDatabase.getAllMessages()

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
    }

    fun getCurrentMessages(): List<ChatMessage> = messageDatabase.getAllMessages()
}

