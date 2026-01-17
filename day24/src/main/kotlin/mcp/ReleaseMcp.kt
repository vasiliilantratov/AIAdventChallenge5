package org.example.mcp

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * MCP клиент для релиза приложения.
 * Загружает файлы из локальной директории на сервер через SSH.
 */
class ReleaseMcp(
    private val sshConfig: String = "my_mon_bot",
    private val localDir: String = "/home/vas/Documents/Projects/EchoBot",
    private val remoteDir: String = "/root/release"
) {
    
    /**
     * Проверяет доступность SSH соединения
     */
    fun testConnection(): Boolean {
        return try {
            val result = runCommand(listOf("ssh", sshConfig, "echo", "OK"))
            result.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Проверяет, существует ли локальная директория с файлами для релиза
     */
    fun checkLocalDirectory(): Boolean {
        val dir = File(localDir)
        return dir.exists() && dir.isDirectory
    }
    
    /**
     * Получает список файлов в локальной директории
     */
    fun getLocalFiles(): List<String> {
        val dir = File(localDir)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).path }
            .toList()
    }
    
    /**
     * Создает удаленную директорию для релиза, если её нет
     */
    fun createRemoteDirectory(): String {
        return try {
            runCommand(listOf("ssh", sshConfig, "mkdir", "-p", remoteDir))
            "Удаленная директория создана: $remoteDir"
        } catch (e: Exception) {
            "Ошибка при создании директории: ${e.message}"
        }
    }
    
    /**
     * Выполняет релиз - загружает все файлы на сервер
     */
    fun release(): ReleaseResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        
        // Проверяем локальную директорию
        if (!checkLocalDirectory()) {
            return ReleaseResult(
                success = false,
                message = "Локальная директория не найдена: $localDir",
                uploadedFiles = emptyList(),
                errors = listOf("Директория $localDir не существует"),
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        
        // Проверяем SSH соединение
        if (!testConnection()) {
            return ReleaseResult(
                success = false,
                message = "Не удалось подключиться к серверу через SSH: $sshConfig",
                uploadedFiles = emptyList(),
                errors = listOf("SSH соединение недоступно"),
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        
        // Создаем удаленную директорию
        createRemoteDirectory()
        
        // Получаем список файлов
        val files = getLocalFiles()
        if (files.isEmpty()) {
            return ReleaseResult(
                success = false,
                message = "В директории $localDir нет файлов для загрузки",
                uploadedFiles = emptyList(),
                errors = listOf("Нет файлов для релиза"),
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        
        val uploadedFiles = mutableListOf<String>()
        
        // Используем rsync для синхронизации файлов
        try {
            val command = listOf(
                "rsync",
                "-avz",
                "--delete",
                "-e", "ssh",
                "$localDir/",
                "$sshConfig:$remoteDir/"
            )
            
            val output = runCommand(command)
            
            // Парсим вывод rsync для получения списка загруженных файлов
            output.lines().forEach { line ->
                val trimmed = line.trim()
                // rsync выводит имена файлов, игнорируем служебные строки
                if (trimmed.isNotEmpty() && 
                    !trimmed.startsWith("sending") && 
                    !trimmed.startsWith("sent") &&
                    !trimmed.startsWith("total") &&
                    !trimmed.contains("speedup") &&
                    !trimmed.startsWith("building") &&
                    !trimmed.startsWith("./") &&
                    trimmed != "." &&
                    !trimmed.endsWith("/")) {
                    uploadedFiles.add(trimmed)
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            return ReleaseResult(
                success = true,
                message = "Релиз успешно завершен! Загружено файлов: ${files.size}",
                uploadedFiles = uploadedFiles.ifEmpty { files },
                errors = emptyList(),
                durationMs = duration
            )
            
        } catch (e: Exception) {
            errors.add("Ошибка при загрузке файлов: ${e.message}")
            
            return ReleaseResult(
                success = false,
                message = "Ошибка при выполнении релиза: ${e.message}",
                uploadedFiles = uploadedFiles,
                errors = errors,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * Получает информацию о последнем релизе на сервере
     */
    fun getRemoteInfo(): String {
        return try {
            val output = runCommand(listOf(
                "ssh", 
                sshConfig, 
                "ls", "-lah", remoteDir
            ))
            output
        } catch (e: Exception) {
            "Ошибка при получении информации: ${e.message}"
        }
    }
    
    /**
     * Очищает удаленную директорию с релизом
     */
    fun cleanRemoteDirectory(): String {
        return try {
            runCommand(listOf(
                "ssh",
                sshConfig,
                "rm", "-rf", "$remoteDir/*"
            ))
            "Удаленная директория очищена"
        } catch (e: Exception) {
            "Ошибка при очистке: ${e.message}"
        }
    }
    
    private fun runCommand(command: List<String>): String {
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(true)
        
        val process = processBuilder.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()
        
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
        
        val exitCode = process.waitFor()
        if (exitCode != 0 && output.isEmpty()) {
            throw Exception("Command failed with exit code $exitCode")
        }
        
        return output.toString()
    }
}

/**
 * Результат выполнения релиза
 */
data class ReleaseResult(
    val success: Boolean,
    val message: String,
    val uploadedFiles: List<String>,
    val errors: List<String>,
    val durationMs: Long
)
