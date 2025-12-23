package org.example.indexing

import java.io.File

class FileScanner {
    
    private val supportedExtensions = setOf(
        ".md", ".txt", ".kt", ".java", ".js", ".ts", ".jsx", ".tsx",
        ".py", ".rs", ".go", ".cpp", ".c", ".h", ".hpp", ".cs",
        ".rb", ".php", ".swift", ".scala", ".clj", ".sh", ".yaml", ".yml",
        ".json", ".xml", ".html", ".css"
    )
    
    private val maxFileSize = 10 * 1024 * 1024 // 10MB
    
    fun scanDirectory(directoryPath: String): List<FileInfo> {
        val directory = File(directoryPath)
        if (!directory.exists() || !directory.isDirectory) {
            throw IllegalArgumentException("Directory does not exist: $directoryPath")
        }
        
        val files = mutableListOf<FileInfo>()
        scanDirectoryRecursive(directory, files)
        return files
    }
    
    private fun scanDirectoryRecursive(directory: File, files: MutableList<FileInfo>) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDirectoryRecursive(file, files)
            } else if (file.isFile) {
                val extension = getFileExtension(file.name)
                if (extension in supportedExtensions && file.length() <= maxFileSize) {
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

