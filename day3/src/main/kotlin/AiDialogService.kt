package org.example

import kotlinx.serialization.json.Json

/**
 * Сервис для взаимодействия с ИИ-слоем
 * Инкапсулирует логику анализа запросов, генерации вопросов и формирования резюме
 */
class AiDialogService(private val chatClient: OllamaChatClient) {
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private var conversationHistory = mutableListOf<ConversationTurn>()
    private var initialRequest: String = ""
    private var lastQuestion: String = "" // Храним последний заданный вопрос
    
    /**
     * Анализирует исходный запрос и генерирует первый уточняющий вопрос
     */
    fun analyzeInitialRequest(request: String): AiResponse {
        initialRequest = request
        conversationHistory.clear()
        lastQuestion = ""
        
        val systemPrompt = createInitialAnalysisPrompt()
        chatClient.clearHistory()
        chatClient.setSystemMessage(systemPrompt)
        
        val userPrompt = """
            Пользователь задал следующий запрос:
            "$request"
            
            Проанализируй запрос и задай первый уточняющий вопрос, который поможет лучше понять задачу.
            Вопрос должен быть конкретным и направленным на сбор важной информации.
            Если возможно, предложи варианты ответов для помощи в выборе.
        """.trimIndent()
        
        return try {
            val response = chatClient.sendMessage(userPrompt)
            if (response.startsWith("Ошибка")) {
                AiResponse.Error(response)
            } else {
                val parsed = parseQuestionWithOptions(response.trim())
                lastQuestion = parsed.first
                AiResponse.Question(parsed.first, parsed.second)
            }
        } catch (e: Exception) {
            AiResponse.Error("Ошибка при обращении к ИИ: ${e.message}")
        }
    }
    
    /**
     * Обрабатывает ответ пользователя и генерирует следующий вопрос или сообщает о готовности
     */
    fun processAnswer(answer: String): AiResponse {
        // Сохраняем пару вопрос-ответ в историю
        if (lastQuestion.isNotEmpty()) {
            conversationHistory.add(ConversationTurn(lastQuestion, answer))
        }
        
        // Проверяем, не является ли ответ исправлением
        val isCorrection = detectCorrection(answer)
        
        val systemPrompt = createClarificationPrompt()
        chatClient.clearHistory()
        chatClient.setSystemMessage(systemPrompt)
        
        val contextPrompt = buildContextPrompt(isCorrection)
        
        return try {
            val response = chatClient.sendMessage(contextPrompt)
            if (response.startsWith("Ошибка")) {
                AiResponse.Error(response)
            } else {
                // Проверяем, готов ли ИИ к резюме
                val lowerResponse = response.lowercase()
                if (lowerResponse.contains("достаточно") || 
                    lowerResponse.contains("готов") || 
                    lowerResponse.contains("можно формировать резюме")) {
                    AiResponse.ReadyToSummarize
                } else {
                    // Парсим вопрос и варианты
                    val parsed = parseQuestionWithOptions(response.trim())
                    lastQuestion = parsed.first
                    AiResponse.Question(parsed.first, parsed.second)
                }
            }
        } catch (e: Exception) {
            AiResponse.Error("Ошибка при обращении к ИИ: ${e.message}")
        }
    }
    
    /**
     * Генерирует итоговое резюме задачи
     */
    fun generateSummary(): AiResponse {
        val systemPrompt = createSummaryGenerationPrompt()
        chatClient.clearHistory()
        chatClient.setSystemMessage(systemPrompt)
        
        val contextPrompt = buildSummaryContextPrompt()
        
        return try {
            val response = chatClient.sendMessage(contextPrompt)
            if (response.startsWith("Ошибка")) {
                AiResponse.Error(response)
            } else {
                // Парсим резюме из ответа ИИ
                val summary = parseSummaryFromResponse(response)
                AiResponse.Summary(summary)
            }
        } catch (e: Exception) {
            AiResponse.Error("Ошибка при генерации резюме: ${e.message}")
        }
    }
    
    /**
     * Возвращает текущую историю диалога
     */
    fun getConversationHistory(): List<ConversationTurn> = conversationHistory.toList()
    
    /**
     * Возвращает исходный запрос
     */
    fun getInitialRequest(): String = initialRequest
    
