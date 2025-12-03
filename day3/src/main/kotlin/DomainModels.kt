package org.example

/**
 * Модели данных для резюме задачи
 */

/**
 * Итоговое резюме задачи
 */
data class TaskSummary(
    val initialRequest: String,
    val briefFormulation: String,
    val goalsAndExpectedResult: List<String>,
    val targetAudience: String,
    val contextAndApplication: String,
    val requirements: Requirements,
    val constraints: String,
    val successCriteria: List<String>,
    val solutionOrPlan: String,
    val additionalNotes: String
)

/**
 * Требования и пожелания
 */
data class Requirements(
    val functional: List<String>,
    val nonFunctional: List<String>,
    val outputFormat: String
)

/**
 * Состояние диалога
 */
sealed class DialogState {
    /**
     * Ожидание исходного запроса
     */
    object WaitingForInitialRequest : DialogState()
    
    /**
     * Диалог уточняющих вопросов
     */
    data class InClarificationDialog(
        val initialRequest: String,
        val conversationHistory: List<ConversationTurn>
    ) : DialogState()
    
    /**
     * Готовность к формированию резюме
     */
    data class ReadyForSummary(
        val initialRequest: String,
        val conversationHistory: List<ConversationTurn>
    ) : DialogState()
}

/**
 * Один ход в диалоге (вопрос-ответ)
 */
data class ConversationTurn(
    val question: String,
    val answer: String
)

/**
 * Результат анализа запроса ИИ
 */
sealed class AiResponse {
    /**
     * ИИ задает уточняющий вопрос (открытый или с вариантами)
     */
    data class Question(
        val text: String,
        val options: List<String> = emptyList()
    ) : AiResponse()
    
    /**
     * ИИ считает, что информации достаточно
     */
    object ReadyToSummarize : AiResponse()
    
    /**
     * ИИ сгенерировал резюме
     */
    data class Summary(val taskSummary: TaskSummary) : AiResponse()
    
    /**
     * Ошибка при обращении к ИИ
     */
    data class Error(val message: String) : AiResponse()
}

