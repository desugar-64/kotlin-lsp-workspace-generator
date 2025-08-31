package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Configuration DSL for Kotlin LSP Workspace Generator plugin.
 *
 * Generates workspace.json for Kotlin LSP to understand Android project dependencies.
 * Converts AAR files to JARs with source attachments for proper IDE support in VS Code.
 *
 * Essential configuration:
 * - Set [applicationId] to your Android app's package name
 * - Set [launcherActivity] to your main activity class name
 * - Most other settings auto-detected or have sensible defaults
 *
 * Example usage:
 * ```
 * kotlinLspWorkspace {
 *     applicationId.set("com.yourapp.package")
 *     launcherActivity.set("MainActivity")
 * }
 * ```
 */
abstract class KotlinLspWorkspaceExtension {
    
    // Core LSP Configuration
    
    /**
     * Output location for workspace.json file.
     * @default "workspace.json"
     */
    abstract val workspaceFile: RegularFileProperty
    
    /**
     * Include test dependencies in workspace. Adds testImplementation dependencies.
     * @default false
     */
    abstract val includeTestDependencies: Property<Boolean>
    
    /**
     * Copy JARs to build/.lsp-temp for path consistency. Ensures absolute paths work correctly.
     * @default true
     */
    abstract val copyJarsToTemp: Property<Boolean>
    
    /**
     * Auto-regenerate workspace on dependency changes. Hooks into Gradle configuration tasks.
     * @default true
     */
    abstract val autoRegenerate: Property<Boolean>
    
    /**
     * Kotlin version for LSP configuration. Auto-detected from project if not set.
     */
    abstract val kotlinVersion: Property<String>
    
    // VS Code Integration
    
    /**
     * Generate VS Code configuration files (.vscode/). Enables Android build tasks in VS Code.
     * @default true
     */
    abstract val generateVSCodeConfig: Property<Boolean>
    
    /**
     * Directory for VS Code configuration files.
     * @default ".vscode"
     */
    abstract val vsCodeDirectory: DirectoryProperty
    
    /**
     * Generate launch.json for debugging. Currently unreliable in VS Code.
     * @default false
     */
    abstract val generateLaunchJson: Property<Boolean>
    
    /**
     * Generate tasks.json for build commands. Adds Android build/install/launch tasks.
     * @default true
     */
    abstract val generateTasksJson: Property<Boolean>
    
    // Android App Configuration
    
    /**
     * Android app package name for launch intents. Used in adb commands like "com.yourapp.package".
     * Required for VS Code tasks.
     */
    abstract val applicationId: Property<String>
    
    /**
     * Main activity class name for app launching. Combined with [applicationId] for launch intents.
     * @default "MainActivity"
     */
    abstract val launcherActivity: Property<String>
    
    init {
        includeTestDependencies.convention(false)
        copyJarsToTemp.convention(true)
        autoRegenerate.convention(true)
        
        generateVSCodeConfig.convention(true)
        generateLaunchJson.convention(false)  // Disabled by default - launch configs don't work reliably
        generateTasksJson.convention(true)
        applicationId.convention("com.example.app")  // User should set this manually
        launcherActivity.convention("MainActivity")   // User should set this manually
    }
}
