package org.example

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class ApiCallResult {
    data class Success(val response: ChatResponse) : ApiCallResult()
    data class NetworkError(val message: String) : ApiCallResult()
    data class HttpError(val statusCode: Int, val bodySnippet: String) : ApiCallResult()
    object JsonError : ApiCallResult()
    data class UnknownError(val message: String) : ApiCallResult()
}

class ChatApiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    fun sendChatRequest(model: String, messages: List<ChatMessage>): ApiCallResult {
        return try {
            val body = json.encodeToString(
                ChatRequest(
                    model = model,
                    messages = messages,
                    stream = false
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                val snippet = response.body().orEmpty().take(500)
                return ApiCallResult.HttpError(response.statusCode(), snippet)
            }

            val parsed = try {
                json.decodeFromString<ChatResponse>(response.body())
            } catch (_: Exception) {
                return ApiCallResult.JsonError
            }

            ApiCallResult.Success(parsed)
        } catch (e: HttpTimeoutException) {
            ApiCallResult.NetworkError("Timeout: ${e.message}")
        } catch (e: HttpConnectTimeoutException) {
            ApiCallResult.NetworkError("Timeout: ${e.message}")
        } catch (e: ConnectException) {
            ApiCallResult.NetworkError(e.message ?: "Connection refused")
        } catch (e: UnknownHostException) {
            ApiCallResult.NetworkError(e.message ?: "Unknown host")
        } catch (e: IOException) {
            ApiCallResult.NetworkError(e.message ?: "I/O error")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ApiCallResult.NetworkError("Interrupted: ${e.message}")
        } catch (e: Exception) {
            ApiCallResult.UnknownError(e.message ?: "Unexpected error")
        }
    }
}
