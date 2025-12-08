package org.example

fun main() {
    val chatClient = OllamaChatClient()
    val aiDialogService = AiDialogService(chatClient)
    val dialogManager = DialogManager(aiDialogService)
    
    println("=== Простой чат с ИИ ===")
    println("Пишите сообщения и получайте ответы от модели.")
    println("Команды: 'exit' — выход, 'new' — начать новый диалог (очистить контекст).")
    println()
    
    while (true) {
        print("Вы> ")
        val userInput = readlnOrNull()
        if (userInput == null) {
            println("\nЗавершение работы. Пока!")
            break
        }
        
        val trimmedInput = userInput.trim()
        if (trimmedInput.isEmpty()) {
            continue
        }
        
        val action = dialogManager.processInput(trimmedInput)
        when (action) {
            is DialogAction.ShowMessage -> {
                println("ИИ> ${action.message}")
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
                println("=== Контекст очищен. Новый диалог. ===")
                println()
            }
        }
    }
}
