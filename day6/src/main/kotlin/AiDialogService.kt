package org.example

class AiDialogService(private val chatClient: OllamaChatClient) {
    /**
     * Отправляет пользовательское сообщение и возвращает ответ модели.
     * Никаких дополнительных промптов или анализов не добавляется.
     */
    fun sendUserMessage(message: String): AiResponse {
        if (message.isBlank()) return AiResponse.Error("Сообщение не должно быть пустым.")

        return try {
            val response = chatClient.sendMessage(message)
            if (response.startsWith("Ошибка")) {
                AiResponse.Error(response)
            } else {
                AiResponse.Reply(response.trim())
            }
        } catch (e: Exception) {
            AiResponse.Error("Ошибка при обращении к ИИ: ${e.message}")
        }
    }

    /**
     * Очищает историю общения с моделью.
     */
    fun reset() {
        chatClient.clearHistory()
    }
}

