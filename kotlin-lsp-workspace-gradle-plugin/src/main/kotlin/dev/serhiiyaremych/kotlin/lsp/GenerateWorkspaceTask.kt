package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.json.JSONObject
import org.json.JSONArray

abstract class GenerateWorkspaceTask : DefaultTask() {
    @get:InputFile
    abstract val dependencyMetadata: RegularFileProperty
    
    @get:OutputFile
    abstract val workspaceFile: RegularFileProperty
    
    @TaskAction
    fun generateWorkspace() {
        val metadataFile = dependencyMetadata.asFile.get()
        val metadataText = metadataFile.readText()
        val metadata = JSONObject(metadataText)
        
        // Extract JSON arrays and values directly
        val modulesArray = metadata.getJSONArray("modules")
        val librariesArray = metadata.getJSONArray("libraries")
        val androidSdk = metadata.optString("androidSdk", "")
        val compileSdk = metadata.optString("compileSdk", "")
        
        // Generate final workspace.json using JSONObject
        val workspaceJson = buildWorkspaceObject(modulesArray, librariesArray, androidSdk, compileSdk)
        workspaceFile.asFile.get().writeText(workspaceJson.toString(4))
        logger.lifecycle("Generated workspace.json: ${workspaceFile.asFile.get().absolutePath}")
    }
    
    private fun buildWorkspaceObject(
        modulesArray: JSONArray, 
        librariesArray: JSONArray, 
        androidSdk: String, 
        compileSdk: String
    ): JSONObject {
        return JSONObject().apply {
            put("modules", modulesArray)
            put("libraries", librariesArray)
            put("sdks", buildSdksArray(androidSdk, compileSdk))
            put("kotlinSettings", buildKotlinSettingsArray())
        }
    }
    
    private fun buildSdksArray(androidSdk: String, compileSdk: String): JSONArray {
        val sdksArray = JSONArray()
        if (androidSdk.isNotEmpty() && compileSdk != "0") {
            sdksArray.put(JSONObject().apply {
                put("name", "Android API $compileSdk")
                put("type", "Android")
                put("version", compileSdk)
                put("homePath", androidSdk)
                put("additionalData", "")
            })
        }
        return sdksArray
    }
    
    private fun buildKotlinSettingsArray(): JSONArray {
        val kotlinSettingsArray = JSONArray()
        kotlinSettingsArray.put(JSONObject().apply {
            put("name", "Kotlin")
            put("sourceRoots", JSONArray())
            put("configFileItems", JSONArray())
            put("module", "app.main")
            put("useProjectSettings", true)
            put("implementedModuleNames", JSONArray())
            put("dependsOnModuleNames", JSONArray())
            put("additionalVisibleModuleNames", JSONArray())
            put("productionOutputPath", JSONObject.NULL)
            put("testOutputPath", JSONObject.NULL)
            put("sourceSetNames", JSONArray())
            put("isTestModule", false)
            put("externalProjectId", "")
            put("isHmppEnabled", true)
            put("pureKotlinSourceFolders", JSONArray())
            put("kind", "default")
            put("compilerArguments", JSONObject.NULL)
            put("additionalArguments", JSONObject.NULL)
            put("scriptTemplates", JSONObject.NULL)
            put("scriptTemplatesClasspath", JSONObject.NULL)
            put("outputDirectoryForJsLibraryFiles", JSONObject.NULL)
            put("targetPlatform", JSONObject.NULL)
            put("externalSystemRunTasks", JSONArray())
            put("version", 5)
            put("flushNeeded", false)
        })
        return kotlinSettingsArray
    }
}