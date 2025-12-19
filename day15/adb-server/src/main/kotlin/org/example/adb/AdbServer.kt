package org.example.adb

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonObject? = null
)

/**
 * MCP сервер для работы с Android устройствами через ADB
 */
fun main() {
    val adbClient = AdbClient()
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    val reader = BufferedReader(InputStreamReader(System.`in`))
    val writer = OutputStreamWriter(System.out)
    
    System.err.println("ADB MCP Server started")
    
    try {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            
            System.err.println("Received: $line")
            
            try {
                val request = json.decodeFromString<JsonRpcRequest>(line)
                val response = handleRequest(request, adbClient, json)
                val responseJson = json.encodeToString(response)
                
                writer.write(responseJson)
                writer.write("\n")
                writer.flush()
                
                System.err.println("Sent: $responseJson")
            } catch (e: Exception) {
                System.err.println("Error processing request: ${e.message}")
                e.printStackTrace(System.err)
                
                val errorResponse = JsonRpcResponse(
                    id = null,
                    error = JsonObject(mapOf(
                        "code" to JsonPrimitive(-32700),
                        "message" to JsonPrimitive("Parse error: ${e.message}")
                    ))
                )
                writer.write(json.encodeToString(errorResponse))
                writer.write("\n")
                writer.flush()
            }
        }
    } catch (e: Exception) {
        System.err.println("Fatal error: ${e.message}")
        e.printStackTrace(System.err)
    }
}

