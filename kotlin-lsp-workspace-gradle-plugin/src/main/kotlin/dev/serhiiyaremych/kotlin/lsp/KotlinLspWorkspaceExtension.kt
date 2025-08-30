package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

abstract class KotlinLspWorkspaceExtension {
    abstract val workspaceFile: RegularFileProperty
    abstract val includeTestDependencies: Property<Boolean>
    abstract val copyJarsToTemp: Property<Boolean>
    abstract val autoRegenerate: Property<Boolean>
    
    abstract val kotlinVersion: Property<String>
    
    abstract val generateVSCodeConfig: Property<Boolean>
    abstract val vsCodeDirectory: DirectoryProperty
    abstract val generateLaunchJson: Property<Boolean>
    abstract val generateTasksJson: Property<Boolean>
    abstract val mainActivityName: Property<String>
    
    init {
        includeTestDependencies.convention(false)
        copyJarsToTemp.convention(true)
        autoRegenerate.convention(true)
        
        generateVSCodeConfig.convention(true)
        generateLaunchJson.convention(false)  // Disabled by default - launch configs don't work reliably
        generateTasksJson.convention(true)
        mainActivityName.convention("MainActivity")
    }
}