    /**
     * Очищает состояние сервиса для новой задачи
     */
    fun reset() {
        initialRequest = ""
        conversationHistory.clear()
        lastQuestion = ""
        chatClient.clearHistory()
    }
    
    /**
     * Определяет, является ли ответ исправлением предыдущей информации
     */
    private fun detectCorrection(answer: String): Boolean {
        val correctionKeywords = listOf(
            "исправь", "исправить", "неправильно", "не так", "измени", 
            "изменить", "другое", "другой", "не", "а", "вернее"
        )
        val lowerAnswer = answer.lowercase()
        return correctionKeywords.any { lowerAnswer.contains(it) }
    }
    
    /**
     * Создает системный промпт для начального анализа
     */
    private fun createInitialAnalysisPrompt(): String {
        return """Ты — ассистент для формирования резюме задачи. 
Твоя задача — задавать уточняющие вопросы, чтобы собрать полную информацию о задаче пользователя.

Правила:
- Задавай вопросы по одному
- Вопросы должны быть конкретными и направленными
- Не задавай слишком общих вопросов
- Фокусируйся на важных аспектах: цели, аудитория, требования, ограничения, сроки, бюджет
- Когда возможно, предлагай варианты ответов, чтобы помочь пользователю сделать выбор
- Формат вопроса с вариантами:
  ВОПРОС: [текст вопроса]
  ВАРИАНТЫ:
  1. [вариант 1]
  2. [вариант 2]
  3. [вариант 3]
  (можно добавить "4. Другое" если нужен свободный ответ)
- Если варианты не нужны, просто задай открытый вопрос"""
    }
    
    /**
     * Создает системный промпт для диалога уточнений
     */
    private fun createClarificationPrompt(): String {
        return """Ты — ассистент для формирования резюме задачи. 
Твоя задача — задавать уточняющие вопросы на основе ответов пользователя.

Правила:
- Задавай вопросы по одному
- Учитывай все предыдущие ответы пользователя
- Не повторяй уже заданные вопросы
- Если пользователь исправляет информацию, учитывай исправления
- Когда соберешь достаточно информации, напиши: "Мне кажется, информации достаточно для резюме"
- Вопросы должны быть конкретными и направленными
- Когда возможно, предлагай варианты ответов, чтобы помочь пользователю сделать выбор
- Формат вопроса с вариантами:
  ВОПРОС: [текст вопроса]
  ВАРИАНТЫ:
  1. [вариант 1]
  2. [вариант 2]
  3. [вариант 3]
  (можно добавить "4. Другое" если нужен свободный ответ)
- Если варианты не нужны, просто задай открытый вопрос"""
    }
    
    /**
     * Создает контекстный промпт для обработки ответа
     */
    private fun buildContextPrompt(isCorrection: Boolean): String {
        val correctionNote = if (isCorrection) {
            "\nВНИМАНИЕ: Пользователь, возможно, исправляет предыдущую информацию. Учти это."
        } else ""
        
        val historyText = if (conversationHistory.isEmpty()) {
            "История диалога пока пуста."
        } else {
            conversationHistory.joinToString("\n") { turn ->
                "Вопрос: ${turn.question}\nОтвет: ${turn.answer}"
            }
        }
        
        return """
            Исходный запрос пользователя: "$initialRequest"
            
            История диалога:
            $historyText
            $correctionNote
            
            На основе этой информации задай следующий уточняющий вопрос.
            Если информации достаточно для формирования резюме, напиши: "Мне кажется, информации достаточно для резюме"
            Иначе задай один конкретный вопрос.
            
            Когда возможно, предложи варианты ответов, чтобы помочь пользователю сделать выбор.
            Формат вопроса с вариантами:
            ВОПРОС: [текст вопроса]
            ВАРИАНТЫ:
            1. [вариант 1]
            2. [вариант 2]
            3. [вариант 3]
            (можно добавить "4. Другое" если нужен свободный ответ)
            
            Если варианты не нужны, просто задай открытый вопрос.
        """.trimIndent()
    }
    
    /**
     * Создает системный промпт для генерации резюме
     */
    private fun createSummaryGenerationPrompt(): String {
        return """Ты — ассистент для формирования структурированного резюме задачи.
Твоя задача — на основе исходного запроса и всех ответов пользователя создать подробное резюме задачи.

Резюме должно быть структурированным и содержать все необходимые разделы."""
    }
    
