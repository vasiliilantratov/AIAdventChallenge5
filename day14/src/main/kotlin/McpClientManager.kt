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

class McpClientManager {
    private var serverProcess: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var availableTools: List<McpTool> = emptyList()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestIdCounter = AtomicInteger(1)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /**
     * Запускает MCP weather server и подключается к нему
     */
    suspend fun startWeatherServer(): Boolean {
        return try {
            // Получаем путь к проекту
            val projectDir = File(System.getProperty("user.dir"))
            
            // Сначала создаем distribution, если еще не создана
            System.err.println("Preparing weather server distribution...")
            val prepareProcess = ProcessBuilder().apply {
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    command("${projectDir.absolutePath}\\gradlew.bat", ":weather-server:installDist", "--quiet")
                } else {
                    command("${projectDir.absolutePath}/gradlew", ":weather-server:installDist", "--quiet")
                }
                directory(projectDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
            }.start()
            
            prepareProcess.waitFor()
            
            if (prepareProcess.exitValue() != 0) {
                System.err.println("Failed to build weather server distribution")
                return false
            }
            
            // Запускаем weather server напрямую через скрипт
            val serverScript = if (System.getProperty("os.name").lowercase().contains("windows")) {
                File(projectDir, "weather-server/build/install/weather-server/bin/weather-server.bat")
            } else {
                File(projectDir, "weather-server/build/install/weather-server/bin/weather-server")
            }
            
            if (!serverScript.exists()) {
                System.err.println("Weather server script not found at: ${serverScript.absolutePath}")
                return false
            }
            
            System.err.println("Starting weather server: ${serverScript.absolutePath}")
            
            val processBuilder = ProcessBuilder(serverScript.absolutePath)
            processBuilder.directory(projectDir)
            // Перенаправляем stderr в отдельный поток для чтения логов
            processBuilder.redirectErrorStream(false)
            
            serverProcess = processBuilder.start()
            writer = OutputStreamWriter(serverProcess!!.outputStream)
            reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
            
            // Запускаем поток для чтения stderr (логов сервера)
            scope.launch {
                val errorReader = BufferedReader(InputStreamReader(serverProcess!!.errorStream))
                try {
                    while (true) {
                        val line = errorReader.readLine() ?: break
                        System.err.println("[Weather Server] $line")
                    }
                } catch (e: Exception) {
                    // Поток закрыт
                }
            }
            
            // Даем серверу время на запуск (ждем сообщение в stderr)
            delay(1000)
            
            // Проверяем, что процесс все еще работает
            if (!serverProcess!!.isAlive) {
                System.err.println("Weather server process died immediately. Exit code: ${serverProcess!!.exitValue()}")
                return false
            }
            
            // Инициализируем соединение
            val initResponse = sendRequest("initialize", JsonObject(mapOf(
                "protocolVersion" to JsonPrimitive("2024-11-05"),
                "clientInfo" to JsonObject(mapOf(
                    "name" to JsonPrimitive("ollama-chat-client"),
                    "version" to JsonPrimitive("1.0.0")
                ))
            )))
            
            if (initResponse.error != null) {
                System.err.println("Failed to initialize MCP server: ${initResponse.error}")
                return false
            }
            
            System.err.println("MCP server initialized successfully")
            
            // Получаем список доступных инструментов
            val toolsResponse = sendRequest("tools/list", null)
            
            if (toolsResponse.result != null) {
                val toolsArray = toolsResponse.result.jsonObject["tools"]?.jsonArray
                if (toolsArray != null) {
                    availableTools = toolsArray.map { toolElement ->
                        val toolObj = toolElement.jsonObject
                        McpTool(
                            name = toolObj["name"]?.jsonPrimitive?.content ?: "",
                            description = toolObj["description"]?.jsonPrimitive?.content ?: "",
                            inputSchema = toolObj["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
                        )
                    }
                }
            }
            
            System.err.println("MCP Weather Server connected successfully. Available tools: ${availableTools.map { it.name }}")
            
            true
        } catch (e: Exception) {
            System.err.println("Failed to start weather server: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Отправляет запрос к серверу и получает ответ
     */
    private suspend fun sendRequest(method: String, params: JsonObject?): JsonRpcResponse {
        return withContext(Dispatchers.IO) {
            // Проверяем, что процесс все еще работает
            if (serverProcess?.isAlive == false) {
                throw Exception("Weather server process is not running")
            }
            
            val requestId = requestIdCounter.getAndIncrement()
            val request = JsonRpcRequest(
                id = JsonPrimitive(requestId),
                method = method,
                params = params
            )
            
            val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)
            System.err.println("[MCP Client] Sending: $requestJson")
            
            try {
                writer?.write(requestJson)
                writer?.write("\n")
                writer?.flush()
            } catch (e: Exception) {
                throw Exception("Failed to write to server: ${e.message}", e)
            }
            
            val responseLine = try {
                // Добавляем timeout для чтения
                withTimeout(10000) {
                    reader?.readLine()
                }
            } catch (e: Exception) {
                throw Exception("Failed to read from server: ${e.message}", e)
            }
            
            System.err.println("[MCP Client] Received: $responseLine")
            
            if (responseLine == null) {
                throw Exception("No response from server (connection closed)")
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
            val response = sendRequest("tools/call", JsonObject(mapOf(
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
            writer?.close()
            reader?.close()
            serverProcess?.destroy()
            serverProcess?.waitFor()
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
