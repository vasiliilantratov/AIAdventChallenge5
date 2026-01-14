package org.example.mcp

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * MCP (Model Context Protocol) Service
 * Читает данные из JSON файла (имитация CRM системы)
 * и предоставляет контекст пользователя для RAG системы
 */
class McpService(
    private val crmDataPath: String = "data/crm_data.json"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private var crmData: CrmData? = null
    private var lastLoadTime: Long = 0
    private val cacheTimeout = 60_000L // 1 минута
    
    /**
     * Загружает данные из JSON файла
     */
    private fun loadData(): CrmData {
        val currentTime = System.currentTimeMillis()
        
        // Используем кэш если данные свежие
        if (crmData != null && (currentTime - lastLoadTime) < cacheTimeout) {
            return crmData!!
        }
        
        val path = Path.of(crmDataPath)
        if (!Files.exists(path)) {
            throw IllegalStateException("CRM data file not found: $crmDataPath")
        }
        
        val jsonContent = path.readText()
        crmData = json.decodeFromString(CrmData.serializer(), jsonContent)
        lastLoadTime = currentTime
        
        return crmData!!
    }
    
    /**
     * Получает пользователя по ID
     */
    fun getUserById(userId: String): User? {
        val data = loadData()
        return data.users.find { it.id == userId }
    }
    
    /**
     * Получает пользователя по email
     */
    fun getUserByEmail(email: String): User? {
        val data = loadData()
        return data.users.find { it.email.equals(email, ignoreCase = true) }
    }
    
    /**
     * Получает все тикеты пользователя
     */
    fun getUserTickets(userId: String): List<Ticket> {
        val data = loadData()
        return data.tickets.filter { it.userId == userId }
    }
    
    /**
     * Получает активные тикеты пользователя (open, in_progress)
     */
    fun getActiveTickets(userId: String): List<Ticket> {
        return getUserTickets(userId).filter { 
            it.status in listOf("open", "in_progress", "waiting_for_response")
        }
    }
    
    /**
     * Получает недавние решенные тикеты пользователя
     */
    fun getRecentResolvedTickets(userId: String, limit: Int = 3): List<Ticket> {
        return getUserTickets(userId)
            .filter { it.status == "resolved" }
            .sortedByDescending { it.resolvedAt ?: it.updatedAt }
            .take(limit)
    }
    
    /**
     * Получает тикет по ID
     */
    fun getTicketById(ticketId: String): Ticket? {
        val data = loadData()
        return data.tickets.find { it.id == ticketId }
    }
    
    /**
     * Получает полный контекст пользователя для RAG системы
     */
    fun getUserContext(userId: String): UserContext? {
        val user = getUserById(userId) ?: return null
        val activeTickets = getActiveTickets(userId)
        val recentTickets = getRecentResolvedTickets(userId)
        
        return UserContext(
            user = user,
            activeTickets = activeTickets,
            recentTickets = recentTickets
        )
    }
    
    /**
     * Ищет тикеты по категории
     */
    fun searchTicketsByCategory(category: String): List<Ticket> {
        val data = loadData()
        return data.tickets.filter { 
            it.category.equals(category, ignoreCase = true) 
        }
    }
    
    /**
     * Ищет тикеты по тегу
     */
    fun searchTicketsByTag(tag: String): List<Ticket> {
        val data = loadData()
        return data.tickets.filter { ticket ->
            ticket.tags.any { it.equals(tag, ignoreCase = true) }
        }
    }
    
    /**
     * Ищет похожие тикеты по ключевым словам в названии или описании
     */
    fun searchSimilarTickets(query: String): List<Ticket> {
        val data = loadData()
        val keywords = query.lowercase().split(" ").filter { it.length > 2 }
        
        return data.tickets.filter { ticket ->
            val text = "${ticket.title} ${ticket.description}".lowercase()
            keywords.any { keyword -> text.contains(keyword) }
        }.sortedByDescending { ticket ->
            // Считаем сколько ключевых слов найдено
            val text = "${ticket.title} ${ticket.description}".lowercase()
            keywords.count { keyword -> text.contains(keyword) }
        }
    }
    
    /**
     * Получает статистику по тикетам пользователя
     */
    fun getUserTicketStats(userId: String): Map<String, Any> {
        val tickets = getUserTickets(userId)
        
        return mapOf(
            "total" to tickets.size,
            "open" to tickets.count { it.status == "open" },
            "in_progress" to tickets.count { it.status == "in_progress" },
            "resolved" to tickets.count { it.status == "resolved" },
            "by_priority" to mapOf(
                "critical" to tickets.count { it.priority == "critical" },
                "high" to tickets.count { it.priority == "high" },
                "medium" to tickets.count { it.priority == "medium" },
                "low" to tickets.count { it.priority == "low" }
            ),
            "by_category" to tickets.groupingBy { it.category }.eachCount()
        )
    }
    
    /**
     * Перезагружает данные из файла (сбрасывает кэш)
     */
    fun reload() {
        crmData = null
        lastLoadTime = 0
    }
}
