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
              /model    - сменить модель
            """.trimIndent()
        )
    }
}

