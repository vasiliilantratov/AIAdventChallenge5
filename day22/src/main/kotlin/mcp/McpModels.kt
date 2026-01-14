package org.example.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Модели данных для MCP (Model Context Protocol)
 * Представляют данные из CRM системы (пользователи и тикеты)
 */

@Serializable
data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val organization: String,
    val subscription: String,
    @SerialName("registered_at")
    val registeredAt: String
)

@Serializable
data class Ticket(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val title: String,
    val description: String,
    val status: String,
    val priority: String,
    val category: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("resolved_at")
    val resolvedAt: String? = null,
    val resolution: String? = null,
    @SerialName("assigned_to")
    val assignedTo: String? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class CrmData(
    val users: List<User>,
    val tickets: List<Ticket>
)

/**
 * Контекст пользователя для передачи в RAG систему
 */
data class UserContext(
    val user: User,
    val activeTickets: List<Ticket>,
    val recentTickets: List<Ticket>
) {
    /**
     * Форматирует контекст пользователя для передачи в LLM
     */
    fun toContextString(): String = buildString {
        appendLine("=== КОНТЕКСТ ПОЛЬЗОВАТЕЛЯ ===")
        appendLine()
        appendLine("Пользователь:")
        appendLine("  Имя: ${user.name}")
        appendLine("  Email: ${user.email}")
        appendLine("  Роль: ${user.role}")
        appendLine("  Организация: ${user.organization}")
        appendLine("  Подписка: ${user.subscription}")
        appendLine()
        
        if (activeTickets.isNotEmpty()) {
            appendLine("Активные тикеты:")
            activeTickets.forEach { ticket ->
                appendLine()
                appendLine("  Тикет #${ticket.id}")
                appendLine("    Название: ${ticket.title}")
                appendLine("    Описание: ${ticket.description}")
                appendLine("    Статус: ${ticket.status}")
                appendLine("    Приоритет: ${ticket.priority}")
                appendLine("    Категория: ${ticket.category}")
                if (ticket.tags.isNotEmpty()) {
                    appendLine("    Теги: ${ticket.tags.joinToString(", ")}")
                }
            }
            appendLine()
        }
        
        if (recentTickets.isNotEmpty()) {
            appendLine("Недавние решенные тикеты:")
            recentTickets.forEach { ticket ->
                appendLine()
                appendLine("  Тикет #${ticket.id}")
                appendLine("    Название: ${ticket.title}")
                appendLine("    Категория: ${ticket.category}")
                appendLine("    Решение: ${ticket.resolution ?: "Нет информации"}")
            }
        }
        
        appendLine()
        appendLine("=== КОНЕЦ КОНТЕКСТА ===")
    }
}
