package org.example.weather

import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Простой логгер для Weather API
 */
object WeatherApiLogger {
    private val logFile = File("weather_api.log")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    init {
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }
    
    fun logRequest(url: String, city: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val message = buildString {
            appendLine("\n[$timestamp] Weather API REQUEST")
            appendLine("  City: $city")
            appendLine("  URL: ${maskApiKey(url)}")
        }
        
        System.err.print(message)
        logFile.appendText(message)
    }
    
    fun logResponse(statusCode: Int, city: String, success: Boolean, responseBody: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val icon = if (success) "✓" else "✗"
        
        // Краткий вывод в stderr
        val consoleMessage = buildString {
            appendLine("[$timestamp] Weather API RESPONSE")
            appendLine("  $icon Status: $statusCode")
            appendLine("  City: $city")
            if (success) {
                // Показываем только первые 100 символов в консоли
                val preview = responseBody.take(100).replace("\n", " ")
                appendLine("  Data: $preview${if (responseBody.length > 100) "..." else ""}")
            } else {
                appendLine("  Error: ${responseBody.take(200)}")
            }
        }
        
        // Полный вывод в файл
        val fileMessage = buildString {
            appendLine("\n[$timestamp] Weather API RESPONSE")
            appendLine("  $icon Status: $statusCode")
            appendLine("  City: $city")
            appendLine("  Response Body:")
            appendLine(responseBody)
            appendLine()
        }
        
        System.err.print(consoleMessage)
        logFile.appendText(fileMessage)
    }
    
    private fun maskApiKey(url: String): String {
        return url.replace(Regex("key=[^&]+"), "key=***")
    }
}

class WeatherApiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.weatherapi.com/v1"
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Получить текущую погоду по названию города
     */
    fun getCurrentWeather(city: String): Result<WeatherApiResponse> {
        return try {
            val encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.toString())
            val url = "$baseUrl/current.json?key=$apiKey&q=$encodedCity&aqi=no"
            
            // Логируем запрос
            WeatherApiLogger.logRequest(url, city)
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            val responseBody = response.body()
            
            if (response.statusCode() in 200..299) {
                val weatherResponse = json.decodeFromString<WeatherApiResponse>(responseBody)
                
                // Логируем успешный ответ с полным телом
                WeatherApiLogger.logResponse(response.statusCode(), city, true, responseBody)
                
                Result.success(weatherResponse)
            } else {
                // Логируем ошибку HTTP
                WeatherApiLogger.logResponse(response.statusCode(), city, false, responseBody)
                
                Result.failure(Exception("HTTP Error ${response.statusCode()}: $responseBody"))
            }
        } catch (e: Exception) {
            // Логируем исключение
            WeatherApiLogger.logResponse(0, city, false, e.message ?: "Unknown error")
            
            Result.failure(e)
        }
    }
    
    /**
     * Форматирует ответ погоды в читаемый текст
     */
    fun formatWeatherResponse(weather: WeatherApiResponse): String {
        return buildString {
            appendLine("🌍 Погода в ${weather.location.name}, ${weather.location.region}, ${weather.location.country}")
            appendLine("🕐 Местное время: ${weather.location.localTime}")
            appendLine()
            appendLine("🌡️ Температура: ${weather.current.tempC}°C (${weather.current.tempF}°F)")
            appendLine("🤚 Ощущается как: ${weather.current.feelslikeC}°C (${weather.current.feelslikeF}°F)")
            appendLine("☁️ Состояние: ${weather.current.condition.text}")
            appendLine("💨 Ветер: ${weather.current.windKph} км/ч (${weather.current.windMph} миль/ч), направление ${weather.current.windDir}")
            appendLine("💧 Влажность: ${weather.current.humidity}%")
            appendLine("🌧️ Осадки: ${weather.current.precipMm} мм")
            appendLine("☁️ Облачность: ${weather.current.cloud}%")
            appendLine("📊 Давление: ${weather.current.pressureMb} мбар")
            appendLine("👁️ Видимость: ${weather.current.visKm} км")
            appendLine("☀️ УФ-индекс: ${weather.current.uv}")
        }
    }
}

