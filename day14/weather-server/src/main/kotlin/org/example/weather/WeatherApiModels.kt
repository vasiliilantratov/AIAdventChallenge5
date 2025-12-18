package org.example.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WeatherApiResponse(
    val location: Location,
    val current: Current
)

@Serializable
data class Location(
    val name: String,
    val region: String,
    val country: String,
    @SerialName("localtime")
    val localTime: String
)

@Serializable
data class Current(
    @SerialName("temp_c")
    val tempC: Double,
    @SerialName("temp_f")
    val tempF: Double,
    val condition: Condition,
    @SerialName("wind_kph")
    val windKph: Double,
    @SerialName("wind_mph")
    val windMph: Double,
    @SerialName("wind_dir")
    val windDir: String,
    @SerialName("pressure_mb")
    val pressureMb: Double,
    @SerialName("precip_mm")
    val precipMm: Double,
    val humidity: Int,
    val cloud: Int,
    @SerialName("feelslike_c")
    val feelslikeC: Double,
    @SerialName("feelslike_f")
    val feelslikeF: Double,
    @SerialName("vis_km")
    val visKm: Double,
    @SerialName("uv")
    val uv: Double
)

@Serializable
data class Condition(
    val text: String,
    val icon: String,
    val code: Int
)

