package org.example

import com.aallam.ktoken.Encoding
import com.aallam.ktoken.Tokenizer
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main() {
    val baseUrl = "https://router.huggingface.co/v1"
    val apiKey = throw Exception()

    if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
        println("Ошибка: не найден OPENAI_API_KEY или OPENAI_BASE_URL.")
        println("Установите переменные окружения и перезапустите программу.")
        exitProcess(1)
    }

    println("Добро пожаловать в консольный чат с ИИ.")
    println("Выберите модель для работы:")
    SUPPORTED_MODELS.forEach { println(it.menuTitle) }

    val selectedModel = promptModelSelection()
    val chatSession = ChatSession(
        apiClient = ChatApiClient(baseUrl = baseUrl, apiKey = apiKey),
        initialModel = selectedModel
    )

    println("Вы выбрали модель: ${selectedModel.displayName}.")
    println("Введите ваше сообщение (несколько абзацев). Завершите ввод строкой /end. Для выхода используйте /exit.")

    while (true) {
        when (val input = readUserInput()) {
            null -> break
            is UserInput.Command -> {
                when (input.value) {
                    "/exit" -> break
                    "/help" -> {
                        ConsoleMessages.printHelp()
                        continue
                    }
                    "/clear" -> {
                        chatSession.clearHistory()
                        println("История диалога очищена. Продолжаем с той же моделью.")
                        continue
                    }
                    "/model" -> {
                        println("Выберите модель для работы:")
                        SUPPORTED_MODELS.forEach { println(it.menuTitle) }
                        val newModel = promptModelSelection()
                        chatSession.changeModel(newModel)
                        println("Модель изменена на: ${newModel.displayName}. История диалога сброшена.")
                        continue
                    }
                }
            }
            is UserInput.Message -> {
                val result = chatSession.sendUserMessage(input.text)
                when (result) {
                    is ChatSessionResult.Success -> {
                        println("ИИ: ${result.reply}")
                        printUsage(result.usage, chatSession)
                    }
                    is ChatSessionResult.Error -> {
                        println(result.message)
                    }
                }
                println("Введите ваше сообщение (или /exit для выхода, /end для отправки):")
            }
        }
    }

    println("Завершение работы. Пока!")
}

private fun promptModelSelection(): ModelOption {
    while (true) {
        print("Введите номер модели (1-3): ")
        val input = readlnOrNull()?.trim() ?: continue
        val index = input.toIntOrNull()
        if (index != null && index in 1..SUPPORTED_MODELS.size) {
            return SUPPORTED_MODELS[index - 1]
        }
        println("Некорректный ввод. Пожалуйста, введите число от 1 до 3.")
    }
}

private fun printUsage(usage: Usage?, session: ChatSession) {
    if (usage == null) {
        println("Токены: информация недоступна (usage отсутствует в ответе API).")
        return
    }

    val prompt = usage.prompt_tokens?.toString() ?: "недоступно"
    val completion = usage.completion_tokens?.toString() ?: "недоступно"
    val total = usage.total_tokens?.toString() ?: "недоступно"

    println()
    println("Токены за этот запрос:")
    println("  prompt_tokens: $prompt")
    println("  completion_tokens: $completion")
    println("  total_tokens: $total")

    // Подсчет токенов с помощью ktoken
    try {
        val tokenizer = runBlocking { Tokenizer.of(encoding = Encoding.CL100K_BASE) }
        val messages = session.getCurrentMessages()
        
        // Подсчет токенов для промпта (все сообщения кроме последнего ответа ассистента)
        val promptMessages = messages.dropLast(1) // Все сообщения кроме последнего ответа ассистента
        var ktokenPromptCount = 0
        for (message in promptMessages) {
            // Формат сообщения для подсчета: роль + контент
            val messageText = "${message.role}: ${message.content}"
            ktokenPromptCount += tokenizer.encode(messageText).size
        }
        
        // Подсчет токенов для ответа ассистента (последнее сообщение)
        val assistantMessage = messages.lastOrNull()
        val ktokenCompletionCount = if (assistantMessage != null && assistantMessage.role == "assistant") {
            val messageText = "${assistantMessage.role}: ${assistantMessage.content}"
            tokenizer.encode(messageText).size
        } else {
            0
        }
        
        val ktokenTotalCount = ktokenPromptCount + ktokenCompletionCount
        
        println()
        println("Подсчет токенов (ktoken):")
        println("  prompt_tokens: $ktokenPromptCount")
        println("  completion_tokens: $ktokenCompletionCount")
        println("  total_tokens: $ktokenTotalCount")
    } catch (e: Exception) {
        println()
        println("Ошибка при подсчете токенов (ktoken): ${e.message}")
    }

    println()
    println("Суммарно за сессию:")
    println("  total_prompt_tokens: ${session.sessionPromptTokens}")
    println("  total_completion_tokens: ${session.sessionCompletionTokens}")
    println("  total_tokens: ${session.sessionTotalTokens}")
}

private sealed class UserInput {
    data class Command(val value: String) : UserInput()
    data class Message(val text: String) : UserInput()
}

private fun readUserInput(): UserInput? {
    val lines = mutableListOf<String>()
    while (true) {
        val line = readlnOrNull() ?: return null
        val trimmed = line.trim()

        // Однострочные команды работают, только если введены как единственная строка
        if (lines.isEmpty() && trimmed in setOf("/exit", "/help", "/clear", "/model")) {
            return UserInput.Command(trimmed)
        }

        if (trimmed == "/end") {
            val text = lines.joinToString("\n").trimEnd()
            if (text.isNotBlank()) {
                return UserInput.Message(text)
            }
            // Пустое сообщение — продолжаем запрашивать ввод
            println("Сообщение пустое. Попробуйте ещё раз.")
            lines.clear()
            continue
        }

        lines += line
    }
}