private fun handleRequest(request: JsonRpcRequest, adbClient: AdbClient, json: Json): JsonRpcResponse {
    return when (request.method) {
        "initialize" -> {
            JsonRpcResponse(
                id = request.id,
                result = JsonObject(mapOf(
                    "protocolVersion" to JsonPrimitive("2024-11-05"),
                    "capabilities" to JsonObject(mapOf(
                        "tools" to JsonObject(emptyMap())
                    )),
                    "serverInfo" to JsonObject(mapOf(
                        "name" to JsonPrimitive("adb-server"),
                        "version" to JsonPrimitive("1.0.0")
                    ))
                ))
            )
        }
        
        "tools/list" -> {
            JsonRpcResponse(
                id = request.id,
                result = JsonObject(mapOf(
                    "tools" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "name" to JsonPrimitive("list_android_devices"),
                            "description" to JsonPrimitive("Получить список подключенных Android устройств и эмуляторов через ADB. Возвращает серийные номера, статусы (device, offline, unauthorized) и типы устройств (эмулятор/физическое устройство)."),
                            "inputSchema" to JsonObject(mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(emptyMap()),
                                "required" to JsonArray(emptyList())
                            ))
                        )),
                        JsonObject(mapOf(
                            "name" to JsonPrimitive("list_environment_variables"),
                            "description" to JsonPrimitive("Получить список переменных окружения системы. Возвращает все переменные окружения с их значениями, отсортированные по имени."),
                            "inputSchema" to JsonObject(mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(emptyMap()),
                                "required" to JsonArray(emptyList())
                            ))
                        ))
                    ))
                ))
            )
        }
        
        "tools/call" -> {
            val params = request.params ?: return JsonRpcResponse(
                id = request.id,
                error = JsonObject(mapOf(
                    "code" to JsonPrimitive(-32602),
                    "message" to JsonPrimitive("Missing params")
                ))
            )
            
            val toolName = params["name"]?.jsonPrimitive?.content
            val arguments = params["arguments"]?.jsonObject
            
            if (toolName == null) {
                return JsonRpcResponse(
                    id = request.id,
                    error = JsonObject(mapOf(
                        "code" to JsonPrimitive(-32602),
                        "message" to JsonPrimitive("Invalid params: tool name is required")
                    ))
                )
            }
            
            when (toolName) {
                "list_android_devices" -> {
                    val result = adbClient.getDevices()
                    result.fold(
                        onSuccess = { devicesResponse ->
                            val formattedText = formatDevicesResponse(devicesResponse)
                            
                            val devicesJson = JsonArray(
                                devicesResponse.devices.map { device ->
                                    JsonObject(mapOf(
                                        "serialNumber" to JsonPrimitive(device.serialNumber),
                                        "status" to JsonPrimitive(device.status),
                                        "type" to JsonPrimitive(device.type.name),
                                        "model" to JsonPrimitive(device.model ?: ""),
                                        "product" to JsonPrimitive(device.product ?: "")
                                    ))
                                }
                            )
                            
                            val responseData = JsonObject(mapOf(
                                "devices" to devicesJson,
                                "statistics" to JsonObject(mapOf(
                                    "totalCount" to JsonPrimitive(devicesResponse.totalCount),
                                    "onlineCount" to JsonPrimitive(devicesResponse.onlineCount),
                                    "offlineCount" to JsonPrimitive(devicesResponse.offlineCount),
                                    "unauthorizedCount" to JsonPrimitive(devicesResponse.unauthorizedCount)
                                )),
                                "formatted" to JsonPrimitive(formattedText)
                            ))
                            
                            JsonRpcResponse(
                                id = request.id,
                                result = JsonObject(mapOf(
                                    "content" to JsonArray(listOf(
                                        JsonObject(mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive(json.encodeToString(responseData))
                                        ))
                                    ))
                                ))
                            )
                        },
                        onFailure = { error ->
                            JsonRpcResponse(
                                id = request.id,
                                result = JsonObject(mapOf(
                                    "content" to JsonArray(listOf(
                                        JsonObject(mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive("Ошибка получения списка устройств: ${error.message}")
                                        ))
                                    )),
                                    "isError" to JsonPrimitive(true)
                                ))
                            )
                        }
                    )
                }
                "list_environment_variables" -> {
                    val result = adbClient.getEnvironmentVariables()
                    result.fold(
                        onSuccess = { envVars ->
                            val formattedText = formatEnvironmentVariables(envVars)
                            
                            val envVarsJson = JsonObject(
                                envVars.entries.sortedBy { it.key }.associate { entry ->
                                    entry.key to JsonPrimitive(entry.value)
                                }
                            )
                            
                            val responseData = JsonObject(mapOf(
                                "variables" to envVarsJson,
                                "count" to JsonPrimitive(envVars.size),
                                "formatted" to JsonPrimitive(formattedText)
                            ))
                            
                            JsonRpcResponse(
                                id = request.id,
                                result = JsonObject(mapOf(
                                    "content" to JsonArray(listOf(
                                        JsonObject(mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive(json.encodeToString(responseData))
                                        ))
                                    ))
                                ))
                            )
                        },
                        onFailure = { error ->
                            JsonRpcResponse(
                                id = request.id,
                                result = JsonObject(mapOf(
                                    "content" to JsonArray(listOf(
                                        JsonObject(mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive("Ошибка получения переменных окружения: ${error.message}")
                                        ))
                                    )),
                                    "isError" to JsonPrimitive(true)
                                ))
                            )
                        }
                    )
                }
                else -> {
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonObject(mapOf(
                            "code" to JsonPrimitive(-32601),
                            "message" to JsonPrimitive("Unknown tool: $toolName")
                        ))
                    )
                }
            }
        }
        
        else -> {
            JsonRpcResponse(
                id = request.id,
                error = JsonObject(mapOf(
                    "code" to JsonPrimitive(-32601),
                    "message" to JsonPrimitive("Method not found: ${request.method}")
                ))
            )
        }
    }
}

/**
 * Форматирует ответ со списком устройств для отображения
 */
