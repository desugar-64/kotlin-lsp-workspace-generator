package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.*
import org.gradle.api.tasks.*

// Optional imports - only available if Android plugin is applied
import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension

class KotlinLspWorkspacePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kotlinLspWorkspace", KotlinLspWorkspaceExtension::class.java)
        
        // Set defaults
        extension.workspaceFile.convention(
            project.layout.projectDirectory.file("workspace.json")
        )
        extension.vsCodeDirectory.convention(
            project.layout.projectDirectory.dir(".vscode")
        )
        
        val cleanTask = project.tasks.register("cleanLspTemp", CleanLspTempTask::class.java) {
            group = "kotlin lsp workspace"
            description = "Clean LSP temp directory"
            tempDir.set(project.layout.buildDirectory.dir(".lsp-temp"))
        }
        
        val processTask = project.tasks.register("processLspDependencies", ProcessDependenciesTask::class.java) {
            group = "kotlin lsp workspace"
            description = "Process dependencies for LSP workspace"
            dependsOn(cleanTask)
            
            includeTestDeps.set(extension.includeTestDependencies)
            copyJarsToTemp.set(extension.copyJarsToTemp)
            kotlinVersion.set(extension.kotlinVersion)
            tempDir.set(project.layout.buildDirectory.dir(".lsp-temp"))
            dependencyMetadata.set(project.layout.buildDirectory.file("lsp-metadata.json"))
        }
        
        val generateTask = project.tasks.register("generateKotlinLspWorkspace", GenerateWorkspaceTask::class.java) {
            group = "kotlin lsp workspace"
            description = "Generate Kotlin LSP workspace.json"
            dependsOn(processTask)
            
            dependencyMetadata.set(processTask.flatMap { it.dependencyMetadata })
            workspaceFile.set(extension.workspaceFile)
        }
        
        // VS Code configuration task
        val vsCodeTask = project.tasks.register("generateVSCodeConfig", GenerateVSCodeConfigTask::class.java) {
            group = "kotlin lsp workspace"
            description = "Generate VS Code configuration files"
            dependsOn(generateTask)
            
            vsCodeDirectory.set(extension.vsCodeDirectory)
            generateLaunchJson.set(extension.generateLaunchJson)
            generateTasksJson.set(extension.generateTasksJson)
            mainActivity.set(extension.launcherActivity)
            applicationId.set(extension.applicationId)
        }
        
        // Auto-regenerate when dependencies change (if enabled)
        project.afterEvaluate {
            // Auto-detect Kotlin version if not explicitly set by user
            if (!extension.kotlinVersion.isPresent) {
                val detectedVersion = detectKotlinVersion(project)
                if (detectedVersion != null) {
                    extension.kotlinVersion.set(detectedVersion)
                    project.logger.info("KotlinLspWorkspace: Auto-detected Kotlin version: $detectedVersion")
                } else {
                    project.logger.warn("KotlinLspWorkspace: Could not auto-detect Kotlin version. Please set kotlinVersion manually in kotlinLspWorkspace extension.")
                }
            }
            
            if (extension.autoRegenerate.get()) {
                // Hook into dependency resolution and configuration tasks
                val dependencyTasks = listOf(
                    "prepareKotlinBuildScriptModel",
                    "configureReleaseDependencies", 
                    "configureDebugDependencies",
                    "resolveConfigAttr"
                )
                
                dependencyTasks.forEach { taskName ->
                    project.tasks.findByName(taskName)?.finalizedBy(generateTask)
                }
                
                // Also hook into subprojects (app module)
                project.subprojects.forEach { subproject ->
                    subproject.afterEvaluate {
                        dependencyTasks.forEach { taskName ->
                            subproject.tasks.findByName(taskName)?.finalizedBy(generateTask)
                        }
                        
                        // Auto-regenerate VS Code config too if enabled
                        if (extension.generateVSCodeConfig.get()) {
                            dependencyTasks.forEach { taskName ->
                                subproject.tasks.findByName(taskName)?.finalizedBy(vsCodeTask)
                            }
                        }
                    }
                }
                
                project.logger.info("KotlinLspWorkspace: Auto-regeneration enabled for dependency changes")
            }
        }
    }
    
    
    private fun detectKotlinVersion(project: Project): String? {
        // Try to read kotlin version from gradle/libs.versions.toml
        try {
            val versionCatalogFile = project.rootProject.file("gradle/libs.versions.toml")
            if (versionCatalogFile.exists()) {
                val content = versionCatalogFile.readText()
                val kotlinVersionLine = content.lines().find { it.trim().startsWith("kotlin") && it.contains("=") }
                if (kotlinVersionLine != null) {
                    val version = kotlinVersionLine.substringAfter("=").trim().removeSurrounding("\"")
                    if (version.isNotEmpty() && version != "kotlin") {
                        return version
                    }
                }
            }
        } catch (e: Exception) {
            project.logger.debug("KotlinLspWorkspace: Could not read libs.versions.toml: ${e.message}")
        }
        
        // Fallback: check subprojects for Kotlin dependencies
        project.allprojects.forEach { subproject ->
            subproject.configurations.forEach { config ->
                try {
                    config.allDependencies.forEach { dep ->
                        if (dep.group == "org.jetbrains.kotlin" && dep.name.startsWith("kotlin-stdlib")) {
                            val version = dep.version
                            if (version != null && version.isNotEmpty()) {
                                return version
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore configuration errors
                }
            }
        }
        
        return null
    }
}