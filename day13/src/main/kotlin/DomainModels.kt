package org.example

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.builtins.*

//const val DEFAULT_SYSTEM_PROMPT = "Ты — полезный ассистент, отвечай кратко и по делу."
const val DEFAULT_SYSTEM_PROMPT = """Ты — полезный ассистент, отвечай подробно с объяснениями.

Дополнительно, ты можешь управлять подписками на периодические уведомления о погоде:
- Если пользователь просит присылать погоду для города (например: "Присылай мне погоду для Москвы", "Подпиши меня на погоду в Лондоне"), 
  используй инструмент manage_weather_subscription с действием "add" и укажи название города.
- Если пользователь просит перестать присылать погоду для города (например: "Перестань присылать погоду для Москвы", "Отмени подписку на погоду в Лондоне"), 
  используй инструмент manage_weather_subscription с действием "remove" и укажи название города.
- Если пользователь спрашивает о подписках (например: "Какие у меня подписки?", "Покажи мои подписки на погоду"), 
  используй инструмент manage_weather_subscription с действием "list".
- Если пользователь хочет изменить интервал уведомлений (например: "Присылай погоду для Москвы каждые 30 секунд", "Присылай погоду каждую минуту"), 
  используй инструмент manage_weather_subscription с действием "add" или "update_interval" и укажи interval_seconds (в секундах).

При добавлении подписки пользователь будет автоматически получать периодические уведомления о погоде с рекомендациями по одежде."""

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val tools: List<Map<String, @Serializable(with = AnySerializer::class) Any>>? = null
)

@Serializable
data class OllamaMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

@Serializable
data class OllamaChatResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("done_reason")
    val doneReason: String? = null
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val menuTitle: String
)

val SUPPORTED_MODELS: List<ModelOption> = listOf(
    ModelOption(
        id = "llama3.1:8b",
        displayName = "Llama 3.1 8B",
        menuTitle = "[1] Llama 3.1 8B (llama3.1:8b)"
    )
)

// Сериализатор для Any типа (для поддержки динамических tool параметров)
@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Any", PolymorphicKind.SEALED)
    
    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonElement = when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { 
                it.key.toString() to serializeAny(it.value) 
            })
            is List<*> -> JsonArray(value.map { serializeAny(it) })
            else -> JsonPrimitive(value.toString())
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }
    
    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as JsonDecoder
        return deserializeJsonElement(jsonDecoder.decodeJsonElement())
    }
    
    private fun serializeAny(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { 
            it.key.toString() to serializeAny(it.value) 
        })
        is List<*> -> JsonArray(value.map { serializeAny(it) })
        else -> JsonPrimitive(value.toString())
    }
    
    private fun deserializeJsonElement(element: JsonElement): Any = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                element.content.toIntOrNull() != null -> element.content.toInt()
                element.content.toLongOrNull() != null -> element.content.toLong()
                element.content.toDoubleOrNull() != null -> element.content.toDouble()
                else -> element.content
            }
        }
        is JsonObject -> element.mapValues { deserializeJsonElement(it.value) }
        is JsonArray -> element.map { deserializeJsonElement(it) }
    }
}

@Serializable
data class ToolCall(
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    @Serializable(with = ArgumentsSerializer::class)
    val arguments: String
)

/**
 * Сериализатор для arguments в ToolFunction
 * При получении (deserialize): конвертирует объект в строку для внутреннего использования
 * При отправке (serialize): конвертирует строку обратно в объект для Ollama
 */
object ArgumentsSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Arguments", PrimitiveKind.STRING)
    
    override fun serialize(encoder: Encoder, value: String) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            // Пробуем распарсить строку как JSON и отправить как объект
            try {
                val json = Json { ignoreUnknownKeys = true }
                val element = json.parseToJsonElement(value)
                jsonEncoder.encodeJsonElement(element)
            } catch (e: Exception) {
                // Если не JSON, отправляем как строку
                encoder.encodeString(value)
            }
        } else {
            encoder.encodeString(value)
        }
    }
    
    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder != null) {
            val element = jsonDecoder.decodeJsonElement()
            return when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> element.toString()
                is JsonArray -> element.toString()
                else -> element.toString()
            }
        }
        return decoder.decodeString()
    }
}
