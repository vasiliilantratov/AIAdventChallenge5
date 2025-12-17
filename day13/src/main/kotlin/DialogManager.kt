package org.example

object ConsoleMessages {
    fun printHelp() {
        println(
            """
            Доступные команды:
              /exit     - выйти из программы
              /help     - показать эту справку
              /clear    - очистить историю диалога
              /cleardb  - полностью очистить базу данных
              /model    - показать текущую модель
              /stats    - показать статистику сессии (запросы, токены)
            """.trimIndent()
        )
    }
}

