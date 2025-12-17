package org.example

import kotlinx.serialization.Serializable

/**
 * Модель данных для подписки на погоду
 */
@Serializable
data class WeatherSubscription(
    val id: Int,
    val city: String,
    val intervalSeconds: Int,
    val isActive: Boolean,
    val createdAt: Long,
    val lastNotifiedAt: Long?,
    val nextNotificationAt: Long?
)

