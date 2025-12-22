package org.example.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.runBlocking
import org.example.chunking.ChunkConfig
import org.example.database.DatabaseManager
import org.example.database.Repository
import org.example.embedding.OllamaEmbeddingService
import org.example.indexing.DocumentIndexer
import org.example.search.SemanticSearch

class MainCommand : CliktCommand(name = "semantic-search", help = "Semantic search application") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    
    override fun run() {
        // Инициализация БД
        DatabaseManager.initialize(dbPath)
    }
}

class IndexCommand : CliktCommand(name = "index", help = "Index a directory") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    val path by argument("path", help = "Path to directory to index")
    val chunkSize by option("--chunk-size", help = "Chunk size in characters").convert { it.toInt() }.default(512)
    val overlap by option("--overlap", help = "Overlap size in characters").convert { it.toInt() }.default(50)
    
    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val chunkConfig = ChunkConfig(
            chunkSize = chunkSize,
            overlapSize = overlap
        )
        val indexer = DocumentIndexer(repository, embeddingService, chunkConfig)
        
        println("Starting indexing of directory: $path")
        var lastProgress = 0 to 0
        
        indexer.indexDirectory(path) { processed, total ->
            val (lastProcessed, lastTotal) = lastProgress
            if (processed != lastProcessed || total != lastTotal) {
                println("Progress: $processed/$total files indexed")
                lastProgress = processed to total
            }
        }
        
        println("Indexing completed!")
        embeddingService.close()
    }
}

class SearchCommand : CliktCommand(name = "search", help = "Search in indexed documents") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val ollamaUrl by option("--ollama-url", help = "Ollama server URL").default("http://localhost:11434")
    val query by argument("query", help = "Search query")
    val topK by option("--top-k", help = "Number of results to return").convert { it.toInt() }.default(10)
    
    override fun run() = runBlocking {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val embeddingService = OllamaEmbeddingService(ollamaUrl)
        val search = SemanticSearch(repository, embeddingService)
        
        println("Searching for: \"$query\"\n")
        
        val results = search.search(query, topK)
        
        if (results.isEmpty()) {
            println("No results found.")
        } else {
            results.forEachIndexed { index, result ->
                println("=".repeat(80))
                println("Result ${index + 1} (similarity: ${String.format("%.4f", result.similarity)})")
                println("File: ${result.document.filePath}")
                println("Type: ${result.document.fileType}")
                println("-".repeat(80))
                println(result.content)
                println()
            }
        }
        
        embeddingService.close()
    }
}

class StatsCommand : CliktCommand(name = "stats", help = "Show index statistics") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    
    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val stats = repository.getStats()
        
        println("Index Statistics:")
        println("  Documents: ${stats["documents"]}")
        println("  Chunks: ${stats["chunks"]}")
        println("  Embeddings: ${stats["embeddings"]}")
    }
}

class ClearCommand : CliktCommand(name = "clear", help = "Clear all indexed data") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    
    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        repository.clearAll()
        println("Index cleared successfully.")
    }
}

class RemoveCommand : CliktCommand(name = "remove", help = "Remove a document from index") {
    val dbPath by option("--db-path", help = "Path to SQLite database file").default("./index.db")
    val path by argument("path", help = "Path to file to remove")
    
    override fun run() {
        DatabaseManager.initialize(dbPath)
        val repository = Repository()
        val document = repository.findDocumentByPath(path)
        
        if (document == null) {
            println("Document not found in index: $path")
        } else {
            document.id?.let { repository.deleteDocument(it) }
            println("Document removed from index: $path")
        }
    }
}
