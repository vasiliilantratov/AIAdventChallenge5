# План реализации системы семантического поиска

## 1. Архитектура приложения

### 1.1. Основные компоненты
- **CLI Interface** - консольный интерфейс для команд индексации и поиска
- **Document Indexer** - модуль для сканирования и индексации файлов
- **Text Chunker** - разбиение текста на чанки с overlap
- **Embedding Service** - получение эмбеддингов через Ollama API
- **Database Layer** - работа с SQLite (Exposed ORM)
- **Search Engine** - семантический поиск по индексу

### 1.2. Структура пакетов
```
org.example/
├── Main.kt                    # Точка входа, CLI парсинг
├── cli/
│   └── CommandParser.kt      # Парсинг аргументов командной строки
├── indexing/
│   ├── DocumentIndexer.kt    # Основной класс индексации
│   ├── FileScanner.kt        # Сканирование файловой системы
│   └── ChunkProcessor.kt     # Обработка чанков
├── chunking/
│   ├── TextChunker.kt        # Разбиение текста на чанки
│   └── ChunkStrategy.kt      # Стратегии чанкинга
├── embedding/
│   ├── OllamaEmbeddingService.kt  # Клиент Ollama API
│   └── EmbeddingModel.kt    # Модель данных эмбеддинга
├── database/
│   ├── Database.kt           # Инициализация БД
│   ├── Tables.kt             # Определение таблиц (Exposed)
│   └── Repository.kt         # Репозиторий для работы с данными
├── search/
│   ├── SemanticSearch.kt    # Основной класс поиска
│   └── SimilarityCalculator.kt  # Расчет косинусного расстояния
└── model/
    ├── Document.kt           # Модель документа
    ├── Chunk.kt              # Модель чанка
    └── SearchResult.kt      # Результат поиска
```

## 2. Зависимости (build.gradle.kts)

### 2.1. Необходимые библиотеки
```kotlin
dependencies {
    // HTTP клиент для Ollama API
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    
    // SQLite + Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    
    // JSON сериализация
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    // CLI парсинг
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    
    // Логирование
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    
    // Векторные операции
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    testImplementation(kotlin("test"))
}
```

## 3. Структура базы данных (SQLite)

### 3.1. Таблица `documents`
Хранит метаинформацию о документах для инкрементальной индексации:
```sql
CREATE TABLE documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path TEXT UNIQUE NOT NULL,
    file_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    last_modified INTEGER NOT NULL,  -- Unix timestamp
    content_hash TEXT NOT NULL,      -- SHA-256 для определения изменений
    indexed_at INTEGER NOT NULL,      -- Unix timestamp
    file_type TEXT NOT NULL          -- расширение файла
);
```

### 3.2. Таблица `chunks`
Хранит чанки текста с метаданными:
```sql
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,    -- порядковый номер чанка в документе
    content TEXT NOT NULL,
    start_char INTEGER NOT NULL,      -- начальная позиция в документе
    end_char INTEGER NOT NULL,        -- конечная позиция в документе
    token_count INTEGER,              -- количество токенов (опционально)
    created_at INTEGER NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    UNIQUE(document_id, chunk_index)
);
```

### 3.3. Таблица `embeddings`
Хранит векторы эмбеддингов:
```sql
CREATE TABLE embeddings (
    chunk_id INTEGER PRIMARY KEY,
    embedding BLOB NOT NULL,         -- сериализованный массив Float
    model_name TEXT NOT NULL,         -- "nomic-embed-text:latest"
    dimension INTEGER NOT NULL,       -- размерность вектора
    created_at INTEGER NOT NULL,
    FOREIGN KEY (chunk_id) REFERENCES chunks(id) ON DELETE CASCADE
);
```

### 3.4. Индексы для оптимизации
```sql
CREATE INDEX idx_documents_path ON documents(file_path);
CREATE INDEX idx_chunks_document ON chunks(document_id);
CREATE INDEX idx_embeddings_chunk ON embeddings(chunk_id);
```

## 4. Алгоритм индексации

### 4.1. Процесс индексации документа
1. **Сканирование файлов**
   - Рекурсивный обход указанной директории
   - Фильтрация по расширениям: `.md`, `.txt`, `.kt`, `.java`, `.js`, `.ts`, `.py`, `.rs`, `.go`, `.cpp`, `.c`, `.h`
   - Проверка размера файла (пропуск слишком больших, например >10MB)

2. **Проверка необходимости индексации**
   - Для каждого файла вычисляется SHA-256 хеш содержимого
   - Проверяется в БД: существует ли документ с таким путем
   - Если существует:
     - Сравнивается `content_hash` и `last_modified`
     - Если изменился - удаляются старые чанки и эмбеддинги, переиндексируется
   - Если не существует - индексируется новый документ

3. **Чтение и парсинг файла**
   - Чтение содержимого с учетом кодировки (UTF-8)
   - Обработка специальных символов и форматирования

4. **Разбиение на чанки**
   - Использование стратегии с overlap
   - Параметры:
     - `chunk_size`: 512 токенов (или символов)
     - `overlap_size`: 50 токенов (или символов)
   - Сохранение позиций чанков в исходном документе

5. **Получение эмбеддингов**
   - Для каждого чанка:
     - Отправка запроса в Ollama API: `POST /api/embeddings`
     - Модель: `nomic-embed-text:latest`
     - Сохранение вектора в БД

