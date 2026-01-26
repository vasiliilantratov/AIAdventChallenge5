package org.example.indexing

import java.io.File

class FileScanner(
    private val ignorePattern: IgnorePattern? = null,
    private val rootPath: String = ""
) {
    
    private val supportedExtensions = setOf(
        ".md", ".txt", ".kt", ".java", ".js", ".ts", ".jsx", ".tsx",
        ".py", ".rs", ".go", ".cpp", ".c", ".h", ".hpp", ".cs",
        ".rb", ".php", ".swift", ".scala", ".clj", ".sh", ".yaml", ".yml",
        ".json", ".xml", ".html", ".css",
        ".sql", ".toml", ".ini", ".conf"
    )

    /** Файлы без расширения или со специальными именами, которые нужно брать в индекс. */
    private val specialFileNames = setOf(
        ".editorconfig",
        ".eslintrc", ".eslintrc.json", ".eslintrc.yml", ".eslintrc.yaml",
        ".prettierrc", ".prettierrc.json", ".prettierrc.yml", ".prettierrc.yaml",
        "pyproject.toml", "ruff.toml",
        "openapi.yaml", "openapi.yml", "swagger.json",
        "prisma.schema", "schema.sql"
    ).map { it.lowercase() }.toSet()
    
    private val maxFileSize = 10 * 1024 * 1024 // 10MB
    
    fun scanDirectory(directoryPath: String): List<FileInfo> {
        val directory = File(directoryPath)
        if (!directory.exists() || !directory.isDirectory) {
            throw IllegalArgumentException("Directory does not exist: $directoryPath")
        }
        
        val effectiveRootPath = if (rootPath.isEmpty()) directoryPath else rootPath
        val files = mutableListOf<FileInfo>()
        scanDirectoryRecursive(directory, files, effectiveRootPath)
        return files
    }
    
    private fun scanDirectoryRecursive(directory: File, files: MutableList<FileInfo>, rootPath: String) {
        // Проверяем, нужно ли исключить эту директорию
        if (ignorePattern != null && ignorePattern.shouldIgnore(directory.absolutePath, rootPath)) {
            return
        }
        
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDirectoryRecursive(file, files, rootPath)
            } else if (file.isFile) {
                // Проверяем, нужно ли исключить этот файл
                if (ignorePattern != null && ignorePattern.shouldIgnore(file.absolutePath, rootPath)) {
                    return@forEach
                }
                
                val extension = getFileExtension(file.name)
                val nameLower = file.name.lowercase()
                val allowedByName = nameLower in specialFileNames
                if ((extension in supportedExtensions || allowedByName) && file.length() <= maxFileSize) {
                    files.add(
                        FileInfo(
                            path = file.absolutePath,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            extension = extension
                        )
                    )
                }
            }
        }
    }
    
    private fun getFileExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot >= 0) {
            fileName.substring(lastDot).lowercase()
        } else {
            ""
        }
    }
}

data class FileInfo(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val extension: String
)

