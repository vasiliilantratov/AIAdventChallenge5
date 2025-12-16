package org.example.weather

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonObject? = null
)

/**
 * Simple MCP Weather Server using JSON-RPC over stdio
 */
fun main() {
    val apiKey = "723a066898ce45aa97c180652251612"
    val weatherClient = WeatherApiClient(apiKey)
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    val reader = BufferedReader(InputStreamReader(System.`in`))
    val writer = OutputStreamWriter(System.out)
    
    System.err.println("Weather MCP Server started")
    
    try {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            
            System.err.println("Received: $line")
            
            try {
                val request = json.decodeFromString<JsonRpcRequest>(line)
                val response = handleRequest(request, weatherClient, json)
                val responseJson = json.encodeToString(response)
                
                writer.write(responseJson)
                writer.write("\n")
                writer.flush()
                
                System.err.println("Sent: $responseJson")
            } catch (e: Exception) {
                System.err.println("Error processing request: ${e.message}")
                e.printStackTrace(System.err)
                
                val errorResponse = JsonRpcResponse(
                    id = null,
                    error = JsonObject(mapOf(
                        "code" to JsonPrimitive(-32700),
                        "message" to JsonPrimitive("Parse error: ${e.message}")
                    ))
                )
                writer.write(json.encodeToString(errorResponse))
                writer.write("\n")
                writer.flush()
            }
        }
    } catch (e: Exception) {
        System.err.println("Fatal error: ${e.message}")
        e.printStackTrace(System.err)
    }
}

private fun handleRequest(request: JsonRpcRequest, weatherClient: WeatherApiClient, json: Json): JsonRpcResponse {
    return when (request.method) {
        "initialize" -> {
            JsonRpcResponse(
                id = request.id,
                result = JsonObject(mapOf(
                    "protocolVersion" to JsonPrimitive("2024-11-05"),
                    "capabilities" to JsonObject(mapOf(
                        "tools" to JsonObject(emptyMap())
                    )),
                    "serverInfo" to JsonObject(mapOf(
                        "name" to JsonPrimitive("weather-server"),
                        "version" to JsonPrimitive("1.0.0")
                    ))
                ))
            )
        }
        
        "tools/list" -> {
            JsonRpcResponse(
                id = request.id,
                result = JsonObject(mapOf(
                    "tools" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "name" to JsonPrimitive("get_current_weather"),
                            "description" to JsonPrimitive("Получить текущую погоду по названию города. Возвращает температуру, условия, влажность, ветер и другую информацию о погоде."),
                            "inputSchema" to JsonObject(mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(mapOf(
                                    "city" to JsonObject(mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Название города для получения погоды (например: Moscow, London, New York)")
                                    ))
                                )),
                                "required" to JsonArray(listOf(JsonPrimitive("city")))
                            ))
                        ))
                    ))
                ))
            )
        }
        
        "tools/call" -> {
            val params = request.params ?: return JsonRpcResponse(
                id = request.id,
                error = JsonObject(mapOf(
                    "code" to JsonPrimitive(-32602),
                    "message" to JsonPrimitive("Missing params")
                ))
            )
            
            val toolName = params["name"]?.jsonPrimitive?.content
            val arguments = params["arguments"]?.jsonObject
            
            if (toolName == null || arguments == null) {
                return JsonRpcResponse(
                    id = request.id,
                    error = JsonObject(mapOf(
                        "code" to JsonPrimitive(-32602),
                        "message" to JsonPrimitive("Invalid params")
                    ))
                )
            }
            
            when (toolName) {
                "get_current_weather" -> {
                    val city = arguments["city"]?.jsonPrimitive?.content
                    if (city == null) {
                        return JsonRpcResponse(
                            id = request.id,
                            error = JsonObject(mapOf(
                                "code" to JsonPrimitive(-32602),
                                "message" to JsonPrimitive("Missing required parameter: city")
                            ))
                        )
                    }
                    
                    val result = weatherClient.getCurrentWeather(city)
                    result.fold(
                        onSuccess = { weather ->
                            val formattedWeather = weatherClient.formatWeatherResponse(weather)
                            
                            val weatherData = JsonObject(mapOf(
                                "location" to JsonObject(mapOf(
                                    "name" to JsonPrimitive(weather.location.name),
                                    "region" to JsonPrimitive(weather.location.region),
                                    "country" to JsonPrimitive(weather.location.country),
                                    "localTime" to JsonPrimitive(weather.location.localTime)
                                )),
                                "current" to JsonObject(mapOf(
                                    "temperature_c" to JsonPrimitive(weather.current.tempC),
                                    "temperature_f" to JsonPrimitive(weather.current.tempF),
                                    "condition" to JsonPrimitive(weather.current.condition.text),
                                    "feels_like_c" to JsonPrimitive(weather.current.feelslikeC),
                                    "feels_like_f" to JsonPrimitive(weather.current.feelslikeF),
                                    "wind_kph" to JsonPrimitive(weather.current.windKph),
                                    "wind_dir" to JsonPrimitive(weather.current.windDir),
                                    "humidity" to JsonPrimitive(weather.current.humidity),
                                    "precipitation_mm" to JsonPrimitive(weather.current.precipMm),
                                    "cloud" to JsonPrimitive(weather.current.cloud),
                                    "pressure_mb" to JsonPrimitive(weather.current.pressureMb),
                                    "visibility_km" to JsonPrimitive(weather.current.visKm),
                                    "uv_index" to JsonPrimitive(weather.current.uv)
                                )),
                                "formatted" to JsonPrimitive(formattedWeather)
                            ))
                            
                            JsonRpcResponse(
                                id = request.id,
                                result = JsonObject(mapOf(
                                    "content" to JsonArray(listOf(
                                        JsonObject(mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive(json.encodeToString(weatherData))
                                        ))
                                    ))
                                ))
                            )
                        },
                        onFailure = { error ->
                            JsonRpcResponse(
                                id = request.id,
                                result = JsonObject(mapOf(
                                    "content" to JsonArray(listOf(
                                        JsonObject(mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive("Ошибка получения погоды: ${error.message}")
                                        ))
                                    )),
                                    "isError" to JsonPrimitive(true)
                                ))
                            )
                        }
                    )
                }
                else -> {
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonObject(mapOf(
                            "code" to JsonPrimitive(-32601),
                            "message" to JsonPrimitive("Unknown tool: $toolName")
                        ))
                    )
                }
            }
        }
        
        else -> {
            JsonRpcResponse(
                id = request.id,
                error = JsonObject(mapOf(
                    "code" to JsonPrimitive(-32601),
                    "message" to JsonPrimitive("Method not found: ${request.method}")
                ))
            )
        }
    }
}