    /**
     * Создает контекстный промпт для генерации резюме
     */
    private fun buildSummaryContextPrompt(): String {
        val historyText = conversationHistory.joinToString("\n") { turn ->
            "Вопрос: ${turn.question}\nОтвет: ${turn.answer}"
        }
        
        return """
            Сформируй структурированное резюме задачи на основе следующей информации:
            
            Исходный запрос: "$initialRequest"
            
            История диалога:
            $historyText
            
            Сформируй резюме в следующей структуре (выводи каждый раздел с заголовком):
            
            1. Исходный запрос
            [исходный запрос пользователя]
            
            2. Краткая формулировка задачи
            [1-3 предложения, суммирующие суть задачи]
            
            3. Цели и ожидаемый результат
            - [список целей и ожидаемых результатов]
            
            4. Целевая аудитория
            [описание пользователей/аудитории]
            
            5. Контекст и область применения
            [краткое описание бизнес-контекста]
            
            6. Требования и пожелания
            Функциональные требования:
            - [список функциональных требований]
            
            Нефункциональные требования:
            - [список нефункциональных требований]
            
            Формат итогового результата:
            [описание формата результата]
            
            7. Ограничения
            [сроки, бюджет, технологии, ресурсы и другие ограничения]
            
            8. Критерии успеха
            - [список критериев успешности]
            
            9. Решение или план решения
            [предложи конкретное решение проблемы или детальный план действий для решения задачи.
            План должен включать этапы, шаги, необходимые ресурсы и последовательность выполнения]
            
            10. Дополнительные замечания и примеры
            [аналоги, референсы, предпочтения, анти-примеры]
            
            Выведи резюме в текстовом формате с четкими заголовками разделов.
        """.trimIndent()
    }
    
    /**
     * Парсит резюме из ответа ИИ
     * Поскольку ИИ возвращает текст, мы создаем структуру на основе парсинга
     */
    private fun parseSummaryFromResponse(response: String): TaskSummary {
        // Простой парсинг текстового ответа
        // В реальном приложении можно использовать более сложный парсинг или JSON
        
        val lines = response.lines()
        var currentSection = ""
        val sections = mutableMapOf<String, StringBuilder>()
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            // Определяем раздел по заголовку
            when {
                trimmed.contains("Исходный запрос", ignoreCase = true) -> {
                    currentSection = "initial"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Краткая формулировка", ignoreCase = true) -> {
                    currentSection = "brief"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Цели и ожидаемый результат", ignoreCase = true) -> {
                    currentSection = "goals"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Целевая аудитория", ignoreCase = true) -> {
                    currentSection = "audience"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Контекст и область применения", ignoreCase = true) -> {
                    currentSection = "context"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Требования и пожелания", ignoreCase = true) -> {
                    currentSection = "requirements"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Ограничения", ignoreCase = true) -> {
                    currentSection = "constraints"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Критерии успеха", ignoreCase = true) -> {
                    currentSection = "criteria"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Решение или план", ignoreCase = true) -> {
                    currentSection = "solution"
                    sections[currentSection] = StringBuilder()
                }
                trimmed.contains("Дополнительные замечания", ignoreCase = true) -> {
                    currentSection = "notes"
                    sections[currentSection] = StringBuilder()
                }
                else -> {
                    if (currentSection.isNotEmpty()) {
                        sections[currentSection]?.appendLine(trimmed)
                    }
                }
            }
        }
        
        // Извлекаем данные из секций
        val goals = extractListItems(sections["goals"]?.toString() ?: "")
        val functionalReqs = extractListItems(
            sections["requirements"]?.toString()?.substringBefore("Нефункциональные") ?: ""
        )
        val nonFunctionalReqs = extractListItems(
            sections["requirements"]?.toString()?.substringAfter("Нефункциональные")?.substringBefore("Формат") ?: ""
        )
        val outputFormat = sections["requirements"]?.toString()?.substringAfter("Формат итогового результата:")?.trim() ?: ""
        val criteria = extractListItems(sections["criteria"]?.toString() ?: "")
        
