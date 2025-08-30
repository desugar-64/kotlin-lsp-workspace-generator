package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class CleanLspTempTask : DefaultTask() {
    @get:OutputDirectory
    abstract val tempDir: DirectoryProperty
    
    @TaskAction
    fun clean() {
        val dir = tempDir.asFile.get()
        if (dir.exists()) {
            dir.deleteRecursively()
            logger.lifecycle("Deleted LSP temp directory: ${dir.absolutePath}")
        }
        dir.mkdirs()
        logger.lifecycle("Created LSP temp directory: ${dir.absolutePath}")
    }
}
