package org.example.adb

import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Простой логгер для ADB операций
 */
object AdbApiLogger {
    private val logFile = java.io.File("adb_api.log")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    init {
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }
    
    fun logCommand(command: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val message = buildString {
            appendLine("\n[$timestamp] ADB COMMAND")
            appendLine("  Command: $command")
        }
        
        System.err.print(message)
        logFile.appendText(message)
    }
    
    fun logResponse(success: Boolean, output: String, error: String? = null) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val icon = if (success) "✓" else "✗"
        
        val consoleMessage = buildString {
            appendLine("[$timestamp] ADB RESPONSE")
            appendLine("  $icon Success: $success")
            if (error != null) {
                appendLine("  Error: ${error.take(200)}")
            }
            appendLine("  Output: ${output.take(200)}${if (output.length > 200) "..." else ""}")
        }
        
        val fileMessage = buildString {
            appendLine("\n[$timestamp] ADB RESPONSE")
            appendLine("  $icon Success: $success")
            if (error != null) {
                appendLine("  Error: $error")
            }
            appendLine("  Output:")
            appendLine(output)
            appendLine()
        }
        
        System.err.print(consoleMessage)
        logFile.appendText(fileMessage)
    }
}

/**
 * Клиент для работы с ADB
 */
class AdbClient {
    
    /**
     * Выполняет ADB команду и возвращает результат
     */
    fun executeAdbCommand(command: String): Result<String> {
        return try {
            AdbApiLogger.logCommand(command)
            
            val processBuilder = ProcessBuilder("/home/vas/Android/Sdk/platform-tools/adb", *command.split(" ").toTypedArray())
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                AdbApiLogger.logResponse(true, output)
                Result.success(output)
            } else {
                val error = "ADB command failed with exit code $exitCode"
                AdbApiLogger.logResponse(false, output, error)
                Result.failure(Exception(error))
            }
        } catch (e: java.io.IOException) {
            val error = "ADB not found in PATH or command execution failed: ${e.message}"
            AdbApiLogger.logResponse(false, "", error)
            Result.failure(Exception(error, e))
        } catch (e: Exception) {
            val error = "Unexpected error: ${e.message}"
            AdbApiLogger.logResponse(false, "", error)
            Result.failure(e)
        }
    }
    
    /**
     * Выполняет системную команду и возвращает результат
     */
    fun executeSystemCommand(command: String, vararg args: String): Result<String> {
        return try {
            AdbApiLogger.logCommand("$command ${args.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(command, *args)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                AdbApiLogger.logResponse(true, output)
                Result.success(output)
            } else {
                val error = "Command failed with exit code $exitCode"
                AdbApiLogger.logResponse(false, output, error)
                Result.failure(Exception(error))
            }
        } catch (e: java.io.IOException) {
            val error = "Command not found in PATH or execution failed: ${e.message}"
            AdbApiLogger.logResponse(false, "", error)
            Result.failure(Exception(error, e))
        } catch (e: Exception) {
            val error = "Unexpected error: ${e.message}"
            AdbApiLogger.logResponse(false, "", error)
            Result.failure(e)
        }
    }
    
    /**
     * Получает переменные окружения через команду env
     */
    fun getEnvironmentVariables(): Result<Map<String, String>> {
        val result = executeSystemCommand("env")
        
        return result.fold(
            onSuccess = { output ->
                try {
                    val envVars = mutableMapOf<String, String>()
                    val lines = output.lines()
                    
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        
                        // Формат: KEY=VALUE
                        val equalsIndex = trimmed.indexOf('=')
                        if (equalsIndex > 0) {
                            val key = trimmed.substring(0, equalsIndex)
                            val value = trimmed.substring(equalsIndex + 1)
                            envVars[key] = value
                        }
                    }
                    
                    Result.success(envVars)
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to parse env output: ${e.message}", e))
                }
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }
    
    /**
     * Получает список подключенных устройств
     */
    fun getDevices(): Result<AdbDevicesResponse> {
        // Используем команду adb devices -l для получения дополнительной информации
        val result = executeAdbCommand("devices -l")
        
        return result.fold(
            onSuccess = { output ->
                try {
                    val devices = parseDevicesOutput(output)
                    val onlineCount = devices.count { it.status == "device" }
                    val offlineCount = devices.count { it.status == "offline" }
                    val unauthorizedCount = devices.count { it.status == "unauthorized" }
                    
                    Result.success(
                        AdbDevicesResponse(
                            devices = devices,
                            totalCount = devices.size,
                            onlineCount = onlineCount,
                            offlineCount = offlineCount,
                            unauthorizedCount = unauthorizedCount
                        )
                    )
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to parse devices output: ${e.message}", e))
                }
            },
            onFailure = { error ->
                // Если ADB не найден или произошла ошибка, возвращаем пустой список
                Result.success(AdbDevicesResponse.empty())
            }
        )
    }
    
    /**
     * Парсит вывод команды adb devices -l
     * Формат вывода:
     * List of devices attached
     * emulator-5554    device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64xa
     * R58M1234567     device product:dream2ltexx model:SM_G955F device:dream2lte
     * emulator-5556   offline
     */
    private fun parseDevicesOutput(output: String): List<AndroidDevice> {
        val devices = mutableListOf<AndroidDevice>()
        val lines = output.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Пропускаем заголовок и пустые строки
            if (trimmed.isEmpty() || 
                trimmed.startsWith("List of devices") || 
                trimmed.startsWith("* daemon")) {
                continue
            }
            
            // Парсим строку формата: serial_number    status [additional_info]
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            
            val serialNumber = parts[0]
            val statusAndInfo = parts[1]
            
            // Разделяем статус и дополнительную информацию
            val statusParts = statusAndInfo.split(Regex("\\s+"))
            val status = statusParts[0]
            
            // Извлекаем дополнительную информацию (model, product, device)
            var model: String? = null
            var product: String? = null
            
            for (part in statusParts.drop(1)) {
                when {
                    part.startsWith("model:") -> model = part.substringAfter("model:")
                    part.startsWith("product:") -> product = part.substringAfter("product:")
                }
            }
            
            // Определяем тип устройства
            val deviceType = when {
                serialNumber.startsWith("emulator-") -> DeviceType.EMULATOR
                model != null || product != null -> DeviceType.PHYSICAL
                else -> DeviceType.UNKNOWN
            }
            
            devices.add(
                AndroidDevice(
                    serialNumber = serialNumber,
                    status = status,
                    type = deviceType,
                    model = model,
                    product = product
                )
            )
        }
        
        return devices
    }
}