6. **Сохранение в БД**
   - Транзакция:
     - Вставка/обновление записи в `documents`
     - Вставка чанков в `chunks`
     - Вставка эмбеддингов в `embeddings`

### 4.2. Оптимизации
- Батчинг запросов к Ollama (если API поддерживает)
- Параллельная обработка нескольких документов (корутины)
- Прогресс-бар для больших индексаций

## 5. Алгоритм поиска

### 5.1. Процесс семантического поиска
1. **Получение эмбеддинга запроса**
   - Отправка запроса в Ollama API для получения эмбеддинга
   - Использование той же модели: `nomic-embed-text:latest`

2. **Поиск похожих векторов**
   - Загрузка всех эмбеддингов из БД (или использование векторного индекса, если доступен)
   - Расчет косинусного сходства для каждого чанка:
     ```
     cosine_similarity = dot(A, B) / (||A|| * ||B||)
     ```
   - Сортировка по убыванию сходства

3. **Топ-K результатов**
   - Выбор топ-K результатов (по умолчанию K=10)
   - Загрузка метаданных: содержимое чанка, информация о документе

4. **Форматирование результатов**
   - Вывод: релевантность, путь к файлу, содержимое чанка, контекст

## 6. CLI интерфейс

### 6.1. Команды
```bash
# Индексация директории
./gradlew run --args="index --path /path/to/documents --chunk-size 512 --overlap 50"

# Поиск
./gradlew run --args="search --query 'как работает корутины' --top-k 10"

# Статистика индекса
./gradlew run --args="stats"

# Очистка индекса
./gradlew run --args="clear"

# Удаление конкретного документа
./gradlew run --args="remove --path /path/to/file.md"
```

### 6.2. Параметры
- `--ollama-url`: URL Ollama сервера (по умолчанию: `http://localhost:11434`)
- `--db-path`: Путь к файлу SQLite (по умолчанию: `./index.db`)
- `--chunk-size`: Размер чанка в токенах/символах
- `--overlap`: Размер overlap между чанками
- `--top-k`: Количество результатов поиска

## 7. Реализация компонентов

### 7.1. TextChunker
- Стратегии:
  - По символам (простая)
  - По токенам (используя ktoken, если доступен)
  - По предложениям/абзацам (более интеллектуальная)
- Реализация overlap через скользящее окно

### 7.2. OllamaEmbeddingService
- HTTP клиент на Ktor
- Методы:
  - `getEmbedding(text: String): FloatArray`
  - `getEmbeddingsBatch(texts: List<String>): List<FloatArray>`
- Обработка ошибок и retry логика

### 7.3. Database Repository
- Методы:
  - `saveDocument(doc: Document): Long`
  - `findDocumentByPath(path: String): Document?`
  - `saveChunks(chunks: List<Chunk>): List<Long>`
  - `saveEmbeddings(embeddings: List<Embedding>)`
  - `findAllEmbeddings(): List<Pair<Long, FloatArray>>`
  - `deleteDocument(documentId: Long)`
- Использование транзакций

### 7.4. SimilarityCalculator
- Функция `cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float`
- Оптимизация через нормализацию векторов

## 8. Обработка ошибок

### 8.1. Сценарии
- Файл недоступен для чтения
- Ollama сервер недоступен
- Ошибка парсинга файла
- Ошибка записи в БД
- Недостаточно памяти для больших файлов

### 8.2. Стратегии
- Логирование всех ошибок
- Продолжение индексации при ошибке одного файла
- Retry для сетевых запросов
- Валидация входных данных

## 9. Тестирование

### 9.1. Unit тесты
- TextChunker (разные стратегии)
- SimilarityCalculator
- Database Repository

### 9.2. Integration тесты
- Полный цикл индексации и поиска
- Инкрементальная индексация
- Mock Ollama API

## 10. Оптимизации и улучшения (будущие)

### 10.1. Производительность
- Векторный индекс (например, HNSW через библиотеку)
- Кэширование эмбеддингов запросов
- Параллельная обработка чанков

### 10.2. Функциональность
- Фильтрация по типам файлов при поиске
- Подсветка найденных фрагментов
- Веб-интерфейс
- Поддержка других моделей эмбеддингов

## 11. Порядок реализации

1. **Этап 1: Базовая инфраструктура**
   - Настройка зависимостей
   - Создание структуры пакетов
   - Настройка БД (Exposed)

2. **Этап 2: Модели данных**
   - Определение таблиц
   - Модели Document, Chunk, Embedding
   - Repository с базовыми методами

3. **Этап 3: Чанкинг**
   - Реализация TextChunker
   - Тестирование на разных типах файлов

4. **Этап 4: Ollama интеграция**
   - HTTP клиент
   - Получение эмбеддингов
   - Обработка ошибок

5. **Этап 5: Индексация**
   - FileScanner
   - DocumentIndexer
   - Инкрементальная логика

6. **Этап 6: Поиск**
   - SimilarityCalculator
   - SemanticSearch
   - Форматирование результатов

7. **Этап 7: CLI**
   - Парсинг аргументов (Clikt)
   - Команды index, search, stats, clear

8. **Этап 8: Тестирование и отладка**
   - Unit тесты
   - Integration тесты
   - Исправление багов

