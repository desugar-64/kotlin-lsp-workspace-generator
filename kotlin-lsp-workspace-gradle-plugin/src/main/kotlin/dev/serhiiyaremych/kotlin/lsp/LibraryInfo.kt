package dev.serhiiyaremych.kotlin.lsp

data class LibraryInfo(
    val name: String,
    val jarPath: java.io.File,
    val sourcesPath: java.io.File?,
    val scope: String = "compile",
    val groupId: String = "unknown",
    val artifactId: String = "unknown", 
    val version: String = "unknown"
)
