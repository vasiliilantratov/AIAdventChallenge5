package org.example

sealed class ChatSessionResult {
    data class Success(val reply: String, val usage: Usage?) : ChatSessionResult()
    data class Error(val message: String) : ChatSessionResult()
}

class ChatSession(
    private val apiClient: ChatApiClient,
    initialModel: ModelOption,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    var currentModel: ModelOption = initialModel
        private set

    private val messages: MutableList<ChatMessage> = mutableListOf()

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

        return when (val result = apiClient.sendChatRequest(currentModel.id, messages)) {
            is ApiCallResult.Success -> handleSuccess(result.response)
            is ApiCallResult.NetworkError -> ChatSessionResult.Error("Ошибка сети: ${result.message}. Попробуйте ещё раз.")
            is ApiCallResult.HttpError -> ChatSessionResult.Error("Ошибка API: HTTP ${result.statusCode}. Текст ответа: ${result.bodySnippet}")
            ApiCallResult.JsonError -> ChatSessionResult.Error("Ошибка обработки ответа API (JSON). Возможно, формат ответа отличается от ожидаемого.")
            is ApiCallResult.UnknownError -> ChatSessionResult.Error("Неизвестная ошибка: ${result.message}")
        }
    }

    fun clearHistory() {
        resetHistory()
    }

    fun changeModel(newModel: ModelOption) {
        currentModel = newModel
        resetHistory()
    }

    private fun handleSuccess(response: ChatResponse): ChatSessionResult {
        val assistantText = response.choices.firstOrNull()?.message?.content
            ?.takeIf { it.isNotBlank() }
            ?: "Ответ модели отсутствует."

        messages += ChatMessage(role = "assistant", content = assistantText)
        updateSessionUsage(response.usage)

        return ChatSessionResult.Success(assistantText, response.usage)
    }

    private fun resetHistory() {
        messages.clear()
        messages += ChatMessage(role = "system", content = systemPrompt)
    }

    private fun updateSessionUsage(usage: Usage?) {
        usage ?: return
        sessionPromptTokens += usage.prompt_tokens?.toLong() ?: 0
        sessionCompletionTokens += usage.completion_tokens?.toLong() ?: 0
        sessionTotalTokens += usage.total_tokens?.toLong() ?: 0
    }
}

