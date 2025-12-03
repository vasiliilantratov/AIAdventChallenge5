package org.example

/**
 * Менеджер диалога - управляет состоянием диалога и обрабатывает команды
 */
class DialogManager(private val aiDialogService: AiDialogService) {
    private var currentState: DialogState = DialogState.WaitingForInitialRequest
    
    /**
     * Обрабатывает ввод пользователя и возвращает результат обработки
     */
    fun processInput(input: String): DialogAction {
        val trimmedInput = input.trim()
        
        // Обработка команд
        when (trimmedInput.lowercase()) {
            "exit" -> return DialogAction.Exit
            "summary" -> return handleSummaryCommand()
            "new" -> return handleNewCommand()
        }
        
        // Обработка в зависимости от состояния
        return when (currentState) {
            is DialogState.WaitingForInitialRequest -> {
                handleInitialRequest(trimmedInput)
            }
            is DialogState.InClarificationDialog -> {
                handleClarificationAnswer(trimmedInput)
            }
            is DialogState.ReadyForSummary -> {
                // В состоянии готовности можно либо продолжить диалог, либо сформировать резюме
                if (trimmedInput.lowercase() == "summary") {
                    handleSummaryCommand()
                } else {
                    // Продолжаем диалог
                    handleClarificationAnswer(trimmedInput)
                }
            }
        }
    }
    
    /**
     * Обрабатывает исходный запрос пользователя
     */
    private fun handleInitialRequest(request: String): DialogAction {
        if (request.isEmpty()) {
            return DialogAction.ShowMessage("Пожалуйста, введите непустой запрос.")
        }
        
        val response = aiDialogService.analyzeInitialRequest(request)
        
        return when (response) {
            is AiResponse.Question -> {
                currentState = DialogState.InClarificationDialog(
                    initialRequest = aiDialogService.getInitialRequest(),
                    conversationHistory = aiDialogService.getConversationHistory()
                )
                DialogAction.ShowQuestion(response.text)
            }
            is AiResponse.Error -> {
                DialogAction.ShowError(response.message)
            }
            else -> {
                DialogAction.ShowError("Неожиданный ответ от ИИ")
            }
        }
    }
    
    /**
     * Обрабатывает ответ пользователя в диалоге уточнений
     */
    private fun handleClarificationAnswer(answer: String): DialogAction {
        if (answer.isEmpty()) {
            return DialogAction.ShowMessage("Пожалуйста, введите ответ.")
        }
        
        val response = aiDialogService.processAnswer(answer)
        
        return when (response) {
            is AiResponse.Question -> {
                // Обновляем состояние с актуальной историей из сервиса
                currentState = DialogState.InClarificationDialog(
                    initialRequest = aiDialogService.getInitialRequest(),
                    conversationHistory = aiDialogService.getConversationHistory()
                )
                DialogAction.ShowQuestion(response.text)
            }
            is AiResponse.ReadyToSummarize -> {
                // Автоматически формируем резюме
                val summaryResponse = aiDialogService.generateSummary()
                when (summaryResponse) {
                    is AiResponse.Summary -> {
                        currentState = DialogState.ReadyForSummary(
                            initialRequest = aiDialogService.getInitialRequest(),
                            conversationHistory = aiDialogService.getConversationHistory()
                        )
                        DialogAction.ShowSummary(summaryResponse.taskSummary)
                    }
                    is AiResponse.Error -> {
                        DialogAction.ShowError(summaryResponse.message)
                    }
                    else -> {
                        DialogAction.ShowError("Ошибка при генерации резюме")
                    }
                }
            }
            is AiResponse.Error -> {
                DialogAction.ShowError(response.message)
            }
            else -> {
                DialogAction.ShowError("Неожиданный ответ от ИИ")
            }
        }
    }
    
    /**
     * Обрабатывает команду формирования резюме
     */
    private fun handleSummaryCommand(): DialogAction {
        val response = aiDialogService.generateSummary()
        
        return when (response) {
            is AiResponse.Summary -> {
                DialogAction.ShowSummary(response.taskSummary)
            }
            is AiResponse.Error -> {
                DialogAction.ShowError(response.message)
            }
            else -> {
                DialogAction.ShowError("Ошибка при генерации резюме")
            }
        }
    }
    
    /**
     * Обрабатывает команду начала новой задачи
     */
    private fun handleNewCommand(): DialogAction {
        aiDialogService.reset()
        currentState = DialogState.WaitingForInitialRequest
        return DialogAction.Reset
    }
    
    /**
     * Возвращает текущее состояние диалога
     */
    fun getCurrentState(): DialogState = currentState
}

/**
 * Действия, которые должен выполнить UI после обработки ввода
 */
sealed class DialogAction {
    /**
     * Показать вопрос пользователю
     */
    data class ShowQuestion(val question: String) : DialogAction()
    
    /**
     * Показать сообщение
     */
    data class ShowMessage(val message: String) : DialogAction()
    
    /**
     * Показать резюме
     */
    data class ShowSummary(val summary: TaskSummary) : DialogAction()
    
    /**
     * Показать ошибку
     */
    data class ShowError(val error: String) : DialogAction()
    
    /**
     * Завершить приложение
     */
    object Exit : DialogAction()
    
    /**
     * Сбросить состояние (новая задача)
     */
    object Reset : DialogAction()
}

