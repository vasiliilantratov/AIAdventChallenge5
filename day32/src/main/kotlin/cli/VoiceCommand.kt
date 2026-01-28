package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.example.config.AssistantConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.llm.OllamaLlmService
import org.example.model.Message
import org.example.model.MessageRole
import org.example.speech.SpeechRecognitionService
import java.util.Scanner

/**
 * Команда для голосового взаимодействия с LLM.
 * Speech → LLM → Text
 * 
 * Пользователь говорит в микрофон, речь распознается в текст,
 * отправляется в LLM, и ответ возвращается в текстовом виде.
 */
class VoiceCommand : CliktCommand(
    name = "voice",
    help = "Голосовой агент: говорите в микрофон, получайте текстовые ответы от LLM"
) {
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama сервера").default(AssistantConfig.defaultOllamaUrl)
    private val dbPath by option("--db-path", help = "Путь к базе данных SQLite").default("./index.db")
    private val modelPath by option("--vosk-model", help = "Путь к модели Vosk").default("./vosk-model")

    override fun run() = runBlocking {
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║            Голосовой агент - Speech → LLM → Text             ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()
        
        // Инициализация сервисов
        val speechService = SpeechRecognitionService(modelPath)
        
        try {
            println("🔄 Инициализация...")
            speechService.initialize()
            
            // Проверяем микрофон
            if (!speechService.isMicrophoneAvailable()) {
                println("❌ Ошибка: Микрофон недоступен")
                println()
                println("Возможные причины:")
                println("  • Микрофон не подключен")
                println("  • Нет прав доступа к микрофону")
                println("  • Микрофон используется другим приложением")
                return@runBlocking
            }
            
            println("✅ Микрофон доступен")
            
            // Показываем список доступных микрофонов
            val microphones = speechService.getAvailableMicrophones()
            if (microphones.isNotEmpty()) {
                println()
                println("📱 Доступные микрофоны:")
                microphones.forEachIndexed { index, name ->
                    println("   ${index + 1}. $name")
                }
            }
            
            println()
            println("-".repeat(64))
            println()
            
            // Инициализация БД и LLM
            DatabaseManager.initialize(dbPath)
            val repository = Repository()
            val llmService = OllamaLlmService(ollamaUrl)
            
            // Создаем или получаем последнюю беседу
            var conversationId = repository.getLastConversation()?.id 
                ?: repository.createConversation()
            
            println("Режим работы:")
            println("  • Говорите в микрофон для ввода запроса")
            println("  • Нажмите Enter для остановки записи и отправки в LLM")
            println("  • Введите '/exit' или '/quit' для выхода")
            println("  • Введите '/clear' для начала новой беседы")
            println("  • Введите '/history' для просмотра истории")
            println("  • Введите '/test' для тестовых запросов")
            println()
            println("-".repeat(64))
            println()
            
            val scanner = Scanner(System.`in`)
            
            while (true) {
                println("Выберите действие:")
                println("  1. 🎤 Голосовой ввод")
                println("  2. ⌨️  Текстовый ввод")
                println("  3. 🧪 Тестовые запросы (посчитай, определение, анекдот)")
                println("  4. 📜 История")
                println("  5. 🔄 Новая беседа")
                println("  6. 🚪 Выход")
                println()
                print("Ваш выбор (1-6): ")
                System.out.flush()
                
                val choice = scanner.nextLine()?.trim() ?: ""
                
                when (choice) {
                    "1" -> {
                        // Голосовой ввод
                        println()
                        val recognizedText = speechService.recognizeFromMicrophone()
                        
                        if (recognizedText.isBlank()) {
                            println("⚠️  Речь не распознана. Попробуйте еще раз.")
                            println()
                            continue
                        }
                        
                        println()
                        println("📝 Распознанный текст: \"$recognizedText\"")
                        println()
                        
                        processMessage(recognizedText, conversationId, repository, llmService)
                    }
                    
                    "2" -> {
                        // Текстовый ввод
                        println()
                        print("💬 Введите текст: ")
                        System.out.flush()
                        val text = scanner.nextLine()?.trim() ?: ""
                        
                        if (text.isEmpty()) {
                            continue
                        }
                        
                        if (text.lowercase() == "/exit" || text.lowercase() == "/quit") {
                            break
                        }
                        
                        println()
                        processMessage(text, conversationId, repository, llmService)
                    }
                    
                    "3" -> {
                        // Тестовые запросы
                        println()
                        println("Выберите тестовый запрос:")
                        println("  1. Посчитай 25 * 34")
                        println("  2. Дай определение нейронной сети")
                        println("  3. Расскажи короткий анекдот")
                        println()
                        print("Выбор (1-3): ")
                        System.out.flush()
                        
                        val testChoice = scanner.nextLine()?.trim() ?: ""
                        val testQuery = when (testChoice) {
                            "1" -> "Посчитай 25 умножить на 34"
                            "2" -> "Дай определение нейронной сети"
                            "3" -> "Расскажи короткий анекдот"
                            else -> {
                                println("Неверный выбор")
                                continue
                            }
                        }
                        
                        println()
                        println("📝 Запрос: \"$testQuery\"")
                        println()
                        
                        processMessage(testQuery, conversationId, repository, llmService)
                    }
                    
                    "4" -> {
                        // История
                        showHistory(conversationId, repository)
                    }
                    
                    "5" -> {
                        // Новая беседа
                        conversationId = repository.createConversation()
                        println()
                        println("✨ Начата новая беседа (ID: $conversationId)")
                        println()
                    }
                    
                    "6", "/exit", "/quit" -> {
                        // Выход
                        break
                    }
                    
                    else -> {
                        println("⚠️  Неверный выбор. Попробуйте еще раз.")
                        println()
                    }
                }
                
                println()
                println("-".repeat(64))
                println()
            }
            
            println()
            println("👋 Завершение работы голосового агента. До встречи!")
            
            llmService.close()
            speechService.close()
            
        } catch (e: IllegalStateException) {
            println()
            println("❌ Ошибка: ${e.message}")
            println()
        } catch (e: Exception) {
            println()
            println("❌ Неожиданная ошибка: ${e.message}")
            e.printStackTrace()
            println()
        }
    }
    
    private suspend fun processMessage(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService
    ) {
        val now = System.currentTimeMillis()
        
        // Сохраняем сообщение пользователя
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userInput,
            mode = "voice",
            createdAt = now
        ))
        
        // Получаем ответ от LLM
        println("🤖 LLM обрабатывает запрос...")
        
        val systemPrompt = """
            Ты — полезный AI ассистент для голосового взаимодействия.
            Пользователь говорит вопросы голосом, ты отвечаешь текстом.
            
            Отвечай:
            - Кратко и по делу (2-4 предложения)
            - На русском языке
            - Понятным языком, как будто объясняешь другу
            - Если это вычисление - дай точный ответ
            - Если это определение - дай краткое и понятное объяснение
            - Если это анекдот - расскажи короткий и смешной
            
            Помни: твой ответ будет прочитан пользователем, поэтому структурируй его удобно.
        """.trimIndent()
        
        val answer = try {
            llmService.generateAnswer(systemPrompt, userInput)
        } catch (e: Exception) {
            "❌ Ошибка при получении ответа от LLM: ${e.message}"
        }
        
        // Сохраняем ответ
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = answer,
            mode = "voice",
            sourcesJson = null,
            createdAt = System.currentTimeMillis()
        ))
        
        // Выводим ответ
        println()
        println("═".repeat(64))
        println("📄 Ответ:")
        println("═".repeat(64))
        println()
        println(answer)
        println()
        println("═".repeat(64))
    }
    
    private fun showHistory(conversationId: Long, repository: Repository) {
        println()
        val conversation = repository.getConversation(conversationId)
        if (conversation == null) {
            println("❌ Беседа не найдена.")
            return
        }
        
        val messages = repository.getMessages(conversationId)
        if (messages.isEmpty()) {
            println("📭 В беседе пока нет сообщений.")
            return
        }
        
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
