package org.example.adb

import kotlinx.serialization.Serializable

/**
 * Модель Android устройства
 */
@Serializable
data class AndroidDevice(
    val serialNumber: String,
    val status: String,
    val type: DeviceType = DeviceType.UNKNOWN,
    val model: String? = null,
    val product: String? = null
)

/**
 * Тип устройства
 */
enum class DeviceType {
    EMULATOR,      // Эмулятор
    PHYSICAL,      // Физическое устройство
    UNKNOWN        // Неизвестный тип
}

/**
 * Ответ со списком устройств
 */
@Serializable
data class AdbDevicesResponse(
    val devices: List<AndroidDevice>,
    val totalCount: Int,
    val onlineCount: Int,
    val offlineCount: Int,
    val unauthorizedCount: Int
) {
    companion object {
        fun empty() = AdbDevicesResponse(
            devices = emptyList(),
            totalCount = 0,
            onlineCount = 0,
            offlineCount = 0,
            unauthorizedCount = 0
        )
    }
}

