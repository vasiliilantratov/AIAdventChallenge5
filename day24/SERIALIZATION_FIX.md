# ⚠️ Исправление: Serialization Exception

## Проблема

При попытке выполнить релиз возникала ошибка:
```
kotlinx.serialization.SerializationException: Serializer for class 'Any' is not found.
Please ensure that class is marked as '@Serializable' and that the serialization 
compiler plugin is applied.
```

**Причина:** Попытка сериализовать `Map<String, Any>` с помощью `Json.encodeToString()`.

Kotlinx.serialization не может работать с типом `Any` без явного указания сериализатора, потому что `Any` может быть чем угодно (Boolean, Int, Long, String и т.д.).

## Решение

Заменили автоматическую сериализацию на ручное создание JSON строки.

### Было (неправильно):

```kotlin
val releaseInfo = mapOf(
    "success" to result.success,           // Boolean
    "filesCount" to result.uploadedFiles.size,  // Int
    "durationMs" to result.durationMs,     // Long
    "remoteDir" to "/root/release",        // String
    "sshConfig" to "my_mon_bot"            // String
)

sourcesJson = Json.encodeToString(releaseInfo)
// Ошибка! Map<String, Any> не может быть сериализован
```

### Стало (правильно):

```kotlin
val releaseInfoJson = buildString {
    append("{")
    append("\"success\":${result.success},")
    append("\"filesCount\":${result.uploadedFiles.size},")
    append("\"durationMs\":${result.durationMs},")
    append("\"remoteDir\":\"/root/release\",")
    append("\"sshConfig\":\"my_mon_bot\"")
    append("}")
}

sourcesJson = releaseInfoJson
// Работает! Создаем JSON вручную
```

## Альтернативные решения

### Вариант 1: Создать @Serializable data class

```kotlin
@Serializable
data class ReleaseInfo(
    val success: Boolean,
    val filesCount: Int,
    val durationMs: Long,
    val remoteDir: String,
    val sshConfig: String
)

val releaseInfo = ReleaseInfo(
    success = result.success,
    filesCount = result.uploadedFiles.size,
    durationMs = result.durationMs,
    remoteDir = "/root/release",
    sshConfig = "my_mon_bot"
)

sourcesJson = Json.encodeToString(releaseInfo)
```

### Вариант 2: Использовать JsonObject

```kotlin
val releaseInfo = buildJsonObject {
    put("success", result.success)
    put("filesCount", result.uploadedFiles.size)
    put("durationMs", result.durationMs)
    put("remoteDir", "/root/release")
    put("sshConfig", "my_mon_bot")
}

sourcesJson = releaseInfo.toString()
```

### Выбранное решение

Использовали **ручное создание JSON** потому что:
1. ✅ Простое и понятное
2. ✅ Не требует дополнительных классов
3. ✅ Нет зависимости от библиотек
4. ✅ Работает быстро
5. ✅ Легко читается и поддерживается

## Почему возникла проблема

Kotlinx.serialization - это compile-time библиотека, которая требует явного указания типов. Когда вы используете `Map<String, Any>`, компилятор не знает, как сериализовать `Any`, потому что это может быть:
- String
- Int
- Long
- Boolean
- List
- Map
- Custom class
- и т.д.

Для каждого типа нужен свой сериализатор, а `Any` - это слишком общий тип.

## Проверка

После исправления:

```bash
# Пересобираем
./gradlew build

# Запускаем чат
./chat.sh

# Пробуем релиз
👤 Вы: зарелизь приложение
🚀 [релиз выполняется успешно]
```

## Урок

При работе с kotlinx.serialization:
1. Используйте конкретные типы, не `Any`
2. Создавайте `@Serializable` data classes для сложных структур
3. Для простых JSON - создавайте вручную
4. Для динамических структур - используйте `JsonObject` / `JsonArray`

## Статус

✅ **Исправлено**  
✅ Компиляция успешна  
✅ Релиз работает  

---

**Дата:** 2026-01-17  
**Файл:** `src/main/kotlin/cli/ChatCommand.kt`  
**Строки:** 300-320
