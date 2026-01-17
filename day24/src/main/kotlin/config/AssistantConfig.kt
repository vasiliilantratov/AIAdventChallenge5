package org.example.config

object AssistantConfig {
    /** Корень проекта, по которому строится индекс и выполняются MCP-операции. */
    const val projectPath: String = "/home/vas/Documents/Projects/tg-keywords-monitoring"

    /** Путь к локальной БД с индексом для проекта. */
    const val dbPath: String = "./project-index.db"

    /** URL Ollama по умолчанию. */
    const val defaultOllamaUrl: String = "http://localhost:11434"

    /** Дефолтные паттерны исключения для индексации (директории и файлы, которые не нужно индексировать). */
    val defaultExcludePatterns: List<String> = listOf(
        // Системные директории
        ".git/**",
        ".svn/**",
        ".hg/**",
        // Зависимости
        "node_modules/**",
        "vendor/**",
        ".venv/**",
        "venv/**",
        "env/**",
        "__pycache__/**",
        ".pytest_cache/**",
        // Сборка и артефакты
        "build/**",
        "dist/**",
        "target/**",
        "out/**",
        "bin/**",
        "obj/**",
        ".gradle/**",
        ".idea/**",
        ".vscode/**",
        ".vs/**",
        // Кэши и временные файлы
        ".cache/**",
        ".tmp/**",
        "tmp/**",
        "temp/**",
        "*.tmp",
        "*.log",
        "*.swp",
        "*.swo",
        "*~",
        // Бинарные файлы
        "*.class",
        "*.jar",
        "*.war",
        "*.ear",
        "*.zip",
        "*.tar",
        "*.gz",
        "*.7z",
        "*.rar",
        "*.so",
        "*.dll",
        "*.dylib",
        "*.exe",
        // Медиа файлы
        "*.jpg",
        "*.jpeg",
        "*.png",
        "*.gif",
        "*.bmp",
        "*.ico",
        "*.svg",
        "*.mp3",
        "*.mp4",
        "*.avi",
        "*.mov",
        // Большие файлы данных
        "*.db",
        "*.sqlite",
        "*.sqlite3",
        "*.dump",
        "*.backup"
    )
}
