package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.config.AssistantConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.llm.OllamaLlmService
import org.example.mcp.ReleaseMcp
import org.example.model.Message
import org.example.model.MessageRole
import java.util.Scanner

/**
 * Команда для интерактивного чата с LLM.
 * Поддерживает специальные команды, включая релиз приложения.
 */
class ChatCommand : CliktCommand(
    name = "chat",
    help = "Интерактивный чат с LLM. Поддерживает команды релиза приложения."
) {
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama").default(AssistantConfig.defaultOllamaUrl)
    private val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    private val initialMessageParts by argument(
        "message", 
        help = "Начальное сообщение (опционально)"
    ).multiple()

    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val llmService = OllamaLlmService(ollamaUrl)
        
        // Создаем или получаем последнюю беседу
        var conversationId = repository.getLastConversation()?.id 
            ?: repository.createConversation()
        
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║              Чат с LLM - Интерактивный режим                 ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()
        println("Доступные команды:")
        println("  • Любой вопрос - общайтесь с LLM")
        println("  • '/exit', '/quit' - выход из чата")
        println("  • '/clear' - начать новую беседу")
        println("  • '/history' - показать историю текущей беседы")
        println()
        
        // Если есть начальное сообщение, обработаем его
        if (initialMessageParts.isNotEmpty()) {
            val initialMessage = initialMessageParts.joinToString(" ")
            println("👤 Вы: $initialMessage")
            println()
            
            val response = processMessage(initialMessage, conversationId, repository, llmService)
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
                    println("     ./gradlew run --args='chat Привет!'")
                    println()
                    println("  2. Запуск напрямую (после сборки):")
                    println("     ./gradlew installDist")
                    println("     ./build/install/day23/bin/day23 chat")
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
            }
            
            // Обработка обычного сообщения
            println()
            val response = processMessage(userInput, conversationId, repository, llmService)
            println("🤖 Ассистент: $response")
            println()
            println("-".repeat(64))
            println()
        }
        
        llmService.close()
    }
    
    private suspend fun processMessage(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService
    ): String {
        val now = System.currentTimeMillis()
        
        // Сохраняем сообщение пользователя
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userInput,
            mode = "chat",
            createdAt = now
        ))
        
        // Используем LLM для определения намерения
        val needsRelease = detectReleaseIntent(userInput, llmService)
        
        if (needsRelease) {
            return handleReleaseRequest(userInput, conversationId, repository, llmService)
        }
        
        // Обычный ответ от LLM
        val systemPrompt = """
            Ты — полезный AI ассистент. Отвечай кратко, по делу и дружелюбно.
            Если пользователь спрашивает про релиз приложения, напомни, что нужно явно попросить зарелизить приложение.
            Отвечай на русском языке.
        """.trimIndent()
        
        val answer = llmService.generateAnswer(systemPrompt, userInput)
        
        // Сохраняем ответ
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = answer,
            mode = "chat",
            sourcesJson = null,
            createdAt = System.currentTimeMillis()
        ))
        
        return answer
    }
    
    private suspend fun handleReleaseRequest(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService
    ): String {
        println("🚀 Обнаружен запрос на релиз приложения...")
        println()
        
        val releaseMcp = ReleaseMcp()
        
        // Проверяем предварительные условия
        print("   1. Проверка локальной директории... ")
        if (!releaseMcp.checkLocalDirectory()) {
            val errorMsg = "❌ Ошибка: локальная директория /home/vas/Documents/Projects/EchoBot не найдена."
            println(errorMsg)
            
            repository.saveMessage(Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = errorMsg,
                mode = "release",
                sourcesJson = null,
                createdAt = System.currentTimeMillis()
            ))
            
            return errorMsg
        }
        println("✅")
        
        print("   2. Проверка SSH соединения с my_mon_bot... ")
        if (!releaseMcp.testConnection()) {
            val errorMsg = "❌ Ошибка: не удалось подключиться к серверу через SSH (my_mon_bot)."
            println(errorMsg)
            
            repository.saveMessage(Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = errorMsg,
                mode = "release",
                sourcesJson = null,
                createdAt = System.currentTimeMillis()
            ))
            
            return errorMsg
        }
        println("✅")
        
        print("   3. Получение списка файлов... ")
        val files = releaseMcp.getLocalFiles()
        println("найдено ${files.size} файлов")
        
        if (files.isNotEmpty()) {
            println()
            println("   Файлы для загрузки:")
            files.take(10).forEach { file ->
                println("      • $file")
            }
            if (files.size > 10) {
                println("      ... и еще ${files.size - 10} файлов")
            }
        }
        
        println()
        print("   4. Загрузка файлов на сервер... ")
        
        val result = releaseMcp.release()
        
        println()
        println()
        
        val responseBuilder = StringBuilder()
        
        if (result.success) {
            responseBuilder.appendLine("✅ Релиз успешно завершен!")
            responseBuilder.appendLine()
            responseBuilder.appendLine("📊 Статистика:")
            responseBuilder.appendLine("   • Загружено файлов: ${result.uploadedFiles.size}")
            responseBuilder.appendLine("   • Время выполнения: ${result.durationMs / 1000.0} секунд")
            responseBuilder.appendLine("   • Удаленная директория: /root/release на my_mon_bot")
            
            if (result.uploadedFiles.isNotEmpty()) {
                responseBuilder.appendLine()
                responseBuilder.appendLine("📦 Загруженные файлы:")
                result.uploadedFiles.take(15).forEach { file ->
                    responseBuilder.appendLine("   • $file")
                }
                if (result.uploadedFiles.size > 15) {
                    responseBuilder.appendLine("   ... и еще ${result.uploadedFiles.size - 15} файлов")
                }
            }
            
            // Получаем информацию о релизе на сервере
            val remoteInfo = releaseMcp.getRemoteInfo()
            if (remoteInfo.isNotBlank() && !remoteInfo.startsWith("Ошибка")) {
                responseBuilder.appendLine()
                responseBuilder.appendLine("📁 Содержимое удаленной директории:")
                remoteInfo.lines().take(10).forEach { line ->
                    if (line.isNotBlank()) {
                        responseBuilder.appendLine("   $line")
                    }
                }
            }
            
        } else {
            responseBuilder.appendLine("❌ Релиз завершился с ошибкой:")
            responseBuilder.appendLine()
            responseBuilder.appendLine(result.message)
            
            if (result.errors.isNotEmpty()) {
                responseBuilder.appendLine()
                responseBuilder.appendLine("Ошибки:")
                result.errors.forEach { error ->
                    responseBuilder.appendLine("   • $error")
                }
            }
        }
        
        val finalResponse = responseBuilder.toString()
        
        // Сохраняем результат релиза (создаем JSON вручную)
        val releaseInfoJson = buildString {
            append("{")
            append("\"success\":${result.success},")
            append("\"filesCount\":${result.uploadedFiles.size},")
            append("\"durationMs\":${result.durationMs},")
            append("\"remoteDir\":\"/root/release\",")
            append("\"sshConfig\":\"my_mon_bot\"")
            append("}")
        }
        
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = finalResponse,
            mode = "release",
            sourcesJson = releaseInfoJson,
            createdAt = System.currentTimeMillis()
        ))
        
        return finalResponse
    }
    
    private suspend fun detectReleaseIntent(input: String, llmService: OllamaLlmService): Boolean {
        val systemPrompt = """
            Ты — система анализа намерений пользователя. Твоя задача определить, хочет ли пользователь выполнить релиз/деплой приложения на сервер.
            
            Релиз/деплой означает загрузку файлов приложения на удаленный сервер через SSH.
            
            Проанализируй сообщение пользователя и определи, является ли это запросом на релиз.
            
            Примеры запросов на РЕЛИЗ:
            - "зарелизь приложение"
            - "сделай релиз"
            - "нужно задеплоить"
            - "загрузи файлы на сервер"
            - "выполни деплой"
            - "deploy the application"
            - "давай зарелизим"
            - "пора делать релиз"
            - "можешь зарелизить?"
            
            Примеры НЕ запросов на релиз:
            - "что такое релиз?"
            - "расскажи про процесс релиза"
            - "как работает деплой?"
            - "когда последний раз был релиз?"
            - "нужно ли делать релиз?"
            
            Ответь СТРОГО одним словом: YES (если это запрос на релиз) или NO (если это не запрос на релиз).
            Никаких дополнительных объяснений, только YES или NO.
        """.trimIndent()
        
        val userMessage = "Сообщение пользователя: \"$input\""
        
        return try {
            val response = llmService.generateAnswer(systemPrompt, userMessage)
            val cleanResponse = response.trim().uppercase()
            
            // Проверяем, что ответ содержит YES
            cleanResponse.contains("YES")
        } catch (e: Exception) {
            // В случае ошибки возвращаем false (безопасное поведение)
            println("⚠️  Ошибка при определении намерения: ${e.message}")
            false
        }
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
}
