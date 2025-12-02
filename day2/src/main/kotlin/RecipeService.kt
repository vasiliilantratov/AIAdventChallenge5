package org.example

import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

/**
 * Сервис для работы с рецептами
 */
class RecipeService(private val chatClient: OllamaChatClient) {
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Формирует системный промпт для ИИ с подстановкой названия блюда
     */
    fun createSystemPrompt(dishName: String): String {
        return """Ты — кулинарный помощник.

ЗАДАЧА:
Пользователь задаёт название блюда. Твоя задача — вернуть список ингредиентов с количеством для приготовления этого блюда.

ТРЕБОВАНИЯ К ОТВЕТУ:
1. Отвечай строго в формате JSON.
2. Не добавляй никакого текста до или после JSON.
3. Не используй комментарии, пояснения и лишние поля.

СХЕМА JSON:
{
  "dish": string,          // название блюда
  "servings": number,      // количество порций
  "ingredients": [
    {
      "name": string,      // название ингредиента
      "amount": number,    // количество
      "unit": string       // единица измерения (г, мл, шт и т.п.)
    }
  ]
}

Теперь сгенерируй JSON для блюда: "$dishName".

Отвечай ТОЛЬКО JSON-объектом, без пояснений."""
    }
    
    /**
     * Получает рецепт от ИИ для указанного блюда
     */
    fun getRecipe(dishName: String): RecipeValidationResult {
        var aiResponse: String? = null
        
        try {
            // Формируем промпт
            val systemPrompt = createSystemPrompt(dishName)
            
            // Очищаем историю чата перед новым запросом
            chatClient.clearHistory()
            
            // Устанавливаем системное сообщение с промптом
            chatClient.setSystemMessage(systemPrompt)
            
            // Отправляем пустое сообщение пользователя (промпт уже в системном сообщении)
            // или можно отправить название блюда как пользовательское сообщение
            aiResponse = chatClient.sendMessage("Сгенерируй JSON для блюда: \"$dishName\".")
            
            // Проверяем, не вернулась ли ошибка HTTP
            if (aiResponse.startsWith("Ошибка HTTP:") || aiResponse.startsWith("Ошибка при обращении к модели:")) {
                System.err.println("Ответ ИИ (ошибка HTTP/соединения): $aiResponse")
                return RecipeValidationResult.Error("Ошибка соединения с ИИ")
            }
            
            // Пытаемся извлечь JSON из ответа (на случай, если ИИ добавил лишний текст)
            val jsonText = extractJsonFromResponse(aiResponse)
            
            // Парсим JSON
            val recipe = json.decodeFromString<Recipe>(jsonText)
            
            // Валидируем рецепт
            return validateRecipe(recipe)
            
        } catch (e: SerializationException) {
            // Логируем ответ ИИ при ошибке парсинга
            if (aiResponse != null) {
                System.err.println("Ответ ИИ (ошибка парсинга): $aiResponse")
            }
            return RecipeValidationResult.Error(
                "Ошибка парсинга JSON: ${e.message ?: e.toString()}"
            )
        } catch (e: IllegalArgumentException) {
            // Логируем ответ ИИ при ошибке извлечения JSON
            if (aiResponse != null) {
                System.err.println("Ответ ИИ (ошибка извлечения JSON): $aiResponse")
            }
            return RecipeValidationResult.Error(
                "Ошибка извлечения JSON из ответа: ${e.message ?: e.toString()}"
            )
        } catch (e: Exception) {
            // Логируем ответ ИИ при общей ошибке
            if (aiResponse != null) {
                System.err.println("Ответ ИИ (общая ошибка): $aiResponse")
            }
            return RecipeValidationResult.Error(
                "Ошибка при получении рецепта: ${e.message}"
            )
        }
    }
    
    /**
     * Извлекает JSON из ответа ИИ, удаляя возможные лишние символы до и после JSON
     */
    private fun extractJsonFromResponse(response: String): String {
        val trimmed = response.trim()
        
        // Ищем начало JSON объекта
        val startIndex = trimmed.indexOfFirst { it == '{' }
        if (startIndex == -1) {
            throw IllegalArgumentException("JSON объект не найден в ответе")
        }
        
        // Ищем конец JSON объекта (последняя закрывающая скобка на том же уровне вложенности)
        var braceCount = 0
        var endIndex = startIndex
        
        for (i in startIndex until trimmed.length) {
            when (trimmed[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        endIndex = i + 1
                        break
                    }
                }
            }
        }
        
        if (braceCount != 0) {
            throw IllegalArgumentException("Неполный JSON объект в ответе")
        }
        
        return trimmed.substring(startIndex, endIndex)
    }
}

