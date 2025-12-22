# Semantic Search Application

Консольное приложение на Kotlin для семантического поиска по локальным документам с использованием Ollama и SQLite.

## Требования

- Java 21+
- Gradle
- Ollama с установленной моделью `nomic-embed-text:latest`

### Установка Ollama модели

```bash
ollama pull nomic-embed-text:latest
```

## Сборка

```bash
./gradlew build
```

## Использование

### Индексация директории

```bash
./gradlew run --args="index /path/to/documents --chunk-size 512 --overlap 50"
```

Параметры:
- `--chunk-size`: Размер чанка в символах (по умолчанию: 512)
- `--overlap`: Размер overlap между чанками (по умолчанию: 50)
- `--ollama-url`: URL Ollama сервера (по умолчанию: http://localhost:11434)
- `--db-path`: Путь к файлу SQLite (по умолчанию: ./index.db)

### Поиск

```bash
./gradlew run --args="search \"ваш запрос\" --top-k 10"
```

Параметры:
- `--top-k`: Количество результатов (по умолчанию: 10)
- `--ollama-url`: URL Ollama сервера
- `--db-path`: Путь к файлу SQLite

### Статистика индекса

```bash
./gradlew run --args="stats"
```

### Очистка индекса

```bash
./gradlew run --args="clear"
```

### Удаление документа из индекса

```bash
./gradlew run --args="remove /path/to/file.md"
```

## Примеры

```bash
# Индексация текущей директории
./gradlew run --args="index . --chunk-size 512 --overlap 50"

# Поиск
./gradlew run --args="search \"как работает корутины\" --top-k 5"

# Статистика
./gradlew run --args="stats"
```

## Поддерживаемые форматы файлов

- Markdown: `.md`
- Текст: `.txt`
- Код: `.kt`, `.java`, `.js`, `.ts`, `.jsx`, `.tsx`, `.py`, `.rs`, `.go`, `.cpp`, `.c`, `.h`, `.hpp`, `.cs`, `.rb`, `.php`, `.swift`, `.scala`, `.clj`
- Конфигурация: `.yaml`, `.yml`, `.json`, `.xml`, `.html`, `.css`, `.sh`

Максимальный размер файла: 10MB

## Архитектура

- **Инкрементальная индексация**: Файлы переиндексируются только при изменении (проверка по хешу SHA-256)
- **Чанкинг с overlap**: Текст разбивается на чанки с перекрытием для сохранения контекста
- **Семантический поиск**: Использует косинусное сходство векторов эмбеддингов
- **SQLite**: Локальное хранение индекса с поддержкой каскадного удаления

## Структура базы данных

- `documents`: Метаданные файлов
- `chunks`: Текстовые чанки с позициями
- `embeddings`: Векторы эмбеддингов

