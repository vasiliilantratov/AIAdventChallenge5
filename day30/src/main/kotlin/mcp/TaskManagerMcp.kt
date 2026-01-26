package org.example.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.model.Task
import org.example.model.TaskList
import org.example.model.TaskPriority
import org.example.model.TaskStatus
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * MCP клиент для управления задачами команды.
 * Имитирует работу с таск-менеджером, сохраняя задачи в JSON файл.
 */
class TaskManagerMcp(
    private val tasksFile: File = File("tasks.json")
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        // Создаем файл с задачами по умолчанию, если его нет
        if (!tasksFile.exists()) {
            initializeDefaultTasks()
        }
    }

    /**
     * Получить все задачи
     */
    fun getAllTasks(): List<Task> {
        return readTasks()
    }

    /**
     * Получить задачи по приоритету
     */
    fun getTasksByPriority(priority: TaskPriority): List<Task> {
        return readTasks().filter { it.priority == priority }
    }

    /**
     * Получить задачи по статусу
     */
    fun getTasksByStatus(status: TaskStatus): List<Task> {
        return readTasks().filter { it.status == status }
    }

    /**
     * Получить задачу по ID
     */
    fun getTaskById(id: String): Task? {
        return readTasks().find { it.id == id }
    }

    /**
     * Создать новую задачу
     */
    fun createTask(
        title: String,
        description: String,
        priority: TaskPriority,
        status: TaskStatus = TaskStatus.TODO,
        assignee: String? = null,
        tags: List<String> = emptyList(),
        dueDate: String? = null
    ): Task {
        val tasks = readTasks().toMutableList()
        val now = LocalDateTime.now().format(dateFormatter)
        val newTask = Task(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            priority = priority,
            status = status,
            assignee = assignee,
            tags = tags,
            createdAt = now,
            updatedAt = now,
            dueDate = dueDate
        )
        tasks.add(newTask)
        saveTasks(tasks)
        return newTask
    }

    /**
     * Обновить статус задачи
     */
    fun updateTaskStatus(id: String, newStatus: TaskStatus): Task? {
        val tasks = readTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index == -1) return null

        val updatedTask = tasks[index].copy(
            status = newStatus,
            updatedAt = LocalDateTime.now().format(dateFormatter)
        )
        tasks[index] = updatedTask
        saveTasks(tasks)
        return updatedTask
    }

    /**
     * Обновить приоритет задачи
     */
    fun updateTaskPriority(id: String, newPriority: TaskPriority): Task? {
        val tasks = readTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index == -1) return null

        val updatedTask = tasks[index].copy(
            priority = newPriority,
            updatedAt = LocalDateTime.now().format(dateFormatter)
        )
        tasks[index] = updatedTask
        saveTasks(tasks)
        return updatedTask
    }

    /**
     * Удалить задачу
     */
    fun deleteTask(id: String): Boolean {
        val tasks = readTasks().toMutableList()
        val removed = tasks.removeIf { it.id == id }
        if (removed) {
            saveTasks(tasks)
        }
        return removed
    }

    /**
     * Получить статистику по задачам
     */
    fun getStats(): TaskStats {
        val tasks = readTasks()
        return TaskStats(
            total = tasks.size,
            byStatus = TaskStatus.entries.associateWith { status ->
                tasks.count { it.status == status }
            },
            byPriority = TaskPriority.entries.associateWith { priority ->
                tasks.count { it.priority == priority }
            }
        )
    }

    /**
     * Поиск задач по ключевому слову
     */
    fun searchTasks(keyword: String): List<Task> {
        val lowerKeyword = keyword.lowercase()
        return readTasks().filter { task ->
            task.title.lowercase().contains(lowerKeyword) ||
            task.description.lowercase().contains(lowerKeyword) ||
            task.tags.any { it.lowercase().contains(lowerKeyword) } ||
            task.assignee?.lowercase()?.contains(lowerKeyword) == true
        }
    }

    private fun readTasks(): List<Task> {
        if (!tasksFile.exists()) return emptyList()
        val content = tasksFile.readText()
        if (content.isBlank()) return emptyList()
        val taskList = json.decodeFromString<TaskList>(content)
        return taskList.tasks
    }

    private fun saveTasks(tasks: List<Task>) {
        val taskList = TaskList(tasks)
        val content = json.encodeToString(taskList)
        tasksFile.writeText(content)
    }

    private fun initializeDefaultTasks() {
        val defaultTasks = listOf(
            Task(
                id = UUID.randomUUID().toString(),
                title = "Реализовать аутентификацию пользователей",
                description = "Добавить JWT-токены для авторизации API. Включает регистрацию, логин и refresh токены.",
                priority = TaskPriority.HIGH,
                status = TaskStatus.IN_PROGRESS,
                assignee = "Алексей",
                tags = listOf("backend", "security", "auth"),
                createdAt = "2026-01-10T09:00:00",
                updatedAt = "2026-01-14T15:30:00",
                dueDate = "2026-01-20T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Оптимизировать запросы к базе данных",
                description = "Профилировать и оптимизировать медленные SQL запросы. Добавить индексы там, где нужно.",
                priority = TaskPriority.HIGH,
                status = TaskStatus.TODO,
                assignee = "Мария",
                tags = listOf("backend", "database", "performance"),
                createdAt = "2026-01-12T10:00:00",
                updatedAt = "2026-01-12T10:00:00",
                dueDate = "2026-01-25T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Написать документацию API",
                description = "Создать OpenAPI спецификацию для всех эндпоинтов. Добавить примеры запросов и ответов.",
                priority = TaskPriority.MEDIUM,
                status = TaskStatus.TODO,
                assignee = "Иван",
                tags = listOf("documentation", "api"),
                createdAt = "2026-01-11T14:00:00",
                updatedAt = "2026-01-11T14:00:00",
                dueDate = "2026-01-30T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Исправить баг с загрузкой файлов",
                description = "При загрузке файлов больше 5MB происходит таймаут. Нужно увеличить лимит и добавить прогресс-бар.",
                priority = TaskPriority.CRITICAL,
                status = TaskStatus.BLOCKED,
                assignee = "Алексей",
                tags = listOf("bug", "frontend", "file-upload"),
                createdAt = "2026-01-13T11:00:00",
                updatedAt = "2026-01-15T09:00:00",
                dueDate = "2026-01-17T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Добавить unit тесты для модуля платежей",
                description = "Покрыть тестами критичную логику обработки платежей. Минимум 80% coverage.",
                priority = TaskPriority.HIGH,
                status = TaskStatus.TODO,
                assignee = "Мария",
                tags = listOf("testing", "payments", "backend"),
                createdAt = "2026-01-09T08:00:00",
                updatedAt = "2026-01-09T08:00:00",
                dueDate = "2026-01-22T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Обновить зависимости проекта",
                description = "Обновить все npm пакеты до последних stable версий. Проверить, что ничего не сломалось.",
                priority = TaskPriority.LOW,
                status = TaskStatus.TODO,
                assignee = null,
                tags = listOf("maintenance", "dependencies"),
                createdAt = "2026-01-08T16:00:00",
                updatedAt = "2026-01-08T16:00:00",
                dueDate = null
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Настроить CI/CD pipeline",
                description = "Настроить автоматический деплой на staging при push в develop ветку.",
                priority = TaskPriority.MEDIUM,
                status = TaskStatus.IN_PROGRESS,
                assignee = "Иван",
                tags = listOf("devops", "ci-cd", "automation"),
                createdAt = "2026-01-10T12:00:00",
                updatedAt = "2026-01-14T10:00:00",
                dueDate = "2026-01-28T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Редизайн страницы профиля пользователя",
                description = "Обновить UI страницы профиля согласно новым дизайн-макетам от UX команды.",
                priority = TaskPriority.MEDIUM,
                status = TaskStatus.TODO,
                assignee = "Анна",
                tags = listOf("frontend", "ui", "design"),
                createdAt = "2026-01-14T09:00:00",
                updatedAt = "2026-01-14T09:00:00",
                dueDate = "2026-02-05T23:59:59"
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Добавить поддержку темной темы",
                description = "Реализовать переключатель между светлой и темной темой. Сохранять выбор пользователя.",
                priority = TaskPriority.LOW,
                status = TaskStatus.TODO,
                assignee = "Анна",
                tags = listOf("frontend", "ui", "theme"),
                createdAt = "2026-01-13T15:00:00",
                updatedAt = "2026-01-13T15:00:00",
                dueDate = null
            ),
            Task(
                id = UUID.randomUUID().toString(),
                title = "Провести code review для PR #142",
                description = "Проверить код новой фичи экспорта данных. Обратить внимание на безопасность и производительность.",
                priority = TaskPriority.HIGH,
                status = TaskStatus.DONE,
                assignee = "Мария",
                tags = listOf("code-review", "backend"),
                createdAt = "2026-01-13T10:00:00",
                updatedAt = "2026-01-14T17:00:00",
                dueDate = "2026-01-15T23:59:59"
            )
        )

        saveTasks(defaultTasks)
    }
}

data class TaskStats(
    val total: Int,
    val byStatus: Map<TaskStatus, Int>,
    val byPriority: Map<TaskPriority, Int>
)
