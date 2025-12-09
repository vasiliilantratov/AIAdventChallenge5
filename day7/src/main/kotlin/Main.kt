package org.example

import kotlin.system.exitProcess

fun main() {
    val baseUrl = "https://router.huggingface.co/v1"
    val apiKey = "TODO"

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
