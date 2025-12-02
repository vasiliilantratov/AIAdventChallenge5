package org.example

/**
 * Приложение для получения списка ингредиентов блюда от ИИ
 */
fun main() {
    // Инициализация клиента для работы с Ollama
    val chatClient = OllamaChatClient()
    val recipeService = RecipeService(chatClient)
    
    // Вывод приветственных сообщений
    println("=== Получение рецептов от ИИ ===")
    println("Модель: ${chatClient.getModelName()}")
    println("Введите название блюда для получения списка ингредиентов.")
    println("Введите 'exit' или 'quit' для выхода.")
    println()
    
    // Основной цикл работы
    while (true) {
        // Приглашение к вводу
        print("Введите название блюда> ")
        
        // Чтение ввода пользователя
        val userInput = readlnOrNull()
        
        // Проверка на null (EOF)
        if (userInput == null) {
            println("\nЗавершение работы. Пока!")
            break
        }
        
        val trimmedInput = userInput.trim()
        
        // Обработка ввода
        when {
            // Пустой ввод - игнорируем
            trimmedInput.isEmpty() -> continue
            
            // Команды выхода
            trimmedInput.lowercase() in listOf("exit", "quit") -> {
                println("Завершение работы. Пока!")
                break
            }
            
            // Запрос рецепта
            else -> {
                try {
                    println("Запрос к ИИ...")
                    
                    // Получаем рецепт от ИИ
                    val result = recipeService.getRecipe(trimmedInput)
                    
                    // Обрабатываем результат
                    when (result) {
                        is RecipeValidationResult.Success -> {
                            // Отображаем рецепт
                            RecipeDisplay.displayRecipe(result.recipe)
                        }
                        is RecipeValidationResult.Error -> {
                            // Отображаем ошибку
                            RecipeDisplay.displayError(result.message)
                        }
                    }
                    
                } catch (e: Exception) {
                    RecipeDisplay.displayError("Неожиданная ошибка: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}