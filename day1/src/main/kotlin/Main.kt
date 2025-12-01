package org.example

/**
 * Терминальное чат-приложение для работы с локальной моделью Ollama
 */
fun main() {
    // Инициализация клиента для работы с Ollama
    val chatClient = OllamaChatClient()
    
    // Вывод приветственных сообщений
    println("=== Терминальный чат с локальной моделью Ollama ===")
    println("Модель: ${chatClient.getModelName()}")
    println("Введите 'exit' или 'quit' для выхода.")
    println()
    
    // Основной цикл чата
    while (true) {
        // Приглашение к вводу
        print("User> ")
        
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
            
            // Обычное сообщение
            else -> {
                try {
                    // Отправляем сообщение модели и получаем ответ
                    val response = chatClient.sendMessage(trimmedInput)
                    
                    // Выводим ответ модели
                    println()
                    println("ИИ:")
                    println(response)
                    println()
                    
                } catch (e: Exception) {
                    println("Ошибка: ${e.message}")
                    println()
                }
            }
        }
    }
}