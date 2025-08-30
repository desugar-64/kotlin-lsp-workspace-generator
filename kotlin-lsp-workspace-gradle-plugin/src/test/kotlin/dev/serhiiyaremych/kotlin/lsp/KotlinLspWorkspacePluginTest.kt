package dev.serhiiyaremych.kotlin.lsp

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.assertj.core.api.Assertions.*

class KotlinLspWorkspacePluginTest {

    @Test
    fun `plugin can be applied successfully`(@TempDir tempDir: File) {
        // Simple test to verify plugin applies without errors
        val buildFile = File(tempDir, "build.gradle.kts")
        buildFile.writeText("""
            plugins {
                id("dev.serhiiyaremych.kotlin.lsp.workspace")
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(tempDir)
            .withArguments("tasks", "--group=kotlin lsp workspace")
            .withPluginClasspath()
            .build()

        assertThat(result.output).contains("generateKotlinLspWorkspace")
        assertThat(result.output).contains("cleanLspTemp")
    }

    @Test
    fun `workspace json structure matches expected format`() {
        // Test fixture file with known structure
        val resourceUrl = this::class.java.getResource("/fixtures/expected-workspace.json")
        assertThat(resourceUrl).isNotNull()
        val fixtureFile = File(resourceUrl.file)
        val gson = Gson()
        val workspace = gson.fromJson(fixtureFile.readText(), JsonObject::class.java)
        
        validateWorkspaceStructure(workspace)
    }

    @Test
    fun `workspace json contains expected specific values`() {
        // Test fixture file with known good values
        val resourceUrl = this::class.java.getResource("/fixtures/expected-workspace.json")
        assertThat(resourceUrl).isNotNull()
        val fixtureFile = File(resourceUrl.file)
        val gson = Gson()
        val workspace = gson.fromJson(fixtureFile.readText(), JsonObject::class.java)

        // Verify specific module values
        val modules = workspace.getAsJsonArray("modules")
        val mainModule = modules[0].asJsonObject
        assertThat(mainModule.get("name").asString).isEqualTo("app.main")

        val dependencies = mainModule.getAsJsonArray("dependencies")
        val dependencyNames = dependencies.map { it.asJsonObject.get("name")?.asString }
        
        // Verify critical dependencies exist with exact names
        assertThat(dependencyNames).contains("Gradle: android:android:36")
        assertThat(dependencyNames).anyMatch { it?.contains("activity-compose:1.10.1") == true }
        assertThat(dependencyNames).anyMatch { it?.contains("androidx.compose.runtime:runtime-android") == true }

        // Verify specific library entries
        val libraries = workspace.getAsJsonArray("libraries")
        val libraryNames = libraries.map { it.asJsonObject.get("name").asString }
        
        // Check for key libraries with exact naming format
        assertThat(libraryNames).contains("Gradle: android:android:36")
        assertThat(libraryNames).anyMatch { it.contains("activity-compose:1.10.1") }
        assertThat(libraryNames).anyMatch { it.contains("androidx.compose.runtime:runtime-android") }

        // Verify library structure for a specific library
        val androidLibrary = libraries.find { 
            it.asJsonObject.get("name").asString == "Gradle: android:android:36"
        }?.asJsonObject
        
        assertThat(androidLibrary).isNotNull
        assertThat(androidLibrary!!.get("type").asString).isEqualTo("java-imported")
        
        val androidRoots = androidLibrary.getAsJsonArray("roots")
        assertThat(androidRoots).isNotEmpty
        
        // Verify root structure contains both jar and sources
        val rootPaths = androidRoots.map { it.asJsonObject.get("path").asString }
        assertThat(rootPaths).anyMatch { it.contains("android-36.jar") }
        assertThat(rootPaths).anyMatch { it.contains("android-36-sources.jar") }

        // Verify source root type
        val sourcesRoot = androidRoots.find { 
            it.asJsonObject.has("type") && it.asJsonObject.get("type").asString == "SOURCES"
        }
        assertThat(sourcesRoot).isNotNull

        // Verify kotlin settings structure and values
        val kotlinSettings = workspace.getAsJsonArray("kotlinSettings")
        assertThat(kotlinSettings).isNotNull
        if (kotlinSettings.size() > 0) {
            val firstKotlinSetting = kotlinSettings[0].asJsonObject
            assertThat(firstKotlinSetting.get("name").asString).isEqualTo("Kotlin")
            assertThat(firstKotlinSetting.get("module").asString).isEqualTo("app.main")
            assertThat(firstKotlinSetting.get("useProjectSettings").asBoolean).isTrue()
            assertThat(firstKotlinSetting.get("kind").asString).isEqualTo("default")
        }
    }

    private fun validateWorkspaceStructure(workspace: JsonObject) {
        // Validate required top-level keys
        assertThat(workspace.has("modules")).isTrue()
        assertThat(workspace.has("libraries")).isTrue() 
        assertThat(workspace.has("sdks")).isTrue()
        assertThat(workspace.has("kotlinSettings")).isTrue()

        // Validate modules structure
        val modules = workspace.getAsJsonArray("modules")
        assertThat(modules.size()).isGreaterThan(0)
        
        val firstModule = modules[0].asJsonObject
        assertThat(firstModule.has("name")).isTrue()
        assertThat(firstModule.has("dependencies")).isTrue()
        assertThat(firstModule.has("contentRoots")).isTrue()

        // Validate libraries structure
        val libraries = workspace.getAsJsonArray("libraries")
        assertThat(libraries.size()).isGreaterThan(0)
        
        val firstLibrary = libraries[0].asJsonObject
        assertThat(firstLibrary.has("name")).isTrue()
        assertThat(firstLibrary.has("type")).isTrue()
        assertThat(firstLibrary.has("roots")).isTrue()

        // Validate kotlin settings - can be empty array
        val kotlinSettings = workspace.getAsJsonArray("kotlinSettings")
        assertThat(kotlinSettings).isNotNull
        // Don't assert size - it can be empty
    }
}