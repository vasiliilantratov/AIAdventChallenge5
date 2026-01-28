package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.example.config.AssistantConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.indexing.ProjectIndexer
import org.example.llm.OllamaLlmService
import org.example.mcp.McpClient
import org.example.mcp.ReleaseMcp
import org.example.mcp.TaskManagerMcp
import org.example.model.Message
import org.example.model.MessageRole
import org.example.model.TaskPriority
import org.example.model.TaskStatus
import org.example.model.UserProfile
import org.example.search.LlmReranker
import org.example.search.RagServiceImpl
import org.example.search.SemanticSearch
import org.example.speech.SpeechRecognitionService
import java.io.File
import java.util.Scanner

/**
 * Универсальный агент, объединяющий функционал всех команд:
 * - Голосовой ввод (VoiceCommand)
 * - Персонализированный агент (PersonalizedAgentCommand)
 * - Анализ логов (AnalyzeLogsCommand)
 * - Чат с LLM и релиз (ChatCommand)
 * - Командный ассистент (TeamCommand): RAG, задачи, git, поиск по коду
 */
class GodAgentCommand : CliktCommand(
    name = "god",
    help = "Универсальный агент с голосовым вводом, объединяющий весь функционал"
) {
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama сервера").default(AssistantConfig.defaultOllamaUrl)
    private val dbPath by option("--db-path", help = "Путь к базе данных SQLite").default("./index.db")
    private val modelPath by option("--vosk-model", help = "Путь к модели Vosk").default("./vosk-model")
    private val profilePath by option("--profile", help = "Путь к профилю пользователя JSON").default("./personal/user_profile.json")
    private val logsDir by option("--logs-dir", help = "Путь к папке с логами").default("./logsForAnalysis")

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    override fun run() = runBlocking {
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║              GOD AGENT - Универсальный агент                 ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()

        // Инициализация сервисов
        val speechService = SpeechRecognitionService(modelPath)
        var speechInitialized = false

        try {
            // Пытаемся инициализировать распознавание речи
            try {
                println("🔄 Инициализация распознавания речи...")
                speechService.initialize()
                speechInitialized = true
                if (speechService.isMicrophoneAvailable()) {
                    println("✅ Микрофон доступен")
                } else {
                    println("⚠️  Микрофон недоступен, будет доступен только текстовый ввод")
                }
            } catch (e: Exception) {
                println("⚠️  Распознавание речи недоступно: ${e.message}")
                println("   Будет доступен только текстовый ввод")
            }

            // Инициализация БД и LLM
            DatabaseManager.initialize(dbPath)
            val repository = Repository()
            val llmService = OllamaLlmService(ollamaUrl)

            // Загружаем профиль пользователя
            val profile = loadUserProfile(profilePath)
            val systemPrompt = if (profile != null) {
                buildSystemPrompt(profile)
            } else {
                buildDefaultSystemPrompt()
            }

            // Создаем или получаем последнюю беседу
            var conversationId = repository.getLastConversation()?.id
                ?: repository.createConversation()

            println()
            if (profile != null) {
                println("👤 Пользователь: ${profile.user.name}")
                if (profile.user.language != null) {
                    println("🌐 Язык: ${profile.user.language}")
                }
                if (profile.priorities?.currentFocus != null) {
                    println("🎯 Текущий фокус: ${profile.priorities.currentFocus}")
                }
            }
            println()
            println("Доступные возможности:")
            println("  🎤 Голосовой ввод (если доступен)")
            println("  💬 Чат с LLM")
            println("  📋 Управление задачами")
            println("  📚 Поиск по документации (RAG)")
            println("  📊 Анализ логов")
            println("  🚀 Релиз приложения")
            println("  💻 Поиск по коду и git")
            println()
            println("Команды:")
            println("  • '/exit', '/quit' - выход")
            println("  • '/clear' - новая беседа")
            println("  • '/history' - история беседы")
            if (profile != null) {
                println("  • '/profile' - показать профиль")
            }
            println()
            println("-".repeat(64))
            println()

            val scanner = Scanner(System.`in`)

            while (true) {
                println("Выберите способ ввода:")
                if (speechInitialized && speechService.isMicrophoneAvailable()) {
                    println("  1. 🎤 Голосовой ввод")
                    println("  2. ⌨️  Текстовый ввод")
                } else {
                    println("  1. ⌨️  Текстовый ввод")
                }
                println("  3. 📜 История")
                println("  4. 🔄 Новая беседа")
                if (profile != null) {
                    println("  5. 👤 Профиль")
                }
                println("  6. 🚪 Выход")
                println()
                print("Ваш выбор: ")
                System.out.flush()

                val choice = scanner.nextLine()?.trim() ?: ""

                when (choice) {
                    "1" -> {
                        if (speechInitialized && speechService.isMicrophoneAvailable()) {
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

                            processUserRequest(recognizedText, conversationId, repository, llmService, systemPrompt)
                        } else {
                            // Текстовый ввод
                            handleTextInput(scanner, conversationId, repository, llmService, systemPrompt)
                        }
                    }
                    "2" -> {
                        if (speechInitialized && speechService.isMicrophoneAvailable()) {
                            handleTextInput(scanner, conversationId, repository, llmService, systemPrompt)
                        } else {
                            println("⚠️  Неверный выбор. Попробуйте еще раз.")
                            println()
                        }
                    }
                    "3" -> {
                        showHistory(conversationId, repository)
                    }
                    "4" -> {
                        conversationId = repository.createConversation()
                        println()
                        println("✨ Начата новая беседа (ID: $conversationId)")
                        println()
                    }
                    "5" -> {
                        if (profile != null) {
                            showProfile(profile)
                        } else {
                            println("⚠️  Неверный выбор. Попробуйте еще раз.")
                            println()
                        }
                    }
                    "6", "/exit", "/quit" -> {
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
            println("👋 Завершение работы. До встречи!")

            llmService.close()
            if (speechInitialized) {
                speechService.close()
            }

        } catch (e: Exception) {
            println()
            println("❌ Неожиданная ошибка: ${e.message}")
            e.printStackTrace()
            println()
        }
    }

    private fun handleTextInput(
        scanner: Scanner,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService,
        systemPrompt: String
    ) = runBlocking {
        println()
        print("💬 Введите запрос: ")
        System.out.flush()
        val text = scanner.nextLine()?.trim() ?: ""

        if (text.isEmpty()) {
            return@runBlocking
        }

        if (text.lowercase() == "/exit" || text.lowercase() == "/quit") {
            return@runBlocking
        }

        println()
        processUserRequest(text, conversationId, repository, llmService, systemPrompt)
    }

    private suspend fun processUserRequest(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService,
        systemPrompt: String
    ) {
        val now = System.currentTimeMillis()

        // Сохраняем сообщение пользователя
        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userInput,
            mode = "god",
            createdAt = now
        ))

        // Определяем намерение пользователя и используемые инструменты
        val intent = determineIntent(userInput, llmService)

        println("🤖 Анализ запроса...")
        println("   Инструменты: ${intent.tools.joinToString(", ")}")
        println()

        // Обрабатываем запрос в зависимости от намерения
        val response = when {
            intent.needsRelease -> handleReleaseRequest(userInput, conversationId, repository, llmService)
            intent.needsLogAnalysis -> handleLogAnalysis(userInput, conversationId, repository, llmService)
            intent.needsTaskManagement || intent.needsRag || intent.needsGitInfo || intent.needsCodeSearch -> {
                handleTeamRequest(userInput, intent, conversationId, repository, llmService, systemPrompt)
            }
            else -> {
                // Обычный чат с персонализацией
                handleChatRequest(userInput, conversationId, repository, llmService, systemPrompt)
            }
        }

        // Выводим ответ
        println()
        println("═".repeat(64))
        println("📄 Ответ:")
        println("═".repeat(64))
        println()
        println(response)
        println()
        println("═".repeat(64))
    }

    private suspend fun determineIntent(userInput: String, llmService: OllamaLlmService): GodAgentIntent {
        val systemPrompt = """
            Ты — система анализа намерений для универсального агента.
            Определи, какие инструменты нужны для выполнения запроса пользователя.
            
            Доступные инструменты:
            1. RELEASE - релиз/деплой приложения на сервер
            2. LOG_ANALYSIS - анализ логов из папки logsForAnalysis
            3. TASK_MANAGEMENT - работа с задачами (просмотр, создание, изменение)
            4. RAG - поиск по документации проекта
            5. GIT_INFO - информация о git (ветка, статус)
            6. CODE_SEARCH - поиск по коду проекта
            
            Для TASK_MANAGEMENT определи действие:
            - LIST - показать задачи
            - CREATE - создать задачу
            - UPDATE_STATUS - изменить статус
            - STATS - статистика
            - RECOMMEND - рекомендации
            
            Ответь СТРОГО в формате:
            RELEASE: yes/no
            LOG_ANALYSIS: yes/no
            TASK_MANAGEMENT: yes/no
            TASK_ACTION: LIST/CREATE/UPDATE_STATUS/STATS/RECOMMEND/null
            TASK_FILTER_PRIORITY: HIGH/MEDIUM/LOW/CRITICAL/null
            TASK_FILTER_STATUS: TODO/IN_PROGRESS/DONE/BLOCKED/null
            RAG: yes/no
            GIT_INFO: yes/no
            CODE_SEARCH: yes/no
            CODE_KEYWORD: <ключевое слово или null>
        """.trimIndent()

        val userMessage = "Запрос пользователя: \"$userInput\""

        return try {
            val response = llmService.generateAnswer(systemPrompt, userMessage)

            val needsRelease = response.contains("RELEASE: yes", ignoreCase = true)
            val needsLogAnalysis = response.contains("LOG_ANALYSIS: yes", ignoreCase = true)
            val needsTaskManagement = response.contains("TASK_MANAGEMENT: yes", ignoreCase = true)
            val needsRag = response.contains("RAG: yes", ignoreCase = true)
            val needsGitInfo = response.contains("GIT_INFO: yes", ignoreCase = true)
            val needsCodeSearch = response.contains("CODE_SEARCH: yes", ignoreCase = true)

            val taskActionRegex = Regex("TASK_ACTION:\\s*(\\w+)", RegexOption.IGNORE_CASE)
            val taskActionMatch = taskActionRegex.find(response)
            val taskAction = taskActionMatch?.groupValues?.get(1)?.uppercase()?.takeIf {
                it != "NULL" && it.isNotEmpty()
            }

            val taskPriorityRegex = Regex("TASK_FILTER_PRIORITY:\\s*(\\w+)", RegexOption.IGNORE_CASE)
            val taskPriorityMatch = taskPriorityRegex.find(response)
            val taskPriority = taskPriorityMatch?.groupValues?.get(1)?.uppercase()?.takeIf {
                it != "NULL" && it.isNotEmpty()
            }?.let {
                try { TaskPriority.valueOf(it) } catch (_: Exception) { null }
            }

            val taskStatusRegex = Regex("TASK_FILTER_STATUS:\\s*(\\w+)", RegexOption.IGNORE_CASE)
            val taskStatusMatch = taskStatusRegex.find(response)
            val taskStatus = taskStatusMatch?.groupValues?.get(1)?.uppercase()?.takeIf {
                it != "NULL" && it.isNotEmpty()
            }?.let {
                try { TaskStatus.valueOf(it) } catch (_: Exception) { null }
            }

            val keywordRegex = Regex("CODE_KEYWORD:\\s*(.+)", RegexOption.IGNORE_CASE)
            val keywordMatch = keywordRegex.find(response)
            val keyword = keywordMatch?.groupValues?.get(1)?.trim()?.takeIf {
                it != "null" && it.isNotEmpty()
            }

            val tools = mutableListOf<String>()
            if (needsRelease) tools.add("RELEASE")
            if (needsLogAnalysis) tools.add("LOG_ANALYSIS")
            if (needsTaskManagement) tools.add("TASK_MANAGEMENT")
            if (needsRag) tools.add("RAG")
            if (needsGitInfo) tools.add("GIT_INFO")
            if (needsCodeSearch) tools.add("CODE_SEARCH")
            if (tools.isEmpty()) tools.add("CHAT")

            GodAgentIntent(
                needsRelease = needsRelease,
                needsLogAnalysis = needsLogAnalysis,
                needsTaskManagement = needsTaskManagement,
                taskAction = taskAction,
                taskFilterPriority = taskPriority,
                taskFilterStatus = taskStatus,
                needsRag = needsRag,
                needsGitInfo = needsGitInfo,
                needsCodeSearch = needsCodeSearch,
                codeSearchKeyword = keyword,
                tools = tools
            )
        } catch (e: Exception) {
            println("⚠️  Ошибка при определении намерения: ${e.message}")
            GodAgentIntent(
                needsRelease = false,
                needsLogAnalysis = false,
                needsTaskManagement = false,
                taskAction = null,
                taskFilterPriority = null,
                taskFilterStatus = null,
                needsRag = false,
                needsGitInfo = false,
                needsCodeSearch = false,
                codeSearchKeyword = null,
                tools = listOf("CHAT")
            )
        }
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

        print("   1. Проверка локальной директории... ")
        if (!releaseMcp.checkLocalDirectory()) {
            val errorMsg = "❌ Ошибка: локальная директория не найдена."
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

        print("   2. Проверка SSH соединения... ")
        if (!releaseMcp.testConnection()) {
            val errorMsg = "❌ Ошибка: не удалось подключиться к серверу через SSH."
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
        } else {
            responseBuilder.appendLine("❌ Релиз завершился с ошибкой:")
            responseBuilder.appendLine()
            responseBuilder.appendLine(result.message)
        }

        val finalResponse = responseBuilder.toString()

        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = finalResponse,
            mode = "release",
            sourcesJson = null,
            createdAt = System.currentTimeMillis()
        ))

        return finalResponse
    }

    private suspend fun handleLogAnalysis(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService
    ): String {
        println("📊 Анализ логов...")

        val logsDirFile = File(logsDir)
        if (!logsDirFile.exists() || !logsDirFile.isDirectory) {
            val errorMsg = "❌ Ошибка: папка с логами не найдена: $logsDir"
            repository.saveMessage(Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = errorMsg,
                mode = "log-analysis",
                sourcesJson = null,
                createdAt = System.currentTimeMillis()
            ))
            return errorMsg
        }

        val logFiles = logsDirFile.listFiles { _, name -> name.endsWith(".jsonl") }
            ?.sortedBy { it.name }?.toList() ?: emptyList()

        if (logFiles.isEmpty()) {
            val errorMsg = "⚠️  В папке не найдено файлов с расширением .jsonl"
            repository.saveMessage(Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = errorMsg,
                mode = "log-analysis",
                sourcesJson = null,
                createdAt = System.currentTimeMillis()
            ))
            return errorMsg
        }

        val allLogs = mutableListOf<LogEntry>()
        for (file in logFiles) {
            try {
                allLogs.addAll(readLogFile(file))
            } catch (e: Exception) {
                // Пропускаем ошибки
            }
        }

        if (allLogs.isEmpty()) {
            val errorMsg = "⚠️  Не удалось прочитать ни одной записи из логов"
            repository.saveMessage(Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = errorMsg,
                mode = "log-analysis",
                sourcesJson = null,
                createdAt = System.currentTimeMillis()
            ))
            return errorMsg
        }

        val logsContext = formatLogsForAnalysis(allLogs)

        val systemPrompt = """
            Ты — эксперт по анализу логов приложений. Твоя задача — проанализировать предоставленные логи и дать КРАТКИЙ, КОНКРЕТНЫЙ ответ ТОЛЬКО на заданный вопрос.
            
            ВАЖНЫЕ ПРАВИЛА:
            - Отвечай ТОЛЬКО на заданный вопрос, ничего больше
            - НЕ давай рекомендаций, советов или общих выводов
            - НЕ перечисляй все найденные проблемы, если вопрос конкретный
            - Используй конкретные цифры, проценты, названия эндпоинтов из логов
            - Если данных недостаточно, скажи это кратко (1 предложение)
            
            Отвечай на русском языке, кратко и по делу. Только факты из логов, без интерпретаций и рекомендаций.
        """.trimIndent()

        val userMessage = """
            Логи для анализа:
            $logsContext
            
            Вопрос: $userInput
        """.trimIndent()

        val answer = try {
            llmService.generateAnswer(systemPrompt, userMessage)
        } catch (e: Exception) {
            "❌ Ошибка при обращении к ИИ: ${e.message}"
        }

        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = answer,
            mode = "log-analysis",
            sourcesJson = null,
            createdAt = System.currentTimeMillis()
        ))

        return answer
    }

    private suspend fun handleTeamRequest(
        userInput: String,
        intent: GodAgentIntent,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService,
        systemPrompt: String
    ): String {
        val contextParts = mutableListOf<String>()
        val sources = mutableListOf<String>()

        // 1. TASK_MANAGEMENT
        if (intent.needsTaskManagement) {
            val taskManager = TaskManagerMcp()

            when (intent.taskAction) {
                "LIST" -> {
                    val tasks = when {
                        intent.taskFilterPriority != null ->
                            taskManager.getTasksByPriority(intent.taskFilterPriority)
                        intent.taskFilterStatus != null ->
                            taskManager.getTasksByStatus(intent.taskFilterStatus)
                        else -> taskManager.getAllTasks()
                    }

                    if (tasks.isNotEmpty()) {
                        contextParts.add("=== Задачи команды ===")
                        tasks.forEach { task ->
                            contextParts.add(formatTask(task))
                        }
                        sources.add("tasks.json")
                    } else {
                        contextParts.add("=== Задачи команды ===")
                        contextParts.add("Задачи не найдены по указанным критериям.")
                    }
                }
                "CREATE" -> {
                    val taskInfo = extractTaskInfo(llmService, userInput)
                    if (taskInfo != null) {
                        val newTask = taskManager.createTask(
                            title = taskInfo.title,
                            description = taskInfo.description,
                            priority = taskInfo.priority,
                            tags = taskInfo.tags
                        )
                        contextParts.add("=== Новая задача создана ===")
                        contextParts.add(formatTask(newTask))
                        sources.add("tasks.json")
                    }
                }
                "STATS" -> {
                    val stats = taskManager.getStats()
                    contextParts.add("=== Статистика по задачам ===")
                    contextParts.add("Всего задач: ${stats.total}")
                    contextParts.add("\nПо статусам:")
                    stats.byStatus.forEach { (status, count) ->
                        contextParts.add("  ${status.name}: $count")
                    }
                    contextParts.add("\nПо приоритетам:")
                    stats.byPriority.forEach { (priority, count) ->
                        contextParts.add("  ${priority.name}: $count")
                    }
                    sources.add("tasks.json")
                }
                "RECOMMEND" -> {
                    val allTasks = taskManager.getAllTasks()
                    val stats = taskManager.getStats()

                    contextParts.add("=== Текущая ситуация с задачами ===")
                    contextParts.add("Всего задач: ${stats.total}")
                    contextParts.add("В работе: ${stats.byStatus[TaskStatus.IN_PROGRESS] ?: 0}")
                    contextParts.add("Заблокированных: ${stats.byStatus[TaskStatus.BLOCKED] ?: 0}")

                    val critical = allTasks.filter {
                        it.priority == TaskPriority.CRITICAL && it.status != TaskStatus.DONE
                    }
                    val blocked = allTasks.filter { it.status == TaskStatus.BLOCKED }

                    if (critical.isNotEmpty()) {
                        contextParts.add("\n🚨 Критичные задачи:")
                        critical.forEach { contextParts.add(formatTask(it, brief = true)) }
                    }

                    if (blocked.isNotEmpty()) {
                        contextParts.add("\n🔒 Заблокированные задачи:")
                        blocked.forEach { contextParts.add(formatTask(it, brief = true)) }
                    }

                    sources.add("tasks.json")
                }
            }
        }

        // 2. RAG
        if (intent.needsRag) {
            ProjectIndexer.ensureIndexed(ollamaUrl = ollamaUrl, dbPath = dbPath)
            DatabaseManager.initialize(dbPath)

            val embeddingService = OllamaEmbeddingService(ollamaUrl)
            val semanticSearch = SemanticSearch(repository, embeddingService)
            val ragService = RagServiceImpl(
                semanticSearch = semanticSearch,
                embeddingService = embeddingService,
                llmService = llmService,
                reranker = LlmReranker(llmService)
            )

            val ragAnswer = ragService.answerWithRag(
                question = userInput,
                topK = 3,
                enableReranking = false,
                relevanceThreshold = null,
                rerankTopK = null
            )

            if (ragAnswer.contextChunks.isNotEmpty()) {
                contextParts.add("=== Документация проекта ===")
                contextParts.add(ragAnswer.contextChunks.joinToString("\n\n") { it.content })
                sources.addAll(ragAnswer.sources.map { "${it.documentPath}" })
            }

            embeddingService.close()
        }

        // 3. GIT_INFO
        if (intent.needsGitInfo) {
            val mcp = McpClient()
            val branch = safeCall { mcp.gitBranch() }
            val status = safeCall { mcp.gitStatus() }

            contextParts.add("=== Git информация ===")
            contextParts.add("Текущая ветка: ${branch ?: "неизвестно"}")
            contextParts.add("Статус: ${if (status.isNullOrBlank()) "нет изменений" else "\n$status"}")
        }

        // 4. CODE_SEARCH
        if (intent.needsCodeSearch) {
            val mcp = McpClient()
            val keyword = intent.codeSearchKeyword ?: extractKeyword(userInput)

            if (keyword != null) {
                val hits = safeCall { mcp.search(keyword, ".") } ?: emptyList()

                if (hits.isNotEmpty()) {
                    contextParts.add("=== Результаты поиска по коду (ключевое слово: '$keyword') ===")
                    hits.take(3).forEach { hit ->
                        val lines = safeCall { mcp.readFile(hit.file) } ?: emptyList()
                        val snippet = buildSnippet(lines, hit.line, context = 2)
                        contextParts.add("Файл: ${hit.file}:${hit.line}")
                        contextParts.add(snippet)
                        sources.add(hit.file)
                    }
                }
            }
        }

        // Формируем финальный ответ
        val fullContext = contextParts.joinToString("\n\n")

        val finalSystemPrompt = """
            $systemPrompt
            
            Ты — универсальный ассистент, который может работать с задачами, документацией, кодом и git.
            Отвечай кратко (3-10 предложений), конкретно и по делу.
            Используй только предоставленную информацию.
            Отвечай на русском языке.
        """.trimIndent()

        val finalUserMessage = """
            Вопрос: $userInput
            
            Доступная информация:
            $fullContext
        """.trimIndent()

        val finalAnswer = llmService.generateAnswer(finalSystemPrompt, finalUserMessage)

        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = finalAnswer,
            mode = "god",
            sourcesJson = if (sources.isNotEmpty()) Json.encodeToString(sources) else null,
            createdAt = System.currentTimeMillis()
        ))

        return finalAnswer
    }

    private suspend fun handleChatRequest(
        userInput: String,
        conversationId: Long,
        repository: Repository,
        llmService: OllamaLlmService,
        systemPrompt: String
    ): String {
        val history = repository.getMessages(conversationId)
        val recentHistory = history.takeLast(10)

        val contextMessages = recentHistory.map { msg ->
            when (msg.role) {
                MessageRole.USER -> "Пользователь: ${msg.content}"
                MessageRole.ASSISTANT -> "Ассистент: ${msg.content}"
            }
        }

        val userMessage = if (contextMessages.size > 2) {
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

        val answer = llmService.generateAnswer(systemPrompt, userMessage)

        repository.saveMessage(Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = answer,
            mode = "god",
            sourcesJson = null,
            createdAt = System.currentTimeMillis()
        ))

        return answer
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
            null
        }
    }

    private fun buildSystemPrompt(profile: UserProfile): String {
        val builder = StringBuilder()

        builder.appendLine("Ты — универсальный AI ассистент для пользователя ${profile.user.name}.")
        
        if (profile.user.language != null) {
            builder.appendLine("Язык общения: ${profile.user.language}.")
        }

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
        }

        profile.priorities?.let { priorities ->
            if (priorities.currentFocus != null) {
                builder.appendLine("Текущий фокус: ${priorities.currentFocus}.")
            }
        }

        profile.agentBehavior?.let { behavior ->
            if (behavior.shouldDo != null && behavior.shouldDo.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("Ты ДОЛЖЕН:")
                behavior.shouldDo.forEach { rule ->
                    builder.appendLine("  - $rule")
                }
            }
        }

        builder.appendLine()
        builder.appendLine("Ты можешь работать с задачами, анализировать логи, искать по документации и коду, выполнять релизы и отвечать на вопросы.")

        return builder.toString().trim()
    }

    private fun buildDefaultSystemPrompt(): String {
        return """
            Ты — универсальный AI ассистент.
            Ты можешь работать с задачами, анализировать логи, искать по документации и коду, выполнять релизы и отвечать на вопросы.
            Отвечай кратко, по делу и дружелюбно.
            Отвечай на русском языке.
        """.trimIndent()
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

    private fun showProfile(profile: UserProfile) {
        println()
        println("═".repeat(64))
        println("Профиль пользователя")
        println("═".repeat(64))
        println()
        println("👤 Имя: ${profile.user.name}")
        if (profile.user.language != null) {
            println("🌐 Язык: ${profile.user.language}")
        }
        if (profile.priorities?.currentFocus != null) {
            println("🎯 Текущий фокус: ${profile.priorities.currentFocus}")
        }
        println()
    }

    // Вспомогательные функции из TeamCommand и AnalyzeLogsCommand
    private fun formatTask(task: org.example.model.Task, brief: Boolean = false): String {
        val priorityEmoji = when (task.priority) {
            TaskPriority.CRITICAL -> "🔴"
            TaskPriority.HIGH -> "🟠"
            TaskPriority.MEDIUM -> "🟡"
            TaskPriority.LOW -> "🟢"
        }

        val statusEmoji = when (task.status) {
            TaskStatus.TODO -> "⏳"
            TaskStatus.IN_PROGRESS -> "🔄"
            TaskStatus.DONE -> "✅"
            TaskStatus.BLOCKED -> "🔒"
        }

        return if (brief) {
            "  $priorityEmoji $statusEmoji ${task.title} (${task.assignee ?: "не назначено"})"
        } else {
            buildString {
                appendLine()
                appendLine("$priorityEmoji $statusEmoji [${task.priority}] ${task.title}")
                appendLine("   Описание: ${task.description}")
                appendLine("   Статус: ${task.status}")
                if (task.assignee != null) appendLine("   Исполнитель: ${task.assignee}")
                if (task.tags.isNotEmpty()) appendLine("   Теги: ${task.tags.joinToString(", ")}")
                if (task.dueDate != null) appendLine("   Срок: ${task.dueDate}")
                appendLine("   ID: ${task.id}")
            }
        }
    }

    private suspend fun extractTaskInfo(llmService: OllamaLlmService, question: String): TaskInfo? {
        val systemPrompt = """
            Извлеки информацию о задаче из вопроса пользователя.
            
            Ответь СТРОГО в формате:
            TITLE: <название задачи>
            DESCRIPTION: <описание задачи>
            PRIORITY: HIGH/MEDIUM/LOW/CRITICAL
            TAGS: <тег1>, <тег2>, ... (или null)
        """.trimIndent()

        val response = llmService.generateAnswer(systemPrompt, "Вопрос: $question")

        val titleRegex = Regex("TITLE:\\s*(.+)", RegexOption.IGNORE_CASE)
        val descRegex = Regex("DESCRIPTION:\\s*(.+)", RegexOption.IGNORE_CASE)
        val priorityRegex = Regex("PRIORITY:\\s*(\\w+)", RegexOption.IGNORE_CASE)
        val tagsRegex = Regex("TAGS:\\s*(.+)", RegexOption.IGNORE_CASE)

        val title = titleRegex.find(response)?.groupValues?.get(1)?.trim() ?: return null
        val description = descRegex.find(response)?.groupValues?.get(1)?.trim() ?: title
        val priorityStr = priorityRegex.find(response)?.groupValues?.get(1)?.uppercase() ?: "MEDIUM"
        val priority = try { TaskPriority.valueOf(priorityStr) } catch (_: Exception) { TaskPriority.MEDIUM }

        val tagsStr = tagsRegex.find(response)?.groupValues?.get(1)?.trim()
        val tags = if (tagsStr != null && tagsStr != "null") {
            tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        return TaskInfo(title, description, priority, tags)
    }

    private fun extractKeyword(question: String): String? {
        val tokens = question
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}_/]+"))
            .filter { it.length >= 3 }
        return tokens.maxByOrNull { it.length }
    }

    private fun buildSnippet(lines: List<String>, centerLine: Int, context: Int): String {
        if (lines.isEmpty()) return "(файл пуст или не прочитан)"
        val start = (centerLine - context - 1).coerceAtLeast(0)
        val end = (centerLine + context - 1).coerceAtMost(lines.lastIndex)
        val builder = StringBuilder()
        for (i in start..end) {
            val lineNumber = i + 1
            builder.append(String.format("%4d | %s%n", lineNumber, lines[i]))
        }
        return builder.toString()
    }

    private inline fun <T> safeCall(block: () -> T): T? =
        try { block() } catch (_: Exception) { null }

    // Функции для анализа логов
    private fun readLogFile(file: File): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()

        file.useLines { lines ->
            lines.forEach { line ->
                try {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val jsonObject = json.parseToJsonElement(trimmed).jsonObject
                        logs.add(parseLogEntry(jsonObject, file.name))
                    }
                } catch (e: Exception) {
                    // Пропускаем некорректные строки
                }
            }
        }

        return logs
    }

    private fun parseLogEntry(jsonObject: JsonObject, fileName: String): LogEntry {
        fun getString(key: String): String? {
            return jsonObject[key]?.jsonPrimitive?.contentOrNull
        }

        fun getInt(key: String): Int? {
            return jsonObject[key]?.jsonPrimitive?.intOrNull
        }

        fun getLong(key: String): Long? {
            return jsonObject[key]?.jsonPrimitive?.longOrNull
        }

        return LogEntry(
            timestamp = getString("ts") ?: "",
            level = getString("level")?.uppercase() ?: "UNKNOWN",
            service = getString("service") ?: "",
            message = getString("message") ?: "",
            requestId = getString("request_id"),
            userId = getString("user_id"),
            method = getString("method"),
            path = getString("path"),
            statusCode = getInt("status_code"),
            latencyMs = getLong("latency_ms"),
            errorCode = getString("error_code"),
            rawJson = jsonObject.toString(),
            sourceFile = fileName
        )
    }

    private fun formatLogsForAnalysis(logs: List<LogEntry>): String {
        val maxLogs = 1000
        val logsToAnalyze = if (logs.size > maxLogs) {
            val errors = logs.filter { it.level == "ERROR" }
            val warnings = logs.filter { it.level == "WARN" }
            val others = logs.filter { it.level !in listOf("ERROR", "WARN") }

            val selected = mutableListOf<LogEntry>()
            selected.addAll(errors)
            selected.addAll(warnings.take(100))

            val remaining = maxLogs - selected.size
            if (remaining > 0) {
                val firstHalf = others.take(remaining / 2)
                val lastHalf = others.takeLast(remaining / 2)
                selected.addAll(firstHalf)
                selected.addAll(lastHalf)
            }

            selected.distinctBy { it.rawJson }.sortedBy { it.timestamp }
        } else {
            logs
        }

        val builder = StringBuilder()
        builder.appendLine("Всего записей в логах: ${logs.size}")
        if (logs.size > maxLogs) {
            builder.appendLine("Для анализа выбрано: ${logsToAnalyze.size} записей")
            builder.appendLine()
        }

        logsToAnalyze.forEach { log ->
            builder.appendLine("---")
            builder.appendLine("Файл: ${log.sourceFile}")
            builder.appendLine("Время: ${log.timestamp}")
            builder.appendLine("Уровень: ${log.level}")
            builder.appendLine("Сервис: ${log.service}")
            if (log.requestId != null) builder.appendLine("Request ID: ${log.requestId}")
            if (log.userId != null) builder.appendLine("User ID: ${log.userId}")
            if (log.method != null && log.path != null) {
                builder.appendLine("Запрос: ${log.method} ${log.path}")
            }
            if (log.statusCode != null) builder.appendLine("Статус: ${log.statusCode}")
            if (log.latencyMs != null) builder.appendLine("Задержка: ${log.latencyMs} мс")
            if (log.errorCode != null) builder.appendLine("Код ошибки: ${log.errorCode}")
            builder.appendLine("Сообщение: ${log.message}")
        }

        return builder.toString()
    }
}

private data class GodAgentIntent(
    val needsRelease: Boolean,
    val needsLogAnalysis: Boolean,
    val needsTaskManagement: Boolean,
    val taskAction: String?,
    val taskFilterPriority: TaskPriority?,
    val taskFilterStatus: TaskStatus?,
    val needsRag: Boolean,
    val needsGitInfo: Boolean,
    val needsCodeSearch: Boolean,
    val codeSearchKeyword: String?,
    val tools: List<String>
)
