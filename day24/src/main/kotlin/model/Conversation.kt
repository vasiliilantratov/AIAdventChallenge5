package org.example.model

import kotlinx.serialization.Serializable

data class Conversation(
    val id: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class Message(
    val id: Long? = null,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val mode: String, // "plain" или "rag"
    val sourcesJson: String? = null, // JSON с информацией об источниках
    val createdAt: Long
)

enum class MessageRole {
    USER,
    ASSISTANT
}

@Serializable
data class SourceInfo(
    val documentPath: String,
    val documentName: String,
    val documentType: String,
    val chunkIndex: Int
)

