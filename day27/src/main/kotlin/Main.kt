package org.example

import com.aallam.ktoken.Encoding
import com.aallam.ktoken.Tokenizer
import kotlinx.coroutines.runBlocking

fun main() {
    val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
    val ollamaClient = OllamaChatClient(baseUrl = baseUrl)

    // Инициализируем базу данных
    val messageDatabase = MessageDatabase()
    
    // Загружаем и отображаем последние 50 сообщений
    val lastMessages = messageDatabase.getLastMessages(50)
    if (lastMessages.isNotEmpty()) {
        println("=== Последние сообщения из истории ===")
        lastMessages.forEach { message ->
            val roleLabel = when (message.role) {
                "user" -> "Пользователь"
                "assistant" -> "ИИ"
                "system" -> "Система"
                else -> message.role
            }
            println("[$roleLabel]: ${message.content}")
        }
        println("=====================================\n")
    }

    println("Добро пожаловать в консольный чат с Ollama.")
    println("Используется модель: ${SUPPORTED_MODELS.first().displayName}")
    
    val selectedModel = SUPPORTED_MODELS.first()
    val chatSession = ChatSession(
        apiClient = ollamaClient,
        initialModel = selectedModel,
        messageDatabase = messageDatabase
    )

    println("Введите ваше сообщение (несколько абзацев). Завершите ввод строкой /end. Для выхода используйте /exit.")

    val tokenizer = runBlocking { Tokenizer.of(encoding = Encoding.CL100K_BASE) }

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
                    "/cleardb" -> {
                        messageDatabase.clearHistory()
                        chatSession.clearHistory()
                        println("База данных полностью очищена. История диалога сброшена.")
                        continue
                    }
                    "/model" -> {
                        println("Модель: ${selectedModel.displayName}")
                        continue
                    }
                }
            }
            is UserInput.Message -> {
                val result = chatSession.sendUserMessage(input.text)
                when (result) {
                    is ChatSessionResult.Success -> {
                        println("ИИ: ${result.reply}")
                        printUsage(chatSession, tokenizer)
                    }
                    is ChatSessionResult.Error -> {
                        println(result.message)
                    }
                }
                println("Введите ваше сообщение (или /exit для выхода, /end для отправки):")
            }
        }
    }

    // Закрываем соединение с базой данных
    messageDatabase.close()
    println("Завершение работы. Пока!")
}

private fun printUsage(session: ChatSession, tokenizer: Tokenizer) {
    try {
        val messages = session.getCurrentMessages()
        
        // Подсчет токенов для промпта (все сообщения кроме последнего ответа ассистента)
        val promptMessages = messages.dropLast(1)
        var ktokenPromptCount = 0
        for (message in promptMessages) {
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
        
        // Обновляем счетчики в сессии
        session.updateTokenCounts(ktokenPromptCount.toLong(), ktokenCompletionCount.toLong())
        
        println()
        println("Токены за этот запрос (ktoken):")
        println("  prompt_tokens: $ktokenPromptCount")
        println("  completion_tokens: $ktokenCompletionCount")
        println("  total_tokens: $ktokenTotalCount")
        
        println()
        println("Суммарно за сессию:")
        println("  total_prompt_tokens: ${session.sessionPromptTokens}")
        println("  total_completion_tokens: ${session.sessionCompletionTokens}")
        println("  total_tokens: ${session.sessionTotalTokens}")
    } catch (e: Exception) {
        println()
        println("Ошибка при подсчете токенов (ktoken): ${e.message}")
    }
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
        if (lines.isEmpty() && trimmed in setOf("/exit", "/help", "/clear", "/cleardb", "/model")) {
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
