package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenerateVSCodeConfigTask : DefaultTask() {
    
    @get:OutputDirectory
    abstract val vsCodeDirectory: DirectoryProperty
    
    @get:Input
    abstract val applicationId: Property<String>
    
    @get:Input
    abstract val mainActivity: Property<String>
    
    @get:Input
    abstract val generateLaunchJson: Property<Boolean>
    
    @get:Input 
    abstract val generateTasksJson: Property<Boolean>
    
    init {
        description = "Generate VS Code configuration files (.vscode/tasks.json)"
        group = "kotlin-lsp"
        
        generateLaunchJson.convention(false)  // Launch configs don't work reliably in VS Code
        generateTasksJson.convention(true)
        mainActivity.convention("MainActivity")
    }
    
    @TaskAction
    fun generate() {
        val vsCodeDir = vsCodeDirectory.get().asFile
        vsCodeDir.mkdirs()
        
        val appId = applicationId.get()
        val activity = mainActivity.get()
        
        if (generateTasksJson.get()) {
            generateTasksJsonFile(vsCodeDir, appId, activity)
        }
        
        if (generateLaunchJson.get()) {
            generateLaunchJsonFile(vsCodeDir, appId, activity)
        }
        
        logger.lifecycle("Generated VS Code configuration files in ${vsCodeDir.absolutePath}")
    }
    
    private fun generateTasksJsonFile(vsCodeDir: File, applicationId: String, mainActivity: String) {
        val template = getTemplate("tasks.json.template")
        val content = template
            .replace("{{APPLICATION_ID}}", applicationId)
            .replace("{{MAIN_ACTIVITY}}", "$applicationId.$mainActivity")
            
        val tasksFile = File(vsCodeDir, "tasks.json")
        tasksFile.writeText(content)
        logger.info("Generated tasks.json")
    }
    
    private fun generateLaunchJsonFile(vsCodeDir: File, applicationId: String, mainActivity: String) {
        val template = getTemplate("launch.json.template")
        val content = template
            .replace("{{APPLICATION_ID}}", applicationId)
            .replace("{{MAIN_ACTIVITY}}", "$applicationId.$mainActivity")
            
        val launchFile = File(vsCodeDir, "launch.json")
        launchFile.writeText(content)
        logger.info("Generated launch.json")
    }
    
    private fun getTemplate(templateName: String): String {
        val classLoader = GenerateVSCodeConfigTask::class.java.classLoader
        val inputStream = classLoader.getResourceAsStream(templateName)
            ?: throw IllegalStateException("Template $templateName not found in resources")
        
        return inputStream.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }
}