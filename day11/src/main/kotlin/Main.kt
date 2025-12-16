package org.example

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered

fun main() = runBlocking {
    // 1) Запускаем MCP-сервер как процесс (stdio transport)
    val process = ProcessBuilder("npx", "-y", "@modelcontextprotocol/server-everything")
        .redirectError(ProcessBuilder.Redirect.INHERIT) // чтобы логи сервера шли в stderr
        .start()

    try {
        // 2) Соединяемся клиентом по stdio:
        // input клиента = stdout сервера
        // output клиента = stdin сервера
        val transport = StdioClientTransport(
            input = process.inputStream.asInput(),
            output = process.outputStream.asSink().buffered()
        )

        val client = Client(
            clientInfo = Implementation(name = "kotlin-mcp-client", version = "1.0.0")
        )

        client.connect(transport)

        // 3) Запрашиваем список инструментов (tools/list)
        val tools = client.listTools()

        println("MCP tools:")
        tools.tools.forEach { tool ->
            println("- ${tool.name}: ${tool.description ?: "No description"}")
        }
    } finally {
        process.destroy()
    }
}
