package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_SYSTEM_PROMPT = "Ты — полезный ассистент, отвечай кратко и по делу."

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
data class ChatChoiceMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatChoiceMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val menuTitle: String
)

val SUPPORTED_MODELS: List<ModelOption> = listOf(
    ModelOption(
        id = "deepseek-ai/DeepSeek-R1:novita",
        displayName = "DeepSeek-R1",
        menuTitle = "[1] DeepSeek-R1 (deepseek-ai/DeepSeek-R1:novita)"
    ),
    ModelOption(
        id = "unsloth/Qwen3-8B:featherless-ai",
        displayName = "Qwen3-8B",
        menuTitle = "[2] Qwen3-8B (unsloth/Qwen3-8B:featherless-ai)"
    ),
    ModelOption(
        id = "benfielding/Qwen2.5-0.5B-Instruct-Gensyn-Swarm-alert_arctic_dinosaur:featherless-ai",
        displayName = "Qwen2.5-0.5B",
        menuTitle = "[3] Qwen2.5-0.5B (benfielding/Qwen2.5-0.5B-Instruct-Gensyn-Swarm-alert_arctic_dinosaur:featherless-ai)"
    )
)

