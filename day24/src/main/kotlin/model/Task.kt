package org.example.model

import kotlinx.serialization.Serializable

@Serializable
enum class TaskPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

@Serializable
enum class TaskStatus {
    TODO, IN_PROGRESS, DONE, BLOCKED
}

@Serializable
data class Task(
    val id: String,
    val title: String,
    val description: String,
    val priority: TaskPriority,
    val status: TaskStatus,
    val assignee: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val dueDate: String? = null
)

@Serializable
data class TaskList(
    val tasks: List<Task>
)