private fun formatDevicesResponse(response: AdbDevicesResponse): String {
    if (response.totalCount == 0) {
        return "📱 Android устройства не найдены.\n\n" +
               "Убедитесь, что:\n" +
               "  • ADB установлен и доступен в PATH\n" +
               "  • Устройства подключены через USB или эмуляторы запущены\n" +
               "  • На физических устройствах включена отладка по USB"
    }
    
    val builder = StringBuilder()
    
    // Статистика
    builder.append("📱 Android устройства (всего: ${response.totalCount}")
    if (response.onlineCount > 0) {
        builder.append(", онлайн: ${response.onlineCount}")
    }
    builder.append(")\n\n")
    
    // Группируем устройства по статусам
    val onlineDevices = response.devices.filter { it.status == "device" }
    val offlineDevices = response.devices.filter { it.status == "offline" }
    val unauthorizedDevices = response.devices.filter { it.status == "unauthorized" }
    val otherDevices = response.devices.filter { 
        it.status != "device" && it.status != "offline" && it.status != "unauthorized" 
    }
    
    // Онлайн устройства
    if (onlineDevices.isNotEmpty()) {
        builder.append("✅ Онлайн устройства:\n")
        onlineDevices.forEach { device ->
            val typeLabel = when (device.type) {
                DeviceType.EMULATOR -> "Эмулятор"
                DeviceType.PHYSICAL -> "Физическое устройство"
                DeviceType.UNKNOWN -> "Устройство"
            }
            val deviceInfo = buildString {
                append("  • ${device.serialNumber} ($typeLabel)")
                if (device.model != null) {
                    append(" - модель: ${device.model}")
                }
                if (device.product != null) {
                    append(" - продукт: ${device.product}")
                }
                append(" - статус: ${device.status}")
            }
            builder.append(deviceInfo).append("\n")
        }
        builder.append("\n")
    }
    
    // Офлайн устройства
    if (offlineDevices.isNotEmpty()) {
        builder.append("⚠️ Офлайн устройства:\n")
        offlineDevices.forEach { device ->
            val typeLabel = when (device.type) {
                DeviceType.EMULATOR -> "Эмулятор"
                DeviceType.PHYSICAL -> "Физическое устройство"
                DeviceType.UNKNOWN -> "Устройство"
            }
            builder.append("  • ${device.serialNumber} ($typeLabel) - статус: ${device.status}\n")
        }
        builder.append("\n")
    }
    
    // Неавторизованные устройства
    if (unauthorizedDevices.isNotEmpty()) {
        builder.append("🔒 Неавторизованные устройства:\n")
        unauthorizedDevices.forEach { device ->
            val typeLabel = when (device.type) {
                DeviceType.EMULATOR -> "Эмулятор"
                DeviceType.PHYSICAL -> "Физическое устройство"
                DeviceType.UNKNOWN -> "Устройство"
            }
            builder.append("  • ${device.serialNumber} ($typeLabel) - статус: ${device.status}\n")
            builder.append("    (Разрешите отладку по USB на устройстве)\n")
        }
        builder.append("\n")
    }
    
    // Другие статусы
    if (otherDevices.isNotEmpty()) {
        builder.append("❓ Другие устройства:\n")
        otherDevices.forEach { device ->
            val typeLabel = when (device.type) {
                DeviceType.EMULATOR -> "Эмулятор"
                DeviceType.PHYSICAL -> "Физическое устройство"
                DeviceType.UNKNOWN -> "Устройство"
            }
            builder.append("  • ${device.serialNumber} ($typeLabel) - статус: ${device.status}\n")
        }
    }
    
    return builder.toString()
}

/**
 * Форматирует переменные окружения для отображения
 */
private fun formatEnvironmentVariables(envVars: Map<String, String>): String {
    if (envVars.isEmpty()) {
        return "🔧 Переменные окружения не найдены."
    }
    
    val builder = StringBuilder()
    
    builder.append("🔧 Переменные окружения (всего: ${envVars.size})\n\n")
    
    // Сортируем переменные по имени для удобства чтения
    val sortedVars = envVars.entries.sortedBy { it.key }
    
    // Группируем важные переменные (PATH, HOME, USER, etc.)
    val importantVars = listOf("PATH", "HOME", "USER", "SHELL", "JAVA_HOME", "ANDROID_HOME", "JAVA_HOME")
    val important = sortedVars.filter { it.key in importantVars }
    val other = sortedVars.filter { it.key !in importantVars }
    
    if (important.isNotEmpty()) {
        builder.append("⭐ Важные переменные:\n")
        important.forEach { (key, value) ->
            // Для PATH и других длинных переменных показываем только первые 200 символов
            val displayValue = if (value.length > 200) {
                "${value.take(200)}... (всего ${value.length} символов)"
            } else {
                value
            }
            builder.append("  • $key = $displayValue\n")
        }
        builder.append("\n")
    }
    
    if (other.isNotEmpty()) {
        builder.append("📋 Остальные переменные:\n")
        other.forEach { (key, value) ->
            // Для длинных значений показываем только первые 150 символов
            val displayValue = if (value.length > 150) {
                "${value.take(150)}... (всего ${value.length} символов)"
            } else {
                value
            }
            builder.append("  • $key = $displayValue\n")
        }
    }
    
    return builder.toString()
}

