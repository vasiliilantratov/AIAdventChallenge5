package org.example

/**
 * Простые модели для базового диалога с ИИ
 */

/**
 * Ответ сервиса ИИ
 */
sealed class AiResponse {
    /**
     * Успешный ответ модели
     */
    data class Reply(val text: String) : AiResponse()

    /**
     * Ошибка обращения к модели
     */
    data class Error(val message: String) : AiResponse()
}

/**
 * Действия, которые должен выполнить UI
 */
sealed class DialogAction {
    /**
     * Показать ответ ИИ
     */
    data class ShowMessage(val message: String) : DialogAction()

    /**
     * Показать ошибку
     */
    data class ShowError(val error: String) : DialogAction()

    /**
     * Завершить приложение
     */
    object Exit : DialogAction()

    /**
     * Сбросить диалог (очистить историю)
     */
    object Reset : DialogAction()
}

