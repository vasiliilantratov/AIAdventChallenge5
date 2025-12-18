package org.example.filesaver

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

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
 * MCP сервер для сохранения текста в файлы в папку {projectDir}/savedFiles
 * Использует JSON-RPC поверх stdio, по аналогии с weather-server.
 */
fun main() {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val projectDir = File(System.getProperty("user.dir"))
    val savedDir = File(projectDir, "savedFiles")

    if (!savedDir.exists()) {
        val created = savedDir.mkdirs()
        System.err.println("Creating savedFiles directory at: ${savedDir.absolutePath}, success=$created")
    } else {
        System.err.println("Using existing savedFiles directory at: ${savedDir.absolutePath}")
    }

    val reader = BufferedReader(InputStreamReader(System.`in`))
    val writer = OutputStreamWriter(System.out)

    System.err.println("File Saver MCP Server started")

    try {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            System.err.println("Received: $line")

            try {
                val request = json.decodeFromString<JsonRpcRequest>(line)
                val response = handleRequest(request, json, savedDir)
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
                    error = JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(-32700),
                            "message" to JsonPrimitive("Parse error: ${e.message}")
                        )
                    )
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

private fun handleRequest(
    request: JsonRpcRequest,
    json: Json,
    savedDir: File
): JsonRpcResponse {
    return when (request.method) {
        "initialize" -> {
            JsonRpcResponse(
                id = request.id,
                result = JsonObject(
                    mapOf(
                        "protocolVersion" to JsonPrimitive("2024-11-05"),
                        "capabilities" to JsonObject(
                            mapOf(
                                "tools" to JsonObject(emptyMap())
                            )
                        ),
                        "serverInfo" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("file-saver-server"),
                                "version" to JsonPrimitive("1.0.0")
                            )
                        )
                    )
                )
            )
        }

        "tools/list" -> {
            JsonRpcResponse(
                id = request.id,
                result = JsonObject(
                    mapOf(
                        "tools" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive("save_text_to_file"),
                                        "description" to JsonPrimitive(
                                            "Сохранить переданный текст в файл в папку savedFiles в корне проекта."
                                        ),
                                        "inputSchema" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "content" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("string"),
                                                                "description" to JsonPrimitive("Текст, который нужно сохранить в файл")
                                                            )
                                                        ),
                                                        "filename" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("string"),
                                                                "description" to JsonPrimitive("Необязательное имя файла. Если не указано, будет сгенерировано автоматически.")
                                                            )
                                                        )
                                                    )
                                                ),
                                                "required" to JsonArray(listOf(JsonPrimitive("content")))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }

        "tools/call" -> {
            val params = request.params ?: return JsonRpcResponse(
                id = request.id,
                error = JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32602),
                        "message" to JsonPrimitive("Missing params")
                    )
                )
            )

            val toolName = params["name"]?.jsonPrimitive?.content
            val arguments = params["arguments"]?.jsonObject

            if (toolName == null || arguments == null) {
                return JsonRpcResponse(
                    id = request.id,
                    error = JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(-32602),
                            "message" to JsonPrimitive("Invalid params")
                        )
                    )
                )
            }

            when (toolName) {
                "save_text_to_file" -> handleSaveTextTool(request.id, arguments, savedDir, json)

                else -> {
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonObject(
                            mapOf(
                                "code" to JsonPrimitive(-32601),
                                "message" to JsonPrimitive("Unknown tool: $toolName")
                            )
                        )
                    )
                }
            }
        }

        else -> {
            JsonRpcResponse(
                id = request.id,
                error = JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32601),
                        "message" to JsonPrimitive("Method not found: ${request.method}")
                    )
                )
            )
        }
    }
}

private fun handleSaveTextTool(
    id: JsonElement?,
    arguments: JsonObject,
    savedDir: File,
    json: Json
): JsonRpcResponse {
    val content = arguments["content"]?.jsonPrimitive?.content
    val filenameArg = arguments["filename"]?.jsonPrimitive?.content

    if (content.isNullOrBlank()) {
        return JsonRpcResponse(
            id = id,
            error = JsonObject(
                mapOf(
                    "code" to JsonPrimitive(-32602),
                    "message" to JsonPrimitive("Missing required parameter: content")
                )
            )
        )
    }

    val safeFilename = (filenameArg ?: generateDefaultFilename()).replace(Regex("""[^\w\-.]"""), "_")
    val targetFile = File(savedDir, safeFilename)

    return try {
        targetFile.writeText(content)

        val resultData = JsonObject(
            mapOf(
                "filePath" to JsonPrimitive(targetFile.absolutePath),
                "relativePath" to JsonPrimitive(savedDir.toPath().relativize(targetFile.toPath()).toString()),
                "message" to JsonPrimitive("Текст успешно сохранён в файл: ${targetFile.absolutePath}")
            )
        )

        JsonRpcResponse(
            id = id,
            result = JsonObject(
                mapOf(
                    "content" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive(json.encodeToString(resultData))
                                )
                            )
                        )
                    )
                )
            )
        )
    } catch (e: Exception) {
        System.err.println("Error writing file: ${e.message}")
        e.printStackTrace(System.err)

        JsonRpcResponse(
            id = id,
            result = JsonObject(
                mapOf(
                    "content" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive("Ошибка сохранения файла: ${e.message}")
                                )
                            )
                        )
                    ),
                    "isError" to JsonPrimitive(true)
                )
            )
        )
    }
}

private fun generateDefaultFilename(): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val randomPart = Random.nextInt(1000, 9999)
    return "saved-$timestamp-$randomPart.txt"
}


