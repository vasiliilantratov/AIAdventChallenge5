package org.example.mcp

import org.example.config.AssistantConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.streams.toList

data class SearchHit(
    val file: String,
    val line: Int,
    val preview: String
)

/**
 * Безопасный MCP-клиент для работы с репозиторием проекта.
 * Все git-команды и операции с файлами выполняются в контексте projectRoot
 * (по умолчанию из AssistantConfig.projectPath).
 */
class McpClient(
    private val projectRoot: Path = Paths.get(AssistantConfig.projectPath)
    ) {

    init {
        require(projectRoot.toFile().exists()) { "Project root does not exist: $projectRoot" }
    }

    fun gitBranch(): String =
        runCommand(listOf("git", "-C", projectRoot.toString(), "rev-parse", "--abbrev-ref", "HEAD"))
            .ifBlank { "unknown" }
            .trim()

    fun gitStatus(): String =
        runCommand(listOf("git", "-C", projectRoot.toString(), "status", "--porcelain"))
            .trim()

    fun gitDiff(file: String? = null, statOnly: Boolean = true): String {
        val args = mutableListOf("git", "-C", projectRoot.toString(), "diff")
        if (statOnly) args.add("--stat")
        // Преобразуем путь в относительный от projectRoot для git
        file?.let { 
            val absolutePath = safePath(it)
            val relativePath = projectRoot.relativize(absolutePath).toString()
            args.add(relativePath)
        }
        return runCommand(args).trim()
    }

    /**
     * Получает diff последнего коммита (HEAD vs HEAD~1)
     */
    fun gitDiffLastCommit(): String {
        val args = listOf("git", "-C", projectRoot.toString(), "diff", "HEAD~1", "HEAD")
        return runCommand(args).trim()
    }

    /**
     * Получает список файлов, измененных в последнем коммите
     */
    fun gitChangedFilesLastCommit(): List<String> {
        val args = listOf("git", "-C", projectRoot.toString(), "diff", "--name-only", "HEAD~1", "HEAD")
        val output = runCommand(args).trim()
        if (output.isBlank()) return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    /**
     * Получает информацию о последнем коммите (hash, message, author, date)
     */
    fun gitLastCommitInfo(): String {
        val hash = runCommand(listOf("git", "-C", projectRoot.toString(), "rev-parse", "HEAD")).trim()
        val message = runCommand(listOf("git", "-C", projectRoot.toString(), "log", "-1", "--pretty=format:%s")).trim()
        val author = runCommand(listOf("git", "-C", projectRoot.toString(), "log", "-1", "--pretty=format:%an <%ae>")).trim()
        val date = runCommand(listOf("git", "-C", projectRoot.toString(), "log", "-1", "--pretty=format:%ai")).trim()
        
        return buildString {
            appendLine("Commit: $hash")
            appendLine("Author: $author")
            appendLine("Date: $date")
            appendLine("Message: $message")
        }.trim()
    }

    fun listDir(relativePath: String = "."): List<String> {
        val dir = safePath(relativePath)
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.map { projectRoot.relativize(it).toString() }
                .sorted()
                .toList()
        }
    }

    fun readFile(relativePath: String, maxLines: Int = 120): List<String> {
        val path = safePath(relativePath)
        if (!path.exists()) return emptyList()
        val lines = path.readLines()
        return lines.take(maxLines)
    }

    fun search(pattern: String, relativeDir: String = ".", maxHits: Int = 10): List<SearchHit> {
        val dir = safePath(relativeDir)
        if (!dir.exists()) return emptyList()
        
        // Преобразуем в относительный путь от projectRoot для ripgrep
        val relativeSearchDir = projectRoot.relativize(dir).toString()
        
        val args = listOf(
            "rg",
            "--max-count", "1",
            "--line-number",
            "--ignore-file", ".gitignore",
            "-n",
            pattern,
            if (relativeSearchDir.isEmpty() || relativeSearchDir == ".") "." else relativeSearchDir
        )
        // ripgrep выполняется в контексте projectRoot
        val raw = runCommand(args, workingDir = projectRoot.toFile())
        if (raw.isBlank()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        raw.lineSequence().take(maxHits).forEach { line ->
            // Format: path:line:match
            val firstColon = line.indexOf(':')
            val secondColon = if (firstColon != -1) line.indexOf(':', firstColon + 1) else -1
            if (firstColon == -1 || secondColon == -1) return@forEach
            val file = line.substring(0, firstColon)
            val lineNum = line.substring(firstColon + 1, secondColon).toIntOrNull() ?: return@forEach
            val preview = line.substring(secondColon + 1).trim()
            hits.add(
                SearchHit(
                    file = file,
                    line = lineNum,
                    preview = preview
                )
            )
        }
        return hits
    }

    private fun safePath(relative: String): Path {
        val path = projectRoot.resolve(relative).normalize()
        require(path.startsWith(projectRoot)) { "Path escapes project root: $relative" }
        return path
    }

    private fun runCommand(args: List<String>, workingDir: File? = null): String {
        val processBuilder = ProcessBuilder(args)
            .redirectErrorStream(true)
        
        // Устанавливаем рабочую директорию, если указана
        workingDir?.let { processBuilder.directory(it) }
        
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
