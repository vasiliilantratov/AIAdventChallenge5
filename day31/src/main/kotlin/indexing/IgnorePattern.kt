package org.example.indexing

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths

/**
 * Утилита для проверки соответствия путей паттернам исключения.
 * Поддерживает glob-паттерны (как в .gitignore).
 */
class IgnorePattern(private val patterns: List<String>) {
    private val matchers: List<PathMatcher> = patterns.map { pattern ->
        val globPattern = normalizePattern(pattern)
        FileSystems.getDefault().getPathMatcher("glob:$globPattern")
    }

    /**
     * Проверяет, должен ли путь быть исключен из индексации.
     * @param filePath абсолютный путь к файлу или директории
     * @param rootPath корневой путь проекта (для относительных паттернов)
     */
    fun shouldIgnore(filePath: String, rootPath: String = ""): Boolean {
        val path = Paths.get(filePath).normalize()
        val root = if (rootPath.isNotEmpty()) Paths.get(rootPath).normalize() else null

        return matchers.any { matcher ->
            // Проверяем абсолютный путь
            if (matcher.matches(path)) return@any true

            // Проверяем имя файла/директории
            val fileName = path.fileName?.toString() ?: ""
            if (fileName.isNotEmpty() && matcher.matches(Paths.get(fileName))) {
                return@any true
            }

            // Проверяем относительный путь (если есть root)
            if (root != null && path.startsWith(root)) {
                val relative = root.relativize(path)
                // Проверяем относительный путь
                if (matcher.matches(relative)) return@any true
                // Проверяем каждый компонент пути
                var current: Path? = relative
                while (current != null && current.nameCount > 0) {
                    if (matcher.matches(current)) return@any true
                    current = current.parent
                }
            }
            
            false
        }
    }

    /**
     * Нормализует паттерн для glob:
     * - Обрабатывает начальные/конечные слеши
     * - Поддерживает ** для рекурсивного поиска
     */
    private fun normalizePattern(pattern: String): String {
        var normalized = pattern.trim()

        // Удаляем комментарии
        val commentIndex = normalized.indexOf('#')
        if (commentIndex >= 0) {
            normalized = normalized.substring(0, commentIndex).trim()
        }

        if (normalized.isEmpty()) return normalized

        // Если паттерн начинается с /, убираем его (будем проверять относительно root)
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1)
        }

        // Если паттерн заканчивается на /, это директория - добавляем /**
        if (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1) + "/**"
        }

        // Если паттерн не начинается с * или **, добавляем **/ для поиска в любом месте
        if (!normalized.startsWith("*")) {
            normalized = "**/$normalized"
        }

        return normalized
    }

    companion object {
        /**
         * Создает IgnorePattern из списка паттернов.
         */
        fun from(patterns: List<String>): IgnorePattern {
            return IgnorePattern(patterns)
        }

        /**
         * Создает IgnorePattern из одного паттерна.
         */
        fun from(pattern: String): IgnorePattern {
            return IgnorePattern(listOf(pattern))
        }

        /**
         * Читает паттерны из .gitignore файла.
         */
        fun fromGitignore(gitignorePath: String): List<String> {
            val file = java.io.File(gitignorePath)
            if (!file.exists() || !file.isFile) return emptyList()

            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }
}
