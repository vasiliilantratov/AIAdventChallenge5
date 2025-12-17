package org.example

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * Сервис для управления подписками на погоду
 */
class WeatherSubscriptionService(
    private val subscriptionDatabase: WeatherSubscriptionDatabase,
    private val mcpClientManager: McpClientManager?,
    private val clothingService: ClothingRecommendationService,
    private val messageDatabase: MessageDatabase
) {
    
    /**
     * Добавляет подписку на погоду для города
     */
    fun addSubscription(city: String, intervalSeconds: Int = 60): String {
        val success = subscriptionDatabase.addSubscription(city, intervalSeconds)
        val intervalText = when {
            intervalSeconds < 60 -> "каждые $intervalSeconds секунд"
            intervalSeconds == 60 -> "каждую минуту"
            intervalSeconds < 3600 -> "каждые ${intervalSeconds / 60} минут"
            else -> "каждые ${intervalSeconds / 3600} часов"
        }
        return if (success) {
            "✓ Подписка на погоду для города '$city' добавлена. Уведомления будут приходить $intervalText."
        } else {
            "✗ Не удалось добавить подписку для города '$city'."
        }
    }
    
    /**
     * Удаляет подписку на погоду для города
     */
    fun removeSubscription(city: String): String {
        val success = subscriptionDatabase.removeSubscription(city)
        return if (success) {
            "✓ Подписка на погоду для города '$city' удалена."
        } else {
            "✗ Подписка для города '$city' не найдена или уже удалена."
        }
    }
    
    /**
     * Получает список всех активных подписок
     */
    fun listSubscriptions(): String {
        val subscriptions = subscriptionDatabase.getActiveSubscriptions()
        return if (subscriptions.isEmpty()) {
            "У вас нет активных подписок на погоду."
        } else {
            buildString {
                appendLine("Ваши активные подписки на погоду:")
                subscriptions.forEach { sub ->
                    val intervalText = when {
                        sub.intervalSeconds < 60 -> "каждые ${sub.intervalSeconds} секунд"
                        sub.intervalSeconds == 60 -> "каждую минуту"
                        sub.intervalSeconds < 3600 -> "каждые ${sub.intervalSeconds / 60} минут"
                        else -> "каждые ${sub.intervalSeconds / 3600} часов"
                    }
                    appendLine("  • ${sub.city} - $intervalText")
                }
            }
        }
    }
    
    /**
     * Обновляет интервал для подписки
     */
    fun updateInterval(city: String, intervalSeconds: Int): String {
        val success = subscriptionDatabase.updateInterval(city, intervalSeconds)
        val intervalText = when {
            intervalSeconds < 60 -> "$intervalSeconds секунд"
            intervalSeconds == 60 -> "1 минуты"
            intervalSeconds < 3600 -> "${intervalSeconds / 60} минут"
            else -> "${intervalSeconds / 3600} часов"
        }
        return if (success) {
            "✓ Интервал для города '$city' обновлен до $intervalText."
        } else {
            "✗ Не удалось обновить интервал. Проверьте, что подписка для '$city' существует и активна."
        }
    }
    
    /**
     * Отправляет уведомление о погоде для подписки
     */
    suspend fun sendNotificationForSubscription(subscription: WeatherSubscription): Boolean {
        if (mcpClientManager == null) {
            return false
        }
        
        return try {
            // Получаем погоду через MCP
            val argsJson = JsonObject(mapOf(
                "city" to JsonPrimitive(subscription.city)
            ))
            
            val weatherResult = mcpClientManager.callTool("get_current_weather", argsJson)
            
            // Парсим результат погоды
            val weatherJson = Json { ignoreUnknownKeys = true }
            val weatherData = weatherJson.parseToJsonElement(weatherResult)
            
            // Извлекаем данные о погоде из JSON ответа
            // Формат ответа от MCP: JSON строка с полными данными
            val weatherResponse = try {
                // Пробуем распарсить как JSON объект
                val jsonObj = weatherData.jsonObject
                val locationObj = jsonObj["location"]?.jsonObject
                val currentObj = jsonObj["current"]?.jsonObject
                
                if (locationObj != null && currentObj != null) {
                    // Формируем сообщение с рекомендациями
                    val message = buildString {
                        appendLine("🌤️ Погода для ${locationObj["name"]?.jsonPrimitive?.content ?: subscription.city}")
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("📍 Местоположение: ${locationObj["name"]?.jsonPrimitive?.content}, ${locationObj["region"]?.jsonPrimitive?.content}, ${locationObj["country"]?.jsonPrimitive?.content}")
                        appendLine("🕐 Время: ${locationObj["localTime"]?.jsonPrimitive?.content}")
                        appendLine()
                        
                        val tempC = currentObj["temperature_c"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        val feelsLikeC = currentObj["feels_like_c"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: tempC
                        val condition = currentObj["condition"]?.jsonPrimitive?.content ?: "Неизвестно"
                        val windKph = currentObj["wind_kph"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        val windDir = currentObj["wind_dir"]?.jsonPrimitive?.content ?: ""
                        val humidity = currentObj["humidity"]?.jsonPrimitive?.content ?: "0"
                        val precipMm = currentObj["precipitation_mm"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        val uv = currentObj["uv_index"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        
                        appendLine("🌡️ Температура: ${tempC}°C (ощущается как ${feelsLikeC}°C)")
                        appendLine("☁️ Условия: $condition")
                        appendLine("💨 Ветер: $windKph км/ч, направление: $windDir")
                        appendLine("💧 Влажность: $humidity%")
                        if (precipMm > 0) {
                            appendLine("☔ Осадки: ${precipMm} мм")
                        }
                        appendLine("☀️ UV индекс: $uv")
                        appendLine()
                        
                        // Генерируем рекомендации
                        val recommendation = clothingService.generateRecommendation(
                            tempC, condition, windKph, uv, precipMm
                        )
                        appendLine("👕 Рекомендации по одежде:")
                        appendLine("   $recommendation")
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    }
                    
                    // Сохраняем уведомление в базу сообщений
                    messageDatabase.saveMessage("assistant", message)
                    
                    // Выводим в консоль
                    println("\n$message")
                    
                    // Обновляем время следующего уведомления
                    subscriptionDatabase.updateNotificationTime(subscription.city, subscription.intervalSeconds)
                    
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                System.err.println("Ошибка при парсинге данных погоды: ${e.message}")
                false
            }
            
            weatherResponse
        } catch (e: Exception) {
            System.err.println("Ошибка при отправке уведомления для ${subscription.city}: ${e.message}")
            false
        }
    }
    
}

