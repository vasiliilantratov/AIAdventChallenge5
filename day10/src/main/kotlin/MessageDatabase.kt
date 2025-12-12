package org.example

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class MessageDatabase(private val dbPath: String = "chat_history.db") {
    private val connection: Connection by lazy {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also {
            initializeDatabase(it)
        }
    }

    private fun initializeDatabase(conn: Connection) {
        val createTableSQL = """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_summary INTEGER DEFAULT 0
            )
        """.trimIndent()

        conn.createStatement().use { stmt ->
            stmt.execute(createTableSQL)
        }
    }

    /**
     * Сохраняет сообщение в базу данных
     */
    fun saveMessage(role: String, content: String, isSummary: Boolean = false) {
        try {
            val sql = "INSERT INTO messages (role, content, is_summary) VALUES (?, ?, ?)"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, role)
                stmt.setString(2, content)
                stmt.setInt(3, if (isSummary) 1 else 0)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при сохранении сообщения в БД: ${e.message}")
        }
    }

    /**
     * Получает последние N сообщений из базы данных
     */
    fun getLastMessages(limit: Int = 20): List<ChatMessage> {
        return try {
            val sql = "SELECT role, content FROM messages ORDER BY created_at DESC LIMIT ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                val messages = mutableListOf<ChatMessage>()
                while (rs.next()) {
                    messages.add(
                        ChatMessage(
                            role = rs.getString("role"),
                            content = rs.getString("content")
                        )
                    )
                }
                // Возвращаем в правильном порядке (от старых к новым)
                messages.reversed()
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при чтении сообщений из БД: ${e.message}")
            emptyList()
        }
    }

    /**
     * Получает все сообщения из базы данных в порядке создания (для отправки в ИИ)
     * Порядок: system, summary (если есть), затем остальные сообщения по времени создания
     */
    fun getAllMessages(): List<ChatMessage> {
        return try {
            // Сортируем так: сначала system, затем summary (если есть), затем остальные по времени
            // Summary должен быть между system и последними двумя сообщениями
            // Используем сортировку: system (приоритет 1), summary (приоритет 2), остальные (приоритет 3)
            // Внутри каждой группы сортируем по времени создания
            val sql = """
                SELECT role, content 
                FROM messages 
                ORDER BY 
                    CASE 
                        WHEN role = 'system' THEN 1
                        WHEN is_summary = 1 THEN 2
                        ELSE 3
                    END,
                    created_at ASC
            """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                val rs = stmt.executeQuery()
                val messages = mutableListOf<ChatMessage>()
                while (rs.next()) {
                    messages.add(
                        ChatMessage(
                            role = rs.getString("role"),
                            content = rs.getString("content")
                        )
                    )
                }
                messages
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при чтении всех сообщений из БД: ${e.message}")
            emptyList()
        }
    }

    /**
     * Подсчитывает количество сообщений (user и assistant, без system)
     */
    fun getMessageCount(): Int {
        return try {
            val sql = "SELECT COUNT(*) as count FROM messages WHERE role IN ('user', 'assistant')"
            connection.prepareStatement(sql).use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.getInt("count")
                } else {
                    0
                }
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при подсчете сообщений: ${e.message}")
            0
        }
    }

    /**
     * Удаляет сообщения для summary (все кроме system и последних двух сообщений)
     * Возвращает количество удаленных сообщений
     */
    fun deleteMessagesForSummary(): Int {
        return try {
            // Получаем ID последних двух сообщений (user и assistant)
            val getLastTwoIdsSQL = """
                SELECT id FROM messages 
                WHERE role IN ('user', 'assistant')
                ORDER BY created_at DESC 
                LIMIT 2
            """.trimIndent()
            
            val lastTwoIds = mutableListOf<Int>()
            connection.prepareStatement(getLastTwoIdsSQL).use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    lastTwoIds.add(rs.getInt("id"))
                }
            }

            if (lastTwoIds.isEmpty()) {
                return 0
            }

            // Удаляем все сообщения кроме system и последних двух
            val placeholders = lastTwoIds.joinToString(",") { "?" }
            val deleteSQL = """
                DELETE FROM messages 
                WHERE role IN ('user', 'assistant') 
                AND id NOT IN ($placeholders)
            """.trimIndent()

            val deletedCount = connection.prepareStatement(deleteSQL).use { stmt ->
                lastTwoIds.forEachIndexed { index, id ->
                    stmt.setInt(index + 1, id)
                }
                stmt.executeUpdate()
            }
            
            deletedCount
        } catch (e: SQLException) {
            System.err.println("Ошибка при удалении сообщений для summary: ${e.message}")
            0
        }
    }

    /**
     * Очищает всю историю сообщений
     */
    fun clearHistory() {
        try {
            val sql = "DELETE FROM messages"
            connection.prepareStatement(sql).use { stmt ->
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при очистке истории: ${e.message}")
        }
    }

    /**
     * Закрывает соединение с базой данных
     */
    fun close() {
        try {
            connection.close()
        } catch (e: SQLException) {
            System.err.println("Ошибка при закрытии соединения с БД: ${e.message}")
        }
    }
}

