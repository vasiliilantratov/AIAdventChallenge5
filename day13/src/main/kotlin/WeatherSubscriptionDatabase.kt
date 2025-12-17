package org.example

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * База данных для хранения подписок на погоду
 */
class WeatherSubscriptionDatabase(private val dbPath: String = "weather_subscriptions.db") {
    private val connection: Connection by lazy {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also {
            initializeDatabase(it)
        }
    }

    private fun initializeDatabase(conn: Connection) {
        // Проверяем, существует ли старая колонка interval_minutes
        val hasOldColumn = try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(weather_subscriptions)")
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "interval_minutes") {
                        found = true
                        break
                    }
                }
                found
            }
        } catch (e: Exception) {
            false
        }
        
        val createTableSQL = """
            CREATE TABLE IF NOT EXISTS weather_subscriptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                city TEXT NOT NULL UNIQUE,
                interval_seconds INTEGER DEFAULT 60,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL,
                last_notified_at INTEGER,
                next_notification_at INTEGER
            )
        """.trimIndent()

        conn.createStatement().use { stmt ->
            stmt.execute(createTableSQL)
        }
        
        // Миграция: если есть старая колонка interval_minutes, добавляем interval_seconds и конвертируем данные
        try {
            // Проверяем, есть ли уже interval_seconds
            val hasNewColumn = conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(weather_subscriptions)")
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "interval_seconds") {
                        found = true
                        break
                    }
                }
                found
            }
            
            if (!hasNewColumn && hasOldColumn) {
                // Добавляем новую колонку
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE weather_subscriptions ADD COLUMN interval_seconds INTEGER DEFAULT 60")
                }
                
                // Конвертируем данные: минуты -> секунды
                conn.createStatement().use { stmt ->
                    stmt.execute("UPDATE weather_subscriptions SET interval_seconds = interval_minutes * 60 WHERE interval_seconds = 60")
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки миграции - возможно, таблица еще не создана или колонка уже существует
        }
    }

    /**
     * Добавляет подписку на погоду для города
     */
    fun addSubscription(city: String, intervalSeconds: Int = 60): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val nextNotification = now + (intervalSeconds * 1000L)
            
            val sql = """
                INSERT INTO weather_subscriptions 
                (city, interval_seconds, is_active, created_at, next_notification_at) 
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(city) DO UPDATE SET
                    interval_seconds = excluded.interval_seconds,
                    is_active = 1,
                    next_notification_at = excluded.next_notification_at
            """.trimIndent()
            
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, city)
                stmt.setInt(2, intervalSeconds)
                stmt.setLong(3, now)
                stmt.setLong(4, nextNotification)
                stmt.executeUpdate() > 0
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при добавлении подписки: ${e.message}")
            false
        }
    }

    /**
     * Удаляет подписку (деактивирует)
     */
    fun removeSubscription(city: String): Boolean {
        return try {
            val sql = "UPDATE weather_subscriptions SET is_active = 0 WHERE city = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, city)
                stmt.executeUpdate() > 0
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при удалении подписки: ${e.message}")
            false
        }
    }

    /**
     * Получает все активные подписки
     */
    fun getActiveSubscriptions(): List<WeatherSubscription> {
        return try {
            val sql = """
                SELECT id, city, interval_seconds, is_active, created_at, 
                       last_notified_at, next_notification_at
                FROM weather_subscriptions
                WHERE is_active = 1
                ORDER BY city
            """.trimIndent()
            
            connection.prepareStatement(sql).use { stmt ->
                val rs = stmt.executeQuery()
                val subscriptions = mutableListOf<WeatherSubscription>()
                while (rs.next()) {
                    // Поддержка миграции: если interval_seconds нет, используем interval_minutes * 60
                    val intervalSeconds = try {
                        rs.getInt("interval_seconds")
                    } catch (e: Exception) {
                        try {
                            rs.getInt("interval_minutes") * 60
                        } catch (e2: Exception) {
                            60 // дефолтное значение
                        }
                    }
                    
                    subscriptions.add(
                        WeatherSubscription(
                            id = rs.getInt("id"),
                            city = rs.getString("city"),
                            intervalSeconds = intervalSeconds,
                            isActive = rs.getInt("is_active") == 1,
                            createdAt = rs.getLong("created_at"),
                            lastNotifiedAt = rs.getLongOrNull("last_notified_at"),
                            nextNotificationAt = rs.getLongOrNull("next_notification_at")
                        )
                    )
                }
                subscriptions
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при получении подписок: ${e.message}")
            emptyList()
        }
    }

    /**
     * Получает подписки, для которых наступило время уведомления
     */
    fun getSubscriptionsDueForNotification(): List<WeatherSubscription> {
        return try {
            val now = System.currentTimeMillis()
            val sql = """
                SELECT id, city, interval_seconds, is_active, created_at, 
                       last_notified_at, next_notification_at
                FROM weather_subscriptions
                WHERE is_active = 1 
                AND (next_notification_at IS NULL OR next_notification_at <= ?)
                ORDER BY city
            """.trimIndent()
            
            connection.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                val rs = stmt.executeQuery()
                val subscriptions = mutableListOf<WeatherSubscription>()
                while (rs.next()) {
                    // Поддержка миграции: если interval_seconds нет, используем interval_minutes * 60
                    val intervalSeconds = try {
                        rs.getInt("interval_seconds")
                    } catch (e: Exception) {
                        try {
                            rs.getInt("interval_minutes") * 60
                        } catch (e2: Exception) {
                            60 // дефолтное значение
                        }
                    }
                    
                    subscriptions.add(
                        WeatherSubscription(
                            id = rs.getInt("id"),
                            city = rs.getString("city"),
                            intervalSeconds = intervalSeconds,
                            isActive = rs.getInt("is_active") == 1,
                            createdAt = rs.getLong("created_at"),
                            lastNotifiedAt = rs.getLongOrNull("last_notified_at"),
                            nextNotificationAt = rs.getLongOrNull("next_notification_at")
                        )
                    )
                }
                subscriptions
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при получении подписок для уведомления: ${e.message}")
            emptyList()
        }
    }

    /**
     * Обновляет время последнего и следующего уведомления
     */
    fun updateNotificationTime(city: String, intervalSeconds: Int) {
        try {
            val now = System.currentTimeMillis()
            val nextNotification = now + (intervalSeconds * 1000L)
            
            val sql = """
                UPDATE weather_subscriptions 
                SET last_notified_at = ?, next_notification_at = ?
                WHERE city = ? AND is_active = 1
            """.trimIndent()
            
            connection.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                stmt.setLong(2, nextNotification)
                stmt.setString(3, city)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при обновлении времени уведомления: ${e.message}")
        }
    }

    /**
     * Обновляет интервал для подписки
     */
    fun updateInterval(city: String, intervalSeconds: Int): Boolean {
        return try {
            val sql = """
                UPDATE weather_subscriptions 
                SET interval_seconds = ?
                WHERE city = ? AND is_active = 1
            """.trimIndent()
            
            connection.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, intervalSeconds)
                stmt.setString(2, city)
                stmt.executeUpdate() > 0
            }
        } catch (e: SQLException) {
            System.err.println("Ошибка при обновлении интервала: ${e.message}")
            false
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

// Расширение для получения Long или null
private fun java.sql.ResultSet.getLongOrNull(columnLabel: String): Long? {
    val value = getLong(columnLabel)
    return if (wasNull()) null else value
}

