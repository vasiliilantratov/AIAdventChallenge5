package org.example

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

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

@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/**
 * Информация о подключенном MCP сервере
 */
private data class McpServerConnection(
    val name: String,
    val process: Process,
    val writer: OutputStreamWriter,
    val reader: BufferedReader,
    val requestIdCounter: AtomicInteger = AtomicInteger(1)
)

class McpClientManager {
    private val servers = mutableMapOf<String, McpServerConnection>()
    private val toolToServer = mutableMapOf<String, String>() // toolName -> serverName
    private var availableTools: List<McpTool> = emptyList()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /**
     * Запускает MCP сервер и подключается к нему
     */
    private suspend fun startServer(
        serverName: String,
        moduleName: String,
        scriptName: String
    ): Boolean {
        return try {
            // Получаем путь к проекту
            val projectDir = File(System.getProperty("user.dir"))
            
            // Сначала создаем distribution, если еще не создана
            System.err.println("Preparing $serverName distribution...")
            val prepareProcess = ProcessBuilder().apply {
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    command("${projectDir.absolutePath}\\gradlew.bat", ":$moduleName:installDist", "--quiet")
                } else {
                    command("${projectDir.absolutePath}/gradlew", ":$moduleName:installDist", "--quiet")
                }
                directory(projectDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
            }.start()
            
            prepareProcess.waitFor()
            
            if (prepareProcess.exitValue() != 0) {
                System.err.println("Failed to build $serverName distribution")
                return false
            }
            
            // Запускаем server напрямую через скрипт
            val serverScript = if (System.getProperty("os.name").lowercase().contains("windows")) {
                File(projectDir, "$moduleName/build/install/$moduleName/bin/$scriptName.bat")
            } else {
                File(projectDir, "$moduleName/build/install/$moduleName/bin/$scriptName")
            }
            
            if (!serverScript.exists()) {
                System.err.println("$serverName script not found at: ${serverScript.absolutePath}")
                return false
            }
            
            System.err.println("Starting $serverName: ${serverScript.absolutePath}")
            
            val processBuilder = ProcessBuilder(serverScript.absolutePath)
            processBuilder.directory(projectDir)
            // Перенаправляем stderr в отдельный поток для чтения логов
            processBuilder.redirectErrorStream(false)
            
            val process = processBuilder.start()
            val writer = OutputStreamWriter(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            // Запускаем поток для чтения stderr (логов сервера)
            scope.launch {
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                try {
                    while (true) {
                        val line = errorReader.readLine() ?: break
                        System.err.println("[$serverName] $line")
                    }
                } catch (e: Exception) {
                    // Поток закрыт
                }
            }
            
            // Даем серверу время на запуск
            delay(1000)
            
            // Проверяем, что процесс все еще работает
            if (!process.isAlive) {
                System.err.println("$serverName process died immediately. Exit code: ${process.exitValue()}")
                return false
            }
            
            val connection = McpServerConnection(serverName, process, writer, reader)
            servers[serverName] = connection
            
            // Инициализируем соединение
            val initResponse = sendRequest(serverName, "initialize", JsonObject(mapOf(
                "protocolVersion" to JsonPrimitive("2024-11-05"),
                "clientInfo" to JsonObject(mapOf(
                    "name" to JsonPrimitive("ollama-chat-client"),
                    "version" to JsonPrimitive("1.0.0")
                ))
            )))
            
            if (initResponse.error != null) {
                System.err.println("Failed to initialize $serverName: ${initResponse.error}")
                servers.remove(serverName)
                return false
            }
            
            System.err.println("$serverName initialized successfully")
            
            // Получаем список доступных инструментов
            val toolsResponse = sendRequest(serverName, "tools/list", null)
            
            val newTools = mutableListOf<McpTool>()
            if (toolsResponse.result != null) {
                val toolsArray = toolsResponse.result.jsonObject["tools"]?.jsonArray
                if (toolsArray != null) {
                    toolsArray.forEach { toolElement ->
                        val toolObj = toolElement.jsonObject
                        val toolName = toolObj["name"]?.jsonPrimitive?.content ?: ""
                        if (toolName.isNotEmpty()) {
                            val tool = McpTool(
                                name = toolName,
                                description = toolObj["description"]?.jsonPrimitive?.content ?: "",
                                inputSchema = toolObj["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
                            )
                            newTools.add(tool)
                            toolToServer[toolName] = serverName
                        }
                    }
                }
            }
            
            // Обновляем общий список инструментов
            availableTools = availableTools + newTools
            
            System.err.println("$serverName connected successfully. Added tools: ${newTools.map { it.name }}")
            
            true
        } catch (e: Exception) {
            System.err.println("Failed to start $serverName: ${e.message}")
            e.printStackTrace()
            servers.remove(serverName)
            false
        }
    }
    
    /**
     * Запускает MCP weather server и подключается к нему
     */
    suspend fun startWeatherServer(): Boolean {
        return startServer("weather-server", "weather-server", "weather-server")
    }
    
    /**
     * Запускает MCP ADB server и подключается к нему
     */
    suspend fun startAdbServer(): Boolean {
        return startServer("adb-server", "adb-server", "adb-server")
    }

    /**
     * Отправляет запрос к указанному серверу и получает ответ
     */
    private suspend fun sendRequest(serverName: String, method: String, params: JsonObject?): JsonRpcResponse {
        return withContext(Dispatchers.IO) {
            val connection = servers[serverName] 
                ?: throw Exception("Server $serverName is not connected")
            
            // Проверяем, что процесс все еще работает
            if (!connection.process.isAlive) {
                throw Exception("$serverName process is not running")
            }
            
            val requestId = connection.requestIdCounter.getAndIncrement()
            val request = JsonRpcRequest(
                id = JsonPrimitive(requestId),
                method = method,
                params = params
            )
            
            val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)
            System.err.println("[MCP Client -> $serverName] Sending: $requestJson")
            
            try {
                connection.writer.write(requestJson)
                connection.writer.write("\n")
                connection.writer.flush()
            } catch (e: Exception) {
                throw Exception("Failed to write to $serverName: ${e.message}", e)
            }
            
            val responseLine = try {
                // Добавляем timeout для чтения
                withTimeout(10000) {
                    connection.reader.readLine()
                }
            } catch (e: Exception) {
                throw Exception("Failed to read from $serverName: ${e.message}", e)
            }
            
            System.err.println("[MCP Client <- $serverName] Received: $responseLine")
            
            if (responseLine == null) {
                throw Exception("No response from $serverName (connection closed)")
            }
            
            json.decodeFromString<JsonRpcResponse>(responseLine)
        }
    }

    /**
     * Получает список доступных инструментов
     */
    fun getAvailableTools(): List<McpTool> = availableTools

    /**
     * Вызывает инструмент MCP сервера
     */
    suspend fun callTool(toolName: String, arguments: JsonObject): String {
        return try {
            val serverName = toolToServer[toolName]
                ?: return "Ошибка: инструмент $toolName не найден ни на одном сервере"
            
            val response = sendRequest(serverName, "tools/call", JsonObject(mapOf(
                "name" to JsonPrimitive(toolName),
                "arguments" to arguments
            )))
            
            if (response.error != null) {
                "Ошибка: ${response.error["message"]?.jsonPrimitive?.content ?: "Unknown error"}"
            } else if (response.result != null) {
                val content = response.result.jsonObject["content"]?.jsonArray?.firstOrNull()
                val text = content?.jsonObject?.get("text")?.jsonPrimitive?.content
                text ?: "Инструмент выполнен, но ответ пуст"
            } else {
                "Инструмент выполнен, но ответ пуст"
            }
        } catch (e: Exception) {
            "Ошибка вызова инструмента: ${e.message}"
        }
    }

    /**
     * Конвертирует MCP Tool в формат для Ollama
     */
    fun convertToolsForOllama(): List<Map<String, Any>> {
        return availableTools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.inputSchema.toMap()
                )
            )
        }
    }

    /**
     * Закрывает соединение с MCP сервером
     */
    fun close() {
        try {
            servers.values.forEach { connection ->
                try {
                    connection.writer.close()
                    connection.reader.close()
                    connection.process.destroy()
                    connection.process.waitFor()
                } catch (e: Exception) {
                    System.err.println("Error closing ${connection.name}: ${e.message}")
                }
            }
            servers.clear()
            toolToServer.clear()
            availableTools = emptyList()
            scope.cancel()
        } catch (e: Exception) {
            System.err.println("Error closing MCP client: ${e.message}")
        }
    }
}

// Вспомогательная функция для конвертации JsonObject в Map
private fun JsonObject.toMap(): Map<String, Any> {
    return this.mapValues { (_, value) ->
        when (value) {
            is JsonPrimitive -> {
                when {
                    value.isString -> value.content
                    value.content == "true" || value.content == "false" -> value.content.toBoolean()
                    value.content.toIntOrNull() != null -> value.content.toInt()
                    value.content.toDoubleOrNull() != null -> value.content.toDouble()
                    else -> value.content
                }
            }
            is JsonObject -> value.toMap()
            is JsonArray -> value.map { element ->
                when (element) {
                    is JsonPrimitive -> element.content
                    is JsonObject -> element.toMap()
                    is JsonArray -> element.toString()
                    is JsonNull -> "null"
                }
            }
            is JsonNull -> "null"
        }
    }
}