        return TaskSummary(
            initialRequest = initialRequest,
            briefFormulation = sections["brief"]?.toString()?.trim() ?: "Не указано",
            goalsAndExpectedResult = if (goals.isEmpty()) listOf("Не указано") else goals,
            targetAudience = sections["audience"]?.toString()?.trim() ?: "Не указано",
            contextAndApplication = sections["context"]?.toString()?.trim() ?: "Не указано",
            requirements = Requirements(
                functional = if (functionalReqs.isEmpty()) listOf("Не указано") else functionalReqs,
                nonFunctional = if (nonFunctionalReqs.isEmpty()) listOf("Не указано") else nonFunctionalReqs,
                outputFormat = outputFormat.ifEmpty { "Не указано" }
            ),
            constraints = sections["constraints"]?.toString()?.trim() ?: "Не указано",
            successCriteria = if (criteria.isEmpty()) listOf("Не указано") else criteria,
            solutionOrPlan = sections["solution"]?.toString()?.trim() ?: "Не указано",
            additionalNotes = sections["notes"]?.toString()?.trim() ?: "Не указано"
        )
    }
    
    /**
     * Извлекает элементы списка из текста (строки, начинающиеся с "-" или цифр)
     */
    private fun extractListItems(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.matches(Regex("^\\d+[.)]\\s+.*")) }
            .map { it.replace(Regex("^[-\\d+.)]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
    }
    
    /**
     * Парсит вопрос и варианты ответов из ответа ИИ
     * Возвращает пару (вопрос, список вариантов)
     */
    private fun parseQuestionWithOptions(response: String): Pair<String, List<String>> {
        val lines = response.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        // Ищем формат "ВОПРОС: ..." и "ВАРИАНТЫ:"
        val questionParts = mutableListOf<String>()
        val options = mutableListOf<String>()
        var inOptionsSection = false
        var foundQuestionHeader = false
        
        for (line in lines) {
            when {
                line.startsWith("ВОПРОС:", ignoreCase = true) -> {
                    foundQuestionHeader = true
                    val questionPart = line.substringAfter("ВОПРОС:").trim()
                    if (questionPart.isNotEmpty()) {
                        questionParts.add(questionPart)
                    }
                }
                line.startsWith("ВАРИАНТЫ:", ignoreCase = true) -> {
                    inOptionsSection = true
                }
                inOptionsSection && line.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                    // Извлекаем вариант (убираем номер и точку/скобку)
                    val option = line.replace(Regex("^\\d+[.)]\\s+"), "").trim()
                    if (option.isNotEmpty()) {
                        options.add(option)
                    }
                }
                !inOptionsSection && !foundQuestionHeader -> {
                    // Накапливаем текст вопроса до секции вариантов
                    questionParts.add(line)
                }
            }
        }
        
        val questionText = if (questionParts.isNotEmpty()) {
            questionParts.joinToString(" ").trim()
        } else {
            ""
        }
        
        // Если не нашли структурированный формат, пытаемся найти варианты в тексте
        if (options.isEmpty() && questionText.isEmpty()) {
            // Пробуем найти варианты в обычном тексте (ищем строки вида "1. текст" или "1) текст")
            val optionPattern = Regex("^\\s*(\\d+)[.)]\\s+(.+)$", RegexOption.MULTILINE)
            val matches = optionPattern.findAll(response)
            val foundOptions = matches.map { it.groupValues[2].trim() }.toList()
            
            if (foundOptions.isNotEmpty()) {
                options.addAll(foundOptions)
                // Извлекаем вопрос (все до первого варианта)
                val firstMatch = optionPattern.find(response)
                if (firstMatch != null) {
                    val questionPart = response.substring(0, firstMatch.range.first).trim()
                        .replace(Regex("(ВОПРОС:|ВАРИАНТЫ:)", RegexOption.IGNORE_CASE), "")
                        .trim()
                    return Pair(if (questionPart.isNotEmpty()) questionPart else response.trim(), options)
                }
            }
            
            // Просто открытый вопрос без вариантов
            return Pair(response.trim(), emptyList())
        }
        
        // Если вопрос пустой, берем весь ответ
        val finalQuestion = if (questionText.isEmpty()) response.trim() else questionText
        
        return Pair(finalQuestion, options)
    }
}

