package org.example

class DialogManager(private val aiDialogService: AiDialogService) {
    /**
     * Обрабатывает ввод пользователя и возвращает действие для UI.
     */
    fun processInput(input: String): DialogAction {
        val trimmedInput = input.trim()

        // Команды управления
        when (trimmedInput.lowercase()) {
            "exit" -> return DialogAction.Exit
            "new" -> return handleNewCommand()
        }

        val response = aiDialogService.sendUserMessage(trimmedInput)

        return when (response) {
            is AiResponse.Reply -> DialogAction.ShowMessage(response.text)
            is AiResponse.Error -> DialogAction.ShowError(response.message)
        }
    }
    
    /**
     * Команда очистки диалога
     */
    private fun handleNewCommand(): DialogAction {
        aiDialogService.reset()
        return DialogAction.Reset
    }
}

