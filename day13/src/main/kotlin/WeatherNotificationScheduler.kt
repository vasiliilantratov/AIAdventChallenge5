package org.example

import kotlinx.coroutines.*

/**
 * Планировщик для периодической отправки уведомлений о погоде
 */
class WeatherNotificationScheduler(
    private val subscriptionService: WeatherSubscriptionService,
    private val subscriptionDatabase: WeatherSubscriptionDatabase
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Запускает планировщик уведомлений
     */
    fun start() {
        if (job?.isActive == true) {
            return // Уже запущен
        }
        
        job = scope.launch {
            while (isActive) {
                try {
                    // Проверяем подписки, для которых наступило время уведомления
                    val dueSubscriptions = subscriptionDatabase.getSubscriptionsDueForNotification()
                    
                    // Отправляем уведомления для каждой подписки
                    dueSubscriptions.forEach { subscription ->
                        try {
                            subscriptionService.sendNotificationForSubscription(subscription)
                            // Небольшая задержка между уведомлениями
                            delay(1000)
                        } catch (e: Exception) {
                            System.err.println("Ошибка при отправке уведомления для ${subscription.city}: ${e.message}")
                        }
                    }
                    
                    // Проверяем каждую минуту
                    delay(2000) // 2 секунды
                } catch (e: Exception) {
                    System.err.println("Ошибка в планировщике уведомлений: ${e.message}")
                    delay(60000) // Продолжаем работу даже при ошибке
                }
            }
        }
    }
    
    /**
     * Останавливает планировщик
     */
    fun stop() {
        job?.cancel()
        scope.cancel()
    }
}

