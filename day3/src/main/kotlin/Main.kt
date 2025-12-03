package org.example

/**
 * Точка входа приложения для формирования резюме задачи
 */
fun main() {
    // Инициализация сервисов
    val chatClient = OllamaChatClient()
    val aiDialogService = AiDialogService(chatClient)
    val dialogManager = DialogManager(aiDialogService)
    
    // Вывод приветственных сообщений
    println("=== Ассистент для формирования резюме задачи ===")
    println("Опишите задачу или задайте вопрос в одной фразе.")
    println("Команды: 'exit' — выход, 'summary' — сформировать резюме (можно в любой момент), 'new' — новая задача.")
    println("Резюме будет сформировано автоматически, когда соберётся достаточно информации.")
    println()
    
    // Основной диалоговый цикл
    while (true) {
        // Определяем приглашение в зависимости от состояния
        val prompt = when (dialogManager.getCurrentState()) {
            is DialogState.WaitingForInitialRequest -> "Запрос> "
            is DialogState.InClarificationDialog -> "Ответ> "
            is DialogState.ReadyForSummary -> "Ответ> "
        }
        
        print(prompt)
        
        // Чтение ввода пользователя
        val userInput = readlnOrNull()
        
        // Проверка на null (EOF)
        if (userInput == null) {
            println("\nЗавершение работы. Пока!")
            break
        }
        
        val trimmedInput = userInput.trim()
        
        // Обработка пустого ввода
        if (trimmedInput.isEmpty()) {
            continue
        }
        
        // Обработка ввода через DialogManager
        val action = dialogManager.processInput(trimmedInput)
        
        // Выполнение действия
        when (action) {
            is DialogAction.ShowQuestion -> {
                println()
                println("ИИ-вопрос:")
                println(action.question)
                println()
            }
            is DialogAction.ShowMessage -> {
                println(action.message)
            }
            is DialogAction.ShowSummary -> {
                println()
                displaySummary(action.summary)
                println()
            }
            is DialogAction.ShowError -> {
                println("Ошибка: ${action.error}")
                println("Попробуйте ещё раз или введите 'exit' для завершения программы.")
            }
            is DialogAction.Exit -> {
                println("Завершение работы. Пока!")
                break
            }
            is DialogAction.Reset -> {
                println()
                println("=== Начало новой задачи ===")
                println("Опишите задачу или задайте вопрос в одной фразе.")
                println()
            }
        }
    }
}

/**
 * Отображает итоговое резюме задачи в терминале
 */
fun displaySummary(summary: TaskSummary) {
    println("=== Итоговое резюме задачи ===")
    println()
    
    println("1. Исходный запрос")
    println(summary.initialRequest)
    println()
    
    println("2. Краткая формулировка задачи")
    println(summary.briefFormulation)
    println()
    
    println("3. Цели и ожидаемый результат")
    summary.goalsAndExpectedResult.forEach { goal ->
        println("- $goal")
    }
    println()
    
    println("4. Целевая аудитория")
    println(summary.targetAudience)
    println()
    
    println("5. Контекст и область применения")
    println(summary.contextAndApplication)
    println()
    
    println("6. Требования и пожелания")
    println("Функциональные требования:")
    summary.requirements.functional.forEach { req ->
        println("- $req")
    }
    println()
    println("Нефункциональные требования:")
    summary.requirements.nonFunctional.forEach { req ->
        println("- $req")
    }
    println()
    println("Формат итогового результата:")
    println(summary.requirements.outputFormat)
    println()
    
    println("7. Ограничения")
    println(summary.constraints)
    println()
    
    println("8. Критерии успеха")
    summary.successCriteria.forEach { criterion ->
        println("- $criterion")
    }
    println()
    
    println("9. Дополнительные замечания и примеры")
    println(summary.additionalNotes)
    println()
}
