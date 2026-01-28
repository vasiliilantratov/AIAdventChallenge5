package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.example.config.AssistantConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.indexing.ProjectIndexer
import org.example.llm.OllamaLlmService
import org.example.mcp.McpClient
import org.example.mcp.TaskManagerMcp
import org.example.model.TaskPriority
import org.example.model.TaskStatus
import org.example.search.LlmReranker
import org.example.search.RagServiceImpl
import org.example.search.SemanticSearch

/**
 * Командный ассистент, объединяющий RAG, MCP и управление задачами.
 * Может создавать задачи, отвечать на вопросы о статусе проекта и давать рекомендации.
 */
class TeamCommand : CliktCommand(
    name = "team",
    help = "Командный ассистент для управления проектом и задачами"
) {
    private val questionParts by argument("question", help = "Вопрос к ассистенту").multiple()
    private val ollamaUrl by option("--ollama-url", help = "URL Ollama").default(AssistantConfig.defaultOllamaUrl)

    override fun run() = runBlocking {
        if (questionParts.isEmpty()) {
            printIntro()
            return@runBlocking
        }

        val question = questionParts.joinToString(" ").trim()
        
        // LLM принимает решение о том, какие инструменты использовать
        val llmService = OllamaLlmService(ollamaUrl)
        val toolsDecision = decideTool(llmService, question)
        
        answerWithTools(question, toolsDecision, llmService)
        
        llmService.close()
    }

    private fun printIntro() {
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║         КОМАНДНЫЙ АССИСТЕНТ - Интегрированная помощь         ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()
        println("Что я умею:")
        println("  📋 Управление задачами: создание, просмотр, изменение статуса")
        println("  📚 Поиск по документации проекта (RAG)")
        println("  💻 Работа с репозиторием через MCP (git, поиск по коду)")
        println("  🎯 Рекомендации по приоритетам и планированию")
        println()
        println("Примеры использования:")
        println()
        println("  Задачи:")
        println("    ./gradlew run --args='team \"покажи задачи с приоритетом high\"'")
        println("    ./gradlew run --args='team \"покажи все задачи в работе\"'")
        println("    ./gradlew run --args='team \"создай задачу: добавить логирование\"'")
        println("    ./gradlew run --args='team \"что делать в первую очередь?\"'")
        println("    ./gradlew run --args='team \"какой статус у проекта?\"'")
        println()
        println("  Документация и код:")
        println("    ./gradlew run --args='team \"как запустить проект?\"'")
        println("    ./gradlew run --args='team \"где реализована аутентификация?\"'")
        println("    ./gradlew run --args='team \"что изменилось в репозитории?\"'")
        println()
        println("  Комплексные вопросы:")
        println("    ./gradlew run --args='team \"покажи критичные задачи и предложи что делать\"'")
        println("    ./gradlew run --args='team \"есть ли блокирующие задачи и как их решить?\"'")
        println()
    }

    private suspend fun decideTool(llmService: OllamaLlmService, question: String): TeamToolsDecision {
        val systemPrompt = """
            Ты — командный ассистент разработчиков. Твоя задача определить, какие инструменты нужны для ответа на вопрос пользователя.
            
            Доступные инструменты:
            1. TASK_MANAGEMENT — работа с задачами команды (просмотр, создание, изменение статуса, фильтрация)
            2. RAG — поиск по документации проекта (README, docs, API specs, schemas, style guides)
            3. GIT_INFO — получение информации о текущей ветке и статусе изменений в git
            4. CODE_SEARCH — поиск по коду проекта (файлы, функции, классы)
            
            Для TASK_MANAGEMENT определи действие:
            - LIST — показать задачи (можно с фильтром по приоритету/статусу)
            - CREATE — создать новую задачу
            - UPDATE_STATUS — изменить статус задачи
            - STATS — показать статистику по задачам
            - RECOMMEND — дать рекомендации по приоритетам
            
            Ответь СТРОГО в формате:
            TASK_MANAGEMENT: yes/no
            TASK_ACTION: LIST/CREATE/UPDATE_STATUS/STATS/RECOMMEND/null
            TASK_FILTER_PRIORITY: HIGH/MEDIUM/LOW/CRITICAL/null
            TASK_FILTER_STATUS: TODO/IN_PROGRESS/DONE/BLOCKED/null
            RAG: yes/no
            GIT_INFO: yes/no
            CODE_SEARCH: yes/no
            CODE_KEYWORD: <ключевое слово для поиска или null>
            
            Примеры:
            - "покажи задачи с приоритетом high" → TASK_MANAGEMENT: yes, TASK_ACTION: LIST, TASK_FILTER_PRIORITY: HIGH, RAG: no, GIT_INFO: no, CODE_SEARCH: no
            - "создай задачу добавить логирование" → TASK_MANAGEMENT: yes, TASK_ACTION: CREATE, RAG: no, GIT_INFO: no, CODE_SEARCH: no
            - "что делать в первую очередь?" → TASK_MANAGEMENT: yes, TASK_ACTION: RECOMMEND, RAG: no, GIT_INFO: no, CODE_SEARCH: no
            - "покажи критичные задачи и как их решить" → TASK_MANAGEMENT: yes, TASK_ACTION: LIST, TASK_FILTER_PRIORITY: CRITICAL, RAG: yes, GIT_INFO: no, CODE_SEARCH: yes
            - "как запустить проект?" → TASK_MANAGEMENT: no, RAG: yes, GIT_INFO: no, CODE_SEARCH: no
            - "есть ли блокирующие задачи?" → TASK_MANAGEMENT: yes, TASK_ACTION: LIST, TASK_FILTER_STATUS: BLOCKED, RAG: no, GIT_INFO: no, CODE_SEARCH: no
        """.trimIndent()

        val userMessage = "Вопрос: $question"
        
        val response = llmService.generateAnswer(systemPrompt, userMessage)
        
        // Парсим ответ LLM
        val useTaskManagement = response.contains("TASK_MANAGEMENT: yes", ignoreCase = true)
        val useRag = response.contains("RAG: yes", ignoreCase = true)
        val useGitInfo = response.contains("GIT_INFO: yes", ignoreCase = true)
        val useCodeSearch = response.contains("CODE_SEARCH: yes", ignoreCase = true)
        
        // Парсим действие с задачами
        val taskActionRegex = Regex("TASK_ACTION:\\s*(\\w+)", RegexOption.IGNORE_CASE)
        val taskActionMatch = taskActionRegex.find(response)
        val taskAction = taskActionMatch?.groupValues?.get(1)?.uppercase()?.takeIf { 
            it != "NULL" && it.isNotEmpty() 
        }
        
        // Парсим фильтр приоритета
        val taskPriorityRegex = Regex("TASK_FILTER_PRIORITY:\\s*(\\w+)", RegexOption.IGNORE_CASE)
        val taskPriorityMatch = taskPriorityRegex.find(response)
        val taskPriority = taskPriorityMatch?.groupValues?.get(1)?.uppercase()?.takeIf { 
            it != "NULL" && it.isNotEmpty() 
        }?.let { 
            try { TaskPriority.valueOf(it) } catch (_: Exception) { null }
        }
        
        // Парсим фильтр статуса
        val taskStatusRegex = Regex("TASK_FILTER_STATUS:\\s*(\\w+)", RegexOption.IGNORE_CASE)
        val taskStatusMatch = taskStatusRegex.find(response)
        val taskStatus = taskStatusMatch?.groupValues?.get(1)?.uppercase()?.takeIf { 
            it != "NULL" && it.isNotEmpty() 
        }?.let { 
            try { TaskStatus.valueOf(it) } catch (_: Exception) { null }
        }
        
        // Парсим ключевое слово для поиска
        val keywordRegex = Regex("CODE_KEYWORD:\\s*(.+)", RegexOption.IGNORE_CASE)
        val keywordMatch = keywordRegex.find(response)
        val keyword = keywordMatch?.groupValues?.get(1)?.trim()?.takeIf { 
            it != "null" && it.isNotEmpty() 
        }
        
        return TeamToolsDecision(
            useTaskManagement = useTaskManagement,
            taskAction = taskAction,
            taskFilterPriority = taskPriority,
            taskFilterStatus = taskStatus,
            useRag = useRag,
            useGitInfo = useGitInfo,
            useCodeSearch = useCodeSearch,
            codeSearchKeyword = keyword
        )
    }

    private suspend fun answerWithTools(
        question: String,
        decision: TeamToolsDecision,
        llmService: OllamaLlmService
    ) {
        val contextParts = mutableListOf<String>()
        val sources = mutableListOf<String>()
        
        // 1. TASK_MANAGEMENT — работа с задачами
        if (decision.useTaskManagement) {
            val taskManager = TaskManagerMcp()
            
            when (decision.taskAction) {
                "LIST" -> {
                    val tasks = when {
                        decision.taskFilterPriority != null -> 
                            taskManager.getTasksByPriority(decision.taskFilterPriority)
                        decision.taskFilterStatus != null -> 
                            taskManager.getTasksByStatus(decision.taskFilterStatus)
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
                    // Извлекаем информацию о задаче из вопроса через LLM
                    val taskInfo = extractTaskInfo(llmService, question)
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
                    } else {
                        contextParts.add("Не удалось извлечь информацию для создания задачи. Укажи название и описание задачи.")
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
                    contextParts.add("Критичных: ${stats.byPriority[TaskPriority.CRITICAL] ?: 0}")
                    contextParts.add("Высокого приоритета: ${stats.byPriority[TaskPriority.HIGH] ?: 0}")
                    
                    val critical = allTasks.filter { 
                        it.priority == TaskPriority.CRITICAL && it.status != TaskStatus.DONE 
                    }
                    val blocked = allTasks.filter { it.status == TaskStatus.BLOCKED }
                    val highPriority = allTasks.filter { 
                        it.priority == TaskPriority.HIGH && it.status == TaskStatus.TODO 
                    }
                    
                    if (critical.isNotEmpty()) {
                        contextParts.add("\n🚨 Критичные задачи:")
                        critical.forEach { contextParts.add(formatTask(it, brief = true)) }
                    }
                    
                    if (blocked.isNotEmpty()) {
                        contextParts.add("\n🔒 Заблокированные задачи:")
                        blocked.forEach { contextParts.add(formatTask(it, brief = true)) }
                    }
                    
                    if (highPriority.isNotEmpty()) {
                        contextParts.add("\n⚡ Важные задачи в очереди:")
                        highPriority.take(3).forEach { contextParts.add(formatTask(it, brief = true)) }
                    }
                    
                    sources.add("tasks.json")
                }
                
                else -> {
                    // Показываем все задачи по умолчанию
                    val tasks = taskManager.getAllTasks()
                    contextParts.add("=== Все задачи команды ===")
                    tasks.forEach { task ->
                        contextParts.add(formatTask(task))
                    }
                    sources.add("tasks.json")
                }
            }
        }
        
        // 2. RAG — поиск по документации
        if (decision.useRag) {
            ProjectIndexer.ensureIndexed(ollamaUrl = ollamaUrl, dbPath = AssistantConfig.dbPath)
            DatabaseManager.initialize(AssistantConfig.dbPath)
            
            val repository = Repository()
            val embeddingService = OllamaEmbeddingService(ollamaUrl)
            val semanticSearch = SemanticSearch(repository, embeddingService)
            val ragService = RagServiceImpl(
                semanticSearch = semanticSearch,
                embeddingService = embeddingService,
                llmService = llmService,
                reranker = LlmReranker(llmService)
            )
            
            val ragAnswer = ragService.answerWithRag(
                question = question,
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
        
        // 3. GIT_INFO — информация о ветке и статусе
        if (decision.useGitInfo) {
            val mcp = McpClient()
            val branch = safeCall { mcp.gitBranch() }
            val status = safeCall { mcp.gitStatus() }
            
            contextParts.add("=== Git информация ===")
            contextParts.add("Текущая ветка: ${branch ?: "неизвестно"}")
            contextParts.add("Статус: ${if (status.isNullOrBlank()) "нет изменений" else "\n$status"}")
        }
        
        // 4. CODE_SEARCH — поиск по коду
        if (decision.useCodeSearch) {
            val mcp = McpClient()
            val keyword = decision.codeSearchKeyword ?: extractKeyword(question)
            
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
        
        // Если нет контекста, сообщаем об этом
        if (contextParts.isEmpty()) {
            println("Не удалось найти релевантную информацию.")
            println("Попробуй переформулировать вопрос или уточнить детали.")
            return
        }
        
        // 5. Формируем финальный ответ через LLM
        val fullContext = contextParts.joinToString("\n\n")
        
        val finalSystemPrompt = """
            Ты — командный ассистент разработчиков.
            Отвечай кратко (3-10 предложений), конкретно и по делу.
            Используй только предоставленную информацию.
            Если задач много, приоритизируй самые важные (критичные, заблокированные, высокий приоритет).
            Давай конкретные рекомендации и следующие шаги.
            Отвечай на русском языке.
        """.trimIndent()
        
        val finalUserMessage = """
            Вопрос: $question
            
            Доступная информация:
            $fullContext
        """.trimIndent()
        
        val finalAnswer = llmService.generateAnswer(finalSystemPrompt, finalUserMessage)
        
        // Выводим результат
        println()
        println("═══════════════════════════════════════════════════════════════")
        println(finalAnswer)
        println("═══════════════════════════════════════════════════════════════")
        println()
        
        if (sources.isNotEmpty()) {
            println("📚 Источники:")
            sources.distinct().forEachIndexed { idx, source ->
                println("   ${idx + 1}. $source")
            }
            println()
        }
        
        // Рекомендации следующих шагов
        println("💡 Следующие шаги:")
        when {
            decision.useTaskManagement && decision.taskAction == "RECOMMEND" -> {
                println("   - Начни с критичных и заблокированных задач")
                println("   - Проверь, нужны ли дополнительные ресурсы для разблокировки задач")
            }
            decision.useTaskManagement && decision.taskAction == "LIST" -> {
                println("   - Используй фильтры для просмотра задач по статусу или приоритету")
                println("   - Спроси про рекомендации: \"что делать в первую очередь?\"")
            }
            decision.useCodeSearch -> {
                println("   - Открой найденные файлы для детального изучения кода")
            }
            decision.useRag -> {
                println("   - Уточни вопрос для более детального ответа")
            }
        }
        println()
    }

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
            
            Если информации недостаточно, используй разумные значения по умолчанию:
            - PRIORITY по умолчанию MEDIUM
            - DESCRIPTION по умолчанию может быть расширенной версией TITLE
        """.trimIndent()
        
        val response = llmService.generateAnswer(systemPrompt, "Вопрос: $question")
        
        // Парсим ответ
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
}

data class TeamToolsDecision(
    val useTaskManagement: Boolean,
    val taskAction: String?,
    val taskFilterPriority: TaskPriority?,
    val taskFilterStatus: TaskStatus?,
    val useRag: Boolean,
    val useGitInfo: Boolean,
    val useCodeSearch: Boolean,
    val codeSearchKeyword: String?
)

data class TaskInfo(
    val title: String,
    val description: String,
    val priority: TaskPriority,
    val tags: List<String>
)
