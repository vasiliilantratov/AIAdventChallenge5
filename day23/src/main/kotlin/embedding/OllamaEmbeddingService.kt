package org.example.embedding

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbeddingRequest(
    val model: String,
    val prompt: String
)

@Serializable
data class EmbeddingResponse(
    val embedding: List<Float>
)

class OllamaEmbeddingService(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text:latest"
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
    
    suspend fun getEmbedding(text: String, retries: Int = 3): FloatArray {
        var lastException: Exception? = null
        
        repeat(retries) { attempt ->
            try {
                val response = client.post("$baseUrl/api/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody(EmbeddingRequest(model = model, prompt = text))
                }
                
                if (response.status.isSuccess()) {
                    val embeddingResponse = response.body<EmbeddingResponse>()
                    return embeddingResponse.embedding.toFloatArray()
                } else {
                    throw Exception("Ollama API returned status ${response.status}")
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < retries - 1) {
                    delay((1000 * (attempt + 1)).toLong()) // Exponential backoff
                }
            }
        }
        
        throw Exception("Failed to get embedding after $retries retries", lastException)
    }
    
    suspend fun getEmbeddingsBatch(texts: List<String>): List<FloatArray> {
        return texts.map { getEmbedding(it) }
    }
    
    fun close() {
        client.close()
    }
}

