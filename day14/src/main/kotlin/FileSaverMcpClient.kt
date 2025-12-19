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
data class FileSaverJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class FileSaverJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonObject? = null
)

/**
 * Клиент для MCP сервера сохранения текстов в файлы (weather-text-saver-server)
 */
class FileSaverMcpClient {
    private var serverProcess: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestIdCounter = AtomicInteger(1)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    suspend fun startServer(): Boolean {
        return try {
            val projectDir = File(System.getProperty("user.dir"))

            System.err.println("Preparing file saver server distribution...")
            val prepareProcess = ProcessBuilder().apply {
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    command("${projectDir.absolutePath}\\gradlew.bat", ":weather-text-saver-server:installDist", "--quiet")
                } else {
                    command("${projectDir.absolutePath}/gradlew", ":weather-text-saver-server:installDist", "--quiet")
                }
                directory(projectDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
            }.start()

            prepareProcess.waitFor()

            if (prepareProcess.exitValue() != 0) {
                System.err.println("Failed to build file saver server distribution")
                return false
            }

            val serverScript = if (System.getProperty("os.name").lowercase().contains("windows")) {
                File(projectDir, "weather-text-saver-server/build/install/weather-text-saver-server/bin/weather-text-saver-server.bat")
            } else {
                File(projectDir, "weather-text-saver-server/build/install/weather-text-saver-server/bin/weather-text-saver-server")
            }

            if (!serverScript.exists()) {
                System.err.println("File saver server script not found at: ${serverScript.absolutePath}")
                return false
            }

            System.err.println("Starting file saver server: ${serverScript.absolutePath}")

            val processBuilder = ProcessBuilder(serverScript.absolutePath)
            processBuilder.directory(projectDir)
            processBuilder.redirectErrorStream(false)

            serverProcess = processBuilder.start()
            writer = OutputStreamWriter(serverProcess!!.outputStream)
            reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))

            // логируем stderr
            scope.launch {
                val errorReader = BufferedReader(InputStreamReader(serverProcess!!.errorStream))
                try {
                    while (true) {
                        val line = errorReader.readLine() ?: break
                        System.err.println("[File Saver Server] $line")
                    }
                } catch (_: Exception) {
                }
            }

            delay(1000)

            if (!serverProcess!!.isAlive) {
                System.err.println("File saver server process died immediately. Exit code: ${serverProcess!!.exitValue()}")
                return false
            }

            val initResponse = sendRequest(
                "initialize",
                JsonObject(
                    mapOf(
                        "protocolVersion" to JsonPrimitive("2024-11-05"),
                        "clientInfo" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("ollama-chat-client"),
                                "version" to JsonPrimitive("1.0.0")
                            )
                        )
                    )
                )
            )

            if (initResponse.error != null) {
                System.err.println("Failed to initialize file saver MCP server: ${initResponse.error}")
                return false
            }

            System.err.println("File saver MCP server initialized successfully")

            true
        } catch (e: Exception) {
            System.err.println("Failed to start file saver server: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun sendRequest(method: String, params: JsonObject?): FileSaverJsonRpcResponse {
        return withContext(Dispatchers.IO) {
            if (serverProcess?.isAlive == false) {
                throw Exception("File saver server process is not running")
            }

            val requestId = requestIdCounter.getAndIncrement()
            val request = FileSaverJsonRpcRequest(
                id = JsonPrimitive(requestId),
                method = method,
                params = params
            )

            val requestJson = json.encodeToString(FileSaverJsonRpcRequest.serializer(), request)
            System.err.println("[FileSaver MCP Client] Sending: $requestJson")

            try {
                writer?.write(requestJson)
                writer?.write("\n")
                writer?.flush()
            } catch (e: Exception) {
                throw Exception("Failed to write to file saver server: ${e.message}", e)
            }

            val responseLine = try {
                withTimeout(10000) {
                    reader?.readLine()
                }
            } catch (e: Exception) {
                throw Exception("Failed to read from file saver server: ${e.message}", e)
            }

            System.err.println("[FileSaver MCP Client] Received: $responseLine")

            if (responseLine == null) {
                throw Exception("No response from file saver server (connection closed)")
            }

            json.decodeFromString<FileSaverJsonRpcResponse>(responseLine)
        }
    }

    /**
     * Сохраняет текст в файл с помощью MCP инструмента save_text_to_file
     */
    suspend fun saveText(content: String, filename: String? = null): String {
        return try {
            val args = mutableMapOf<String, JsonElement>(
                "content" to JsonPrimitive(content)
            )
            if (filename != null) {
                args["filename"] = JsonPrimitive(filename)
            }

            val response = sendRequest(
                "tools/call",
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("save_text_to_file"),
                        "arguments" to JsonObject(args)
                    )
                )
            )

            if (response.error != null) {
                "Ошибка сохранения файла через MCP: ${response.error["message"]?.jsonPrimitive?.content ?: "Unknown error"}"
            } else if (response.result != null) {
                val contentElement = response.result.jsonObject["content"]?.jsonArray?.firstOrNull()
                val text = contentElement?.jsonObject?.get("text")?.jsonPrimitive?.content
                text ?: "Файл сохранён, но ответ пуст"
            } else {
                "Файл сохранён, но ответ пуст"
            }
        } catch (e: Exception) {
            "Ошибка вызова MCP сервера сохранения файлов: ${e.message}"
        }
    }

    fun close() {
        try {
            writer?.close()
            reader?.close()
            serverProcess?.destroy()
            serverProcess?.waitFor()
            scope.cancel()
        } catch (e: Exception) {
            System.err.println("Error closing FileSaver MCP client: ${e.message}")
        }
    }
}



