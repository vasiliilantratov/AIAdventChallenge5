package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

//const val DEFAULT_SYSTEM_PROMPT = "Ты — полезный ассистент, отвечай кратко и по делу."
const val DEFAULT_SYSTEM_PROMPT = "Ты — полезный ассистент, отвечай подробно с объяснениями"

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class OllamaMessage(
    val role: String,
    val content: String
)

@Serializable
data class OllamaChatResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("done_reason")
    val doneReason: String? = null
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val menuTitle: String
)

val SUPPORTED_MODELS: List<ModelOption> = listOf(
    ModelOption(
        id = "llama3.1:8b",
        displayName = "Llama 3.1 8B",
        menuTitle = "[1] Llama 3.1 8B (llama3.1:8b)"
    )
)

