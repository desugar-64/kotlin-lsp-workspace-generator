// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.io.File
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("dev.serhiiyaremych.kotlin.lsp.workspace")
}



kotlinLspWorkspace {
    // Optional configuration - most settings auto-detected
    workspaceFile.set(file("workspace.json"))  // default
    includeTestDependencies.set(false)         // default  
    copyJarsToTemp.set(true)                   // default
    autoRegenerate.set(true)                   // auto-regenerate on dependency changes
    
    // VS Code configuration
    generateVSCodeConfig.set(true)             // generate VS Code config files  
    vsCodeDirectory.set(file(".vscode"))       // default
    generateLaunchJson.set(false)              // disabled - launch configs don't work in VS Code
    generateTasksJson.set(true)                // generate tasks.json  
    applicationId.set("com.example.vscodetest")  // Your app's package name
    launcherActivity.set("MainActivity")         // Your main activity class
}

// Original monolithic task for comparison
tasks.register("generateKotlinLspWorkspaceOld") {
    group = "ide"
    description = "Generates a Kotlin LSP-compatible workspace.json for this Android workspace."

    doLast {
        // Common properties and paths
        val lspTempDir = File(rootProject.layout.buildDirectory.asFile.get(), ".lsp-temp")
        val workspaceJsonFile = rootProject.file("workspace-old.json")
        val gradleHome = System.getProperty("user.home") + "/.gradle"
        val kotlinVersion = "2.0.21" // Extract from project if needed
        val composeStubsVersion = "1.0.0"
        
        // Create temp directory
        lspTempDir.mkdirs()
        
        // Helper functions
        fun toModuleName(projectPath: String): String = projectPath.trimStart(':').replace(':', '.')
        fun relPath(file: File): String = file.relativeTo(rootProject.projectDir).invariantSeparatorsPath
        fun wsPath(file: File): String = "<WORKSPACE>/" + relPath(file).trimStart('/')
        fun configByNameOrNull(p: org.gradle.api.Project, name: String) = p.configurations.findByName(name)

        fun findCompileClasspaths(p: org.gradle.api.Project): List<org.gradle.api.artifacts.Configuration> {
            val exact = configByNameOrNull(p, "compileClasspath")
            if (exact != null) return listOf(exact)
            // Fallback for Android: pick non-test compile classpaths
            return p.configurations.filter { cfg ->
                cfg.isCanBeResolved &&
                    cfg.name.endsWith("CompileClasspath", ignoreCase = true) &&
                    !cfg.name.contains("test", ignoreCase = true) &&
                    !cfg.name.contains("androidTest", ignoreCase = true)
            }
        }

        data class LibraryInfo(
            val name: String,
            val jarPath: File,
            val sourcesPath: File? = null,
            val scope: String = "compile"
        )
        
        fun findSourcesJar(mainJar: File, id: org.gradle.api.artifacts.ModuleVersionIdentifier): File? {
            // Try to find sources jar in the same directory
            val parentDir = mainJar.parentFile
            
            // Look for sources jar with various naming patterns
            val possibleNames = listOf(
                "${id.name}-${id.version}-sources.jar",
                "${mainJar.nameWithoutExtension}-sources.jar",
                "${mainJar.nameWithoutExtension}-sources.aar"
            )
            
            for (name in possibleNames) {
                val sourcesFile = File(parentDir, name)
                if (sourcesFile.exists()) {
                    return sourcesFile
                }
            }
            
            // Also check files in the same directory that contain "sources"
            parentDir.listFiles()?.forEach { file ->
                if (file.name.contains("sources", ignoreCase = true) && 
                    (file.extension == "jar" || file.extension == "aar")) {
                    return file
                }
            }
            
            return null
        }
        
        fun processAarFile(aarFile: File): File? {
            try {
                // Use common temp directory
                
                // Create target JAR file name
                val jarName = aarFile.nameWithoutExtension + ".jar"
                val targetJar = File(lspTempDir, jarName)
                
                // Skip if already extracted and up-to-date
                if (targetJar.exists() && targetJar.lastModified() >= aarFile.lastModified()) {
                    return targetJar
                }
                
                // Extract classes.jar from AAR
                java.util.zip.ZipFile(aarFile).use { zip ->
                    val classesEntry = zip.getEntry("classes.jar")
                    if (classesEntry != null) {
                        zip.getInputStream(classesEntry).use { input ->
                            targetJar.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        return targetJar
                    }
                }
            } catch (e: Exception) {
                println("Warning: Could not process AAR ${aarFile.name}: ${e.message}")
            }
            return null
        }

        fun findSourcesForDependency(id: org.gradle.api.artifacts.ModuleVersionIdentifier, project: org.gradle.api.Project): File? {
            try {
                // Create a detached configuration to resolve sources
                val sourcesConfig = project.configurations.detachedConfiguration()
                val sourceDep = project.dependencies.create("${id.group}:${id.name}:${id.version}:sources@jar")
                sourcesConfig.dependencies.add(sourceDep)
                
                // Resolve and find the sources jar
                val resolved = sourcesConfig.resolve()
                return resolved.firstOrNull { it.name.endsWith("-sources.jar") }
            } catch (e: Exception) {
                // Fallback to old method
                return null
            }
        }

        fun collectLibrariesWithPaths(configs: List<org.gradle.api.artifacts.Configuration>): Map<String, LibraryInfo> {
            val libraries = mutableMapOf<String, LibraryInfo>()
            
            configs.forEach { config ->
                try {
                    config.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                        val id = artifact.moduleVersion.id
                        val libName = "Gradle: ${id.group}:${id.name}:${id.version}"
                        val originalFile = artifact.file
                        
                        // Process AAR files by extracting classes.jar, and copy all JARs to build directory for consistency
                        val jarFile = if (originalFile.extension == "aar") {
                            processAarFile(originalFile) ?: originalFile
                        } else {
                            // Copy regular JARs to temp directory for path consistency
                            val targetJarName = "${id.name}-${id.version}.jar"
                            val targetJar = File(lspTempDir, targetJarName)
                            
                            // Copy JAR if not already copied or if newer
                            if (!targetJar.exists() || targetJar.lastModified() < originalFile.lastModified()) {
                                originalFile.copyTo(targetJar, overwrite = true)
                            }
                            targetJar
                        }
                        
                        // Try to find sources jar using Gradle resolution
                        val originalSourcesFile = findSourcesForDependency(id, rootProject) ?: findSourcesJar(originalFile, id)
                        
                        // Copy sources JAR to temp directory for consistency with main JAR
                        val sourcesFile = if (originalSourcesFile != null) {
                            val sourcesTargetName = "${id.name}-${id.version}-sources.jar"
                            val sourcesTarget = File(lspTempDir, sourcesTargetName)
                            
                            // Copy sources JAR if not already copied or if newer
                            if (!sourcesTarget.exists() || sourcesTarget.lastModified() < originalSourcesFile.lastModified()) {
                                originalSourcesFile.copyTo(sourcesTarget, overwrite = true)
                            }
                            sourcesTarget
                        } else null
                        
                        libraries[libName] = LibraryInfo(libName, jarFile, sourcesFile, "compile")
                    }
                } catch (e: Exception) {
                    // Skip configurations that can't be resolved
                }
            }
            
            return libraries
        }

        fun declaredCompileOnlyGA(p: org.gradle.api.Project): Set<String> =
            p.configurations.findByName("compileOnly")?.allDependencies?.mapNotNull { dep ->
                val g = dep.group
                val n = dep.name
                if (!g.isNullOrBlank() && n.isNotBlank()) "$g:$n" else null
            }?.toSet() ?: emptySet()

        // Get Android SDK path if available
        fun findAndroidSdk(): String? {
            val localProperties = rootProject.file("local.properties")
            if (localProperties.exists()) {
                val props = java.util.Properties()
                props.load(localProperties.inputStream())
                return props.getProperty("sdk.dir")
            }
            
            // Fallback to ANDROID_HOME environment variable
            return System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        }
        
        // Find Android SDK compile version from subprojects
        fun findAndroidCompileSdk(): Int? {
            return rootProject.subprojects.mapNotNull { subproject ->
                try {
                    // Try to get compileSdk from android extension
                    val android = subproject.extensions.findByName("android")
                    if (android != null) {
                        // Use reflection to get compileSdkVersion
                        val compileSdkMethod = android.javaClass.getMethod("getCompileSdkVersion")
                        compileSdkMethod.invoke(android) as? Int
                    } else null
                } catch (e: Exception) {
                    null
                }
            }.firstOrNull() ?: 36 // Default to 36 if not found
        }
        
        fun addAndroidSdkLibraries(androidSdkPath: String, compileSdk: Int, libraries: MutableMap<String, LibraryInfo>, tempDir: File) {
            val platformDir = File(androidSdkPath, "platforms/android-$compileSdk")
            if (platformDir.exists()) {
                val androidJar = File(platformDir, "android.jar")
                
                // Look for actual Android SDK sources and create a proper sources JAR
                val sourcesRootDir = File(androidSdkPath, "sources/android-$compileSdk")
                val actualSources = if (sourcesRootDir.exists()) {
                    // Create sources JAR in temp directory for consistency
                    val sourcesJar = File(tempDir, "android-$compileSdk-sources.jar")
                    
                    // Only create JAR if it doesn't exist or sources are newer
                    if (!sourcesJar.exists() || sourcesRootDir.lastModified() > sourcesJar.lastModified()) {
                        println("Creating Android SDK sources JAR: ${sourcesJar.absolutePath}")
                        println("From sources directory: ${sourcesRootDir.absolutePath}")
                        
                        val processBuilder = ProcessBuilder(
                            "jar", "cf", sourcesJar.absolutePath, 
                            "-C", sourcesRootDir.absolutePath, "."
                        )
                        val process = processBuilder.start()
                        val exitCode = process.waitFor()
                        
                        if (exitCode == 0) {
                            println("Successfully created Android SDK sources JAR")
                            sourcesJar
                        } else {
                            println("Failed to create Android SDK sources JAR")
                            null
                        }
                    } else {
                        println("Using existing Android SDK sources JAR: ${sourcesJar.absolutePath}")
                        sourcesJar
                    }
                } else {
                    // Fallback to stub sources if actual sources not available
                    val stubSourcesJar = File(platformDir, "android-stubs-src.jar")
                    if (stubSourcesJar.exists()) {
                        println("Using Android SDK stub sources: ${stubSourcesJar.absolutePath}")
                        stubSourcesJar
                    } else {
                        println("No Android SDK sources found")
                        null
                    }
                }
                
                if (androidJar.exists()) {
                    // Copy Android JAR to temp directory for consistency with other libraries
                    val copiedAndroidJar = File(tempDir, "android-$compileSdk.jar")
                    
                    if (!copiedAndroidJar.exists() || androidJar.lastModified() > copiedAndroidJar.lastModified()) {
                        println("Copying Android JAR to: ${copiedAndroidJar.absolutePath}")
                        androidJar.copyTo(copiedAndroidJar, overwrite = true)
                    }
                    
                    val libName = "Gradle: android:android:$compileSdk"
                    libraries[libName] = LibraryInfo(
                        name = libName,
                        jarPath = copiedAndroidJar,
                        sourcesPath = actualSources,
                        scope = "compile"  // Change to compile scope like androidx
                    )
                    println("Added Android platform library: ${copiedAndroidJar.absolutePath}")
                    if (actualSources != null) {
                        println("Added Android platform sources: ${actualSources.absolutePath}")
                    }
                }
            }
        }
        
        fun addComposeStubs(libraries: MutableMap<String, LibraryInfo>, tempDir: File, version: String) {
            val stubJar = File(tempDir, "compose-function-stubs.jar")
            
            // Check if stubs already exist and are up-to-date
            if (!stubJar.exists()) {
                // Generate ComposableFunction0 to ComposableFunction22 stub classes
                println("Generating ComposableFunction stubs JAR: ${stubJar.absolutePath}")
                
                val tempStubDir = File(tempDir, "compose-stubs-temp")
                tempStubDir.mkdirs()
                
                try {
                    // Create package directory structure
                    val packageDir = File(tempStubDir, "androidx/compose/runtime")
                    packageDir.mkdirs()
                    
                    // Generate stub classes for ComposableFunction0 to ComposableFunction22
                    for (i in 0..22) {
                        val stubContent = if (i == 0) {
                            """package androidx.compose.runtime

@FunctionalInterface
interface ComposableFunction0<R> {
    fun invoke(): R
}
"""
                        } else {
                            val typeParams = (1..i).joinToString(", ") { "P$it" }
                            val params = (1..i).joinToString(", ") { "p$it: P$it" }
                            """package androidx.compose.runtime

@FunctionalInterface  
interface ComposableFunction$i<$typeParams, R> {
                            fun invoke($params): R
}
"""
                        }
                        
                        File(packageDir, "ComposableFunction$i.kt").writeText(stubContent)
                    }
                    
                    // Create JAR from stub classes
                    val processBuilder = ProcessBuilder(
                        "jar", "cf", stubJar.absolutePath, 
                        "-C", tempStubDir.absolutePath, "."
                    )
                    val process = processBuilder.start()
                    process.waitFor()
                    
                    // Clean up temp directory
                    tempStubDir.deleteRecursively()
                    
                } catch (e: Exception) {
                    println("Warning: Failed to generate ComposableFunction stubs: ${e.message}")
                    tempStubDir.deleteRecursively()
                }
            }
            
            if (stubJar.exists()) {
                val libName = "Gradle: androidx.compose.runtime:compose-function-stubs:$version"
                libraries[libName] = LibraryInfo(
                    name = libName,
                    jarPath = stubJar,
                    sourcesPath = null,
                    scope = "provided"
                )
                println("Added ComposableFunction stubs library: ${stubJar.absolutePath}")
            }
        }

        fun addComposeCompilerLibrary(kotlinVersion: String, libraries: MutableMap<String, LibraryInfo>) {
            val compilerPluginPath = "$gradleHome/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/$kotlinVersion"
            val compilerPluginDir = File(compilerPluginPath)
            
            if (compilerPluginDir.exists()) {
                compilerPluginDir.listFiles()?.forEach { versionDir ->
                    if (versionDir.isDirectory) {
                        val jarFiles = versionDir.listFiles { file -> 
                            file.name.endsWith(".jar") && file.name.contains("kotlin-compose-compiler-plugin-embeddable")
                        }
                        jarFiles?.firstOrNull()?.let { compilerJar ->
                            val libName = "Gradle: org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$kotlinVersion"
                            libraries[libName] = LibraryInfo(
                                name = libName,
                                jarPath = compilerJar,
                                sourcesPath = null,
                                scope = "provided"
                            )
                            println("Added Compose compiler plugin library: ${compilerJar.absolutePath}")
                        }
                    }
                }
            }
        }

        val androidSdkPath = findAndroidSdk()
        val compileSdk = findAndroidCompileSdk()
        
        val modulesJson = mutableListOf<String>()
        val allLibraries = mutableMapOf<String, LibraryInfo>()
        
        // Add Android SDK libraries to allLibraries if SDK path is available
        if (androidSdkPath != null && compileSdk != null) {
            addAndroidSdkLibraries(androidSdkPath, compileSdk, allLibraries, lspTempDir)
        }
        
        // Add Compose compiler plugin library for ComposableFunction types
        addComposeCompilerLibrary(kotlinVersion, allLibraries)
        
        // Add ComposableFunction stub library for LSP compatibility - generate in temp directory
        addComposeStubs(allLibraries, lspTempDir, composeStubsVersion)
        
        // Iterate subprojects (e.g., :app)
        rootProject.subprojects.sortedBy { it.path }.forEach { sp ->
            val moduleBaseName = toModuleName(sp.path)
            val projectDir = sp.projectDir

            val compileConfs = findCompileClasspaths(sp)
            val libraries = collectLibrariesWithPaths(compileConfs)
            allLibraries.putAll(libraries)
            
            val providedGA = declaredCompileOnlyGA(sp)
            
            // Build dependencies array
            val deps = mutableListOf<String>()
            
            // Add Android SDK library dependency if available
            if (androidSdkPath != null && compileSdk != null) {
                val androidSdkLibName = "Gradle: android:android:$compileSdk"
                deps += """
                {
                    "type": "library",
                    "name": "$androidSdkLibName",
                    "scope": "provided"
                }""".trimIndent()
            }
            
            // Add Compose compiler plugin dependency for ComposableFunction types
            val composeCompilerLibName = "Gradle: org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:2.0.21"
            deps += """
                {
                    "type": "library",
                    "name": "$composeCompilerLibName",
                    "scope": "provided"
                }""".trimIndent()
            
            // Add ComposableFunction stub dependency for LSP compatibility  
            val stubLibName = "Gradle: androidx.compose.runtime:compose-function-stubs:1.0.0"
            deps += """
                {
                    "type": "library",
                    "name": "$stubLibName",
                    "scope": "provided"
                }""".trimIndent()
            
            // Add library dependencies (sorted)
            libraries.keys.sorted().forEach { libName ->
                val ga = libName.removePrefix("Gradle: ").substringBeforeLast(":")
                val scope = if (providedGA.contains(ga)) "provided" else "compile"
                
                deps += """
                {
                    "type": "library",
                    "name": "$libName",
                    "scope": "$scope"
                }""".trimIndent()
            }
            
            // Add moduleSource and inheritedSdk
            deps += """
                {
                    "type": "moduleSource"
                }""".trimIndent()
            
            deps += """
                {
                    "type": "inheritedSdk"
                }""".trimIndent()
            
            // Build source roots (always include all three, even if they don't exist)
            val sourceRoots = listOf(
                """
                        {
                            "path": "${wsPath(File(projectDir, "src/main/java"))}",
                            "type": "java-source"
                        }""".trimIndent(),
                """
                        {
                            "path": "${wsPath(File(projectDir, "src/main/kotlin"))}",
                            "type": "java-source"
                        }""".trimIndent(),
                """
                        {
                            "path": "${wsPath(File(projectDir, "src/main/resources"))}",
                            "type": "java-resource"
                        }""".trimIndent()
            )
            
            // Build module JSON
            val moduleJson = """
        {
            "name": "${moduleBaseName}.main",
            "dependencies": [
${deps.joinToString(",\n").prependIndent("                ")}
            ],
            "contentRoots": [
                {
                    "path": "${wsPath(projectDir)}",
                    "excludedPatterns": [],
                    "excludedUrls": [],
                    "sourceRoots": [
${sourceRoots.joinToString(",\n")}
                    ]
                }
            ],
            "facets": []
        }""".trimIndent()
            
            modulesJson += moduleJson
        }
        
        // Build libraries JSON with actual JAR paths
        val librariesJson = allLibraries.values.sortedBy { it.name }.map { lib ->
            val roots = mutableListOf<String>()
            
            // Add main JAR with absolute path
            roots += """
                {
                    "path": "${lib.jarPath.absolutePath}"
                }""".trimIndent()
            
            // Add sources JAR if available with absolute path
            lib.sourcesPath?.let { sourcesFile ->
                roots += """
                {
                    "path": "${sourcesFile.absolutePath}",
                    "type": "SOURCES"
                }""".trimIndent()
            }
            
            """
        {
            "name": "${lib.name}",
            "type": "java-imported",
            "roots": [
${roots.joinToString(",\n").prependIndent("                ")}
            ],
            "properties": {
                "attributes": {
                    "groupId": "${lib.name.removePrefix("Gradle: ").split(":")[0]}",
                    "artifactId": "${lib.name.removePrefix("Gradle: ").split(":")[1]}",
                    "version": "${lib.name.removePrefix("Gradle: ").split(":")[2]}",
                    "baseVersion": "${lib.name.removePrefix("Gradle: ").split(":")[2]}"
                }
            }
        }""".trimIndent()
        }
        
        val sdksJson = if (androidSdkPath != null && compileSdk != null) {
            """
        {
            "name": "Android API $compileSdk",
            "type": "Android", 
            "version": "$compileSdk",
            "homePath": "$androidSdkPath",
            "additionalData": ""
        }"""
        } else {
            ""
        }
        
        // Build kotlinSettings for Android modules
        val kotlinSettingsJson = rootProject.subprojects.map { sp ->
            val moduleBaseName = toModuleName(sp.path)
            """
        {
            "name": "Kotlin",
            "sourceRoots": [],
            "configFileItems": [],
            "module": "${moduleBaseName}.main",
            "useProjectSettings": true,
            "implementedModuleNames": [],
            "dependsOnModuleNames": [],
            "additionalVisibleModuleNames": [],
            "productionOutputPath": null,
            "testOutputPath": null,
            "sourceSetNames": [],
            "isTestModule": false,
            "externalProjectId": "",
            "isHmppEnabled": true,
            "pureKotlinSourceFolders": [],
            "kind": "default",
            "compilerArguments": null,
            "additionalArguments": null,
            "scriptTemplates": null,
            "scriptTemplatesClasspath": null,
            "outputDirectoryForJsLibraryFiles": null,
            "targetPlatform": null,
            "externalSystemRunTasks": [],
            "version": 5,
            "flushNeeded": false
        }"""
        }
        
        // Build complete workspace JSON
        val workspaceJson = """
{
    "modules": [
${modulesJson.joinToString(",\n")}
    ],
    "libraries": [
${librariesJson.joinToString(",\n")}
    ],
    "sdks": [${if (sdksJson.isNotEmpty()) "\n$sdksJson\n    " else ""}],
    "kotlinSettings": [
${kotlinSettingsJson.joinToString(",\n")}
    ]
}
""".trimIndent()
        
        workspaceJsonFile.writeText(workspaceJson)
        println("workspace.json written to: ${workspaceJsonFile.absolutePath}")
    }
}
