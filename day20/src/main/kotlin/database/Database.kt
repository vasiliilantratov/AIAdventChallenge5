package org.example.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseManager {
    fun initialize(dbPath: String) {
        val dbFile = File(dbPath)
        dbFile.parentFile?.mkdirs()
        
        Database.connect(
            "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )
        
        transaction {
            SchemaUtils.create(Documents, Chunks, Embeddings, Conversations, Messages)
        }
    }
    
    fun close() {
        // SQLite connection will be closed automatically
    }
}

