package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.config.AssistantConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.llm.OllamaLlmService
import org.example.model.Message
import org.example.model.MessageRole
import org.example.model.UserProfile
import java.io.File
import java.util.Scanner

/**
 * Команда для интерактивного чата с персонализированным агентом.
 * Использует профиль пользователя из personal/user_profile.json для персонализации.
 */
class PersonalizedAgentCommand : CliktCommand(
    name = "agent",
    help = "Интерактивный чат с персонализированным агентом на основе профиля пользователя"
) {
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama").default(AssistantConfig.defaultOllamaUrl)
    private val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    private val profilePath by option("--profile", help = "Path to user profile JSON").default("./personal/user_profile.json")
    private val initialMessageParts by argument(
        "message",
        help = "Начальное сообщение (опционально)"
    ).multiple()

    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val llmService = OllamaLlmService(ollamaUrl)

        // Загружаем профиль пользователя
        val profile = loadUserProfile(profilePath)
        if (profile == null) {
            println("❌ Ошибка: не удалось загрузить профиль пользователя из $profilePath")
            println("   Убедитесь, что файл существует и содержит корректный JSON.")
            llmService.close()
            return@runBlocking
        }

        // Формируем системный промпт на основе профиля
        val systemPrompt = buildSystemPrompt(profile)

        // Создаем или получаем последнюю беседу
        var conversationId = repository.getLastConversation()?.id
            ?: repository.createConversation()

        println("╔══════════════════════════════════════════════════════════════╗")
        println("║         Персонализированный агент - Интерактивный режим      ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()
        println("👤 Пользователь: ${profile.user.name}")
        if (profile.user.language != null) {
            println("🌐 Язык: ${profile.user.language}")
        }
        if (profile.priorities?.currentFocus != null) {
            println("🎯 Текущий фокус: ${profile.priorities.currentFocus}")
        }
        println()
        println("Доступные команды:")
        println("  • Любой вопрос - общайтесь с персонализированным агентом")
        println("  • '/exit', '/quit' - выход из чата")
        println("  • '/clear' - начать новую беседу")
        println("  • '/history' - показать историю текущей беседы")
        println("  • '/profile' - показать текущий профиль")
        println()

        // Если есть начальное сообщение, обработаем его
        if (initialMessageParts.isNotEmpty()) {
            val initialMessage = initialMessageParts.joinToString(" ")
            println("👤 Вы: $initialMessage")
            println()

            val response = processMessage(
                initialMessage,
                conversationId,
                repository,
                llmService,
                systemPrompt
            )
            println("🤖 Ассистент: $response")
            println()
            println("-".repeat(64))
            println()
        }

        // Интерактивный режим
        val scanner = Scanner(System.`in`)

        while (true) {
            print("👤 Вы: ")
            System.out.flush()

            val userInput = try {
                if (!scanner.hasNextLine()) {
                    println()
                    println("⚠️  Интерактивный ввод недоступен.")
                    println()
                    println("Используйте один из способов:")
                    println("  1. Запуск с начальным сообщением:")
                    println("     ./gradlew run --args='agent Привет!'")
                    println()
                    println("  2. Запуск напрямую (после сборки):")
                    println("     ./gradlew installDist")
                    println("     ./build/install/day24/bin/day24 agent")
                    println()
                    break
                }
                scanner.nextLine()?.trim() ?: ""
            } catch (e: NoSuchElementException) {
                println()
                println("Ввод завершен.")
                break
            }

            if (userInput.isEmpty()) {
                continue
            }

            // Обработка специальных команд
            when (userInput.lowercase()) {
                "/exit", "/quit" -> {
                    println()
                    println("Завершение чата. До встречи!")
                    break
                }

                "/clear" -> {
                    conversationId = repository.createConversation()
                    println()
                    println("✨ Начата новая беседа (ID: $conversationId)")
                    println()
                    continue
                }

                "/history" -> {
                    showHistory(conversationId, repository)
                    println()
                    continue
                }

                "/profile" -> {
                    showProfile(profile)
                    println()
                    continue
                }
            }

            // Обработка обычного сообщения
            println()
            val response = processMessage(
                userInput,
                conversationId,
                repository,
                llmService,
                systemPrompt
            )
            println("🤖 Ассистент: $response")
            println()
            println("-".repeat(64))
            println()
        }

        llmService.close()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    private fun loadUserProfile(path: String): UserProfile? {
        return try {
            val file = File(path)
            if (!file.exists()) {
                return null
            }
            val jsonContent = file.readText()
            json.decodeFromString<UserProfile>(jsonContent)
        } catch (e: Exception) {
            println("Ошибка при загрузке профиля: ${e.message}")
            null
        }
    }

    private fun buildSystemPrompt(profile: UserProfile): String {
        val builder = StringBuilder()

        // Базовая информация о пользователе
        builder.appendLine("Ты — персонализированный AI ассистент для пользователя ${profile.user.name}.")
        
        if (profile.user.language != null) {
            builder.appendLine("Язык общения: ${profile.user.language}.")
        }

        // Предпочтения по общению
        profile.preferences.communication.let { comm ->
            if (comm.addressing != null) {
                builder.appendLine("Обращайся к пользователю: ${comm.addressing}.")
            }
            if (comm.tone != null) {
                builder.appendLine("Тон общения: ${comm.tone}.")
            }
            if (comm.verbosity != null) {
                builder.appendLine("Вербальность: ${comm.verbosity}.")
            }
            if (comm.formatPreferences != null && comm.formatPreferences.isNotEmpty()) {
                builder.appendLine("Предпочтительные форматы ответов: ${comm.formatPreferences.joinToString(", ")}.")
            }
            if (comm.avoid != null && comm.avoid.isNotEmpty()) {
                builder.appendLine("Избегай: ${comm.avoid.joinToString(", ")}.")
            }
        }

        // Ограничения по времени
        profile.constraints?.let { constraints ->
            if (constraints.timePerDayMinutes != null) {
                val hours = constraints.timePerDayMinutes / 60
                val minutes = constraints.timePerDayMinutes % 60
                builder.appendLine("У пользователя есть ${hours}ч ${minutes}мин в день на работу.")
            }
            if (constraints.daysPerWeek != null) {
                builder.appendLine("Рабочих дней в неделю: ${constraints.daysPerWeek}.")
            }
        }

        // Цели пользователя
        profile.goals?.main?.let { goals ->
            if (goals.isNotEmpty()) {
                builder.appendLine("Основные цели пользователя:")
                goals.forEach { goal ->
                    builder.appendLine("  - $goal")
                }
            }
        }

        // Приоритеты
        profile.priorities?.let { priorities ->
            if (priorities.currentFocus != null) {
                builder.appendLine("Текущий фокус: ${priorities.currentFocus}.")
            }
            if (priorities.secondary != null && priorities.secondary.isNotEmpty()) {
                builder.appendLine("Вторичные приоритеты: ${priorities.secondary.joinToString(", ")}.")
            }
        }

        // Поведение агента
        profile.agentBehavior?.let { behavior ->
            if (behavior.shouldDo != null && behavior.shouldDo.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("Ты ДОЛЖЕН:")
                behavior.shouldDo.forEach { rule ->
                    builder.appendLine("  - $rule")
                }
            }
            if (behavior.shouldNotDo != null && behavior.shouldNotDo.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("Ты НЕ ДОЛЖЕН:")
                behavior.shouldNotDo.forEach { rule ->
                    builder.appendLine("  - $rule")
                }
            }
        }

        // Предпочтения по обучению
        profile.preferences.learning?.let { learning ->
            if (learning.style != null && learning.style.isNotEmpty()) {
                builder.appendLine("Стиль обучения: ${learning.style.joinToString(", ")}.")
            }
            if (learning.pace != null) {
                builder.appendLine("Темп обучения: ${learning.pace}.")
            }
        }

        builder.appendLine()
        builder.appendLine("Всегда учитывай эти настройки при общении с пользователем.")

        return builder.toString().trim()
    }

    private suspend fun processMessage(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService,
        systemPrompt: String
    ): String {
        val now = System.currentTimeMillis()

        // Сохраняем сообщение пользователя
        repository.saveMessage(
            Message(
                conversationId = conversationId,
                role = MessageRole.USER,
                content = userInput,
                mode = "agent",
                createdAt = now
            )
        )

        // Получаем историю беседы для контекста
        val history = repository.getMessages(conversationId)
        val recentHistory = history.takeLast(10) // Берем последние 10 сообщений для контекста

        // Формируем контекст из истории
        val contextMessages = recentHistory.map { msg ->
            when (msg.role) {
                MessageRole.USER -> "Пользователь: ${msg.content}"
                MessageRole.ASSISTANT -> "Ассистент: ${msg.content}"
            }
        }

        // Формируем пользовательское сообщение с контекстом
        val userMessage = if (contextMessages.size > 2) {
            // Если есть история, добавляем контекст
            buildString {
                if (contextMessages.size > 2) {
                    appendLine("Контекст предыдущих сообщений:")
                    contextMessages.dropLast(1).forEach { msg ->
                        appendLine(msg)
                    }
                    appendLine()
                }
                appendLine("Текущий вопрос пользователя:")
                appendLine(userInput)
            }
        } else {
            userInput
        }

        // Генерируем ответ с использованием персонализированного системного промпта
        val answer = llmService.generateAnswer(systemPrompt, userMessage)

        // Сохраняем ответ
        repository.saveMessage(
            Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = answer,
                mode = "agent",
                sourcesJson = null,
                createdAt = System.currentTimeMillis()
            )
        )

        return answer
    }

    private fun showHistory(conversationId: Long, repository: Repository) {
        val conversation = repository.getConversation(conversationId)
        if (conversation == null) {
            println("Беседа не найдена.")
            return
        }

        val messages = repository.getMessages(conversationId)
        if (messages.isEmpty()) {
            println("В беседе пока нет сообщений.")
            return
        }

        println()
        println("═".repeat(64))
        println("История беседы #$conversationId")
        println("═".repeat(64))
        println()

        messages.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    println("👤 Вы (${message.mode}):")
                    println(message.content)
                }

                MessageRole.ASSISTANT -> {
                    println()
                    println("🤖 Ассистент (${message.mode}):")
                    println(message.content)
                }
            }
            println()
            println("-".repeat(64))
            println()
        }
    }

    private fun showProfile(profile: UserProfile) {
        println()
        println("═".repeat(64))
        println("Профиль пользователя")
        println("═".repeat(64))
        println()
        println("👤 Имя: ${profile.user.name}")
        if (profile.user.timezone != null) {
            println("🕐 Часовой пояс: ${profile.user.timezone}")
        }
        if (profile.user.language != null) {
            println("🌐 Язык: ${profile.user.language}")
        }
        println()
        
        println("💬 Предпочтения по общению:")
        profile.preferences.communication.let { comm ->
            if (comm.addressing != null) {
                println("  Обращение: ${comm.addressing}")
            }
            if (comm.tone != null) {
                println("  Тон: ${comm.tone}")
            }
            if (comm.verbosity != null) {
                println("  Вербальность: ${comm.verbosity}")
            }
        }
        println()
        
        if (profile.constraints != null) {
            println("⏱️  Ограничения:")
            if (profile.constraints.timePerDayMinutes != null) {
                val hours = profile.constraints.timePerDayMinutes / 60
                val minutes = profile.constraints.timePerDayMinutes % 60
                println("  Время в день: ${hours}ч ${minutes}мин")
            }
            if (profile.constraints.daysPerWeek != null) {
                println("  Дней в неделю: ${profile.constraints.daysPerWeek}")
            }
            println()
        }
        
        if (profile.goals?.main != null && profile.goals.main.isNotEmpty()) {
            println("🎯 Цели:")
            profile.goals.main.forEach { goal ->
                println("  • $goal")
            }
            println()
        }
        
        if (profile.priorities?.currentFocus != null) {
            println("📌 Текущий фокус: ${profile.priorities.currentFocus}")
            if (profile.priorities.secondary != null && profile.priorities.secondary.isNotEmpty()) {
                println("📌 Вторичные приоритеты: ${profile.priorities.secondary.joinToString(", ")}")
            }
            println()
        }
    }
}
