package dev.serhiiyaremych.kotlin.lsp

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

abstract class ProcessDependenciesTask : DefaultTask() {
    @get:Input
    abstract val includeTestDeps: Property<Boolean>
    
    @get:Input
    abstract val copyJarsToTemp: Property<Boolean>
    
    @get:Input
    abstract val kotlinVersion: Property<String>
    
    @get:InputDirectory
    abstract val tempDir: DirectoryProperty
    
    @get:OutputFile
    abstract val dependencyMetadata: RegularFileProperty
    
    @TaskAction
    fun processDependencies() {
        val buildDir = project.layout.buildDirectory.asFile.get()
        val tempDir = File(buildDir, ".lsp-temp")

        // Helper functions for paths and names
        fun toModuleName(projectPath: String) = if (projectPath == ":") project.rootProject.name else projectPath.removePrefix(":")
        fun relPath(file: File) = file.relativeTo(project.rootProject.projectDir).path
        fun wsPath(file: File) = "<WORKSPACE>/" + relPath(file)

        fun Project.configByNameOrNull(name: String) = try {
            configurations.findByName(name)?.takeIf { it.isCanBeResolved }
        } catch (e: Exception) {
            logger.warn("Could not resolve configuration '$name': ${e.message}")
            null
        }

        // 1. First pass - prepare lookup of "compile" classpaths for all Android submodules
        val compileJars = mutableMapOf<String, Set<File>>()

        fun findCompileClasspaths(proj: Project): List<org.gradle.api.artifacts.Configuration> {
            val exact = proj.configByNameOrNull("compileClasspath")
            if (exact != null) return listOf(exact)
            // Fallback for Android: pick non-test compile classpaths
            return proj.configurations.filter { cfg ->
                cfg.isCanBeResolved &&
                    cfg.name.endsWith("CompileClasspath", ignoreCase = true) &&
                    !cfg.name.contains("test", ignoreCase = true) &&
                    !cfg.name.contains("androidTest", ignoreCase = true)
            }
        }

        // Find sources jar for a given jar file
        fun findSourcesJar(jarFile: File): File? {
            // Case 1: If the jar is in Gradle cache, look for -sources.jar variant
            val jarName = jarFile.nameWithoutExtension
            val sourcesName = "$jarName-sources.jar"
            val sourcesFile = File(jarFile.parent, sourcesName)
            if (sourcesFile.exists()) {
                return sourcesFile
            }

            // Case 2: Maven local repository pattern
            val jarPath = jarFile.absolutePath
            if (jarPath.contains(".m2/repository")) {
                val sourcesPath = jarPath.replace(".jar", "-sources.jar")
                val mavenSourcesFile = File(sourcesPath)
                if (mavenSourcesFile.exists()) {
                    return mavenSourcesFile
                }
            }

            // Case 3: Gradle cache pattern - check if it's a versioned jar
            if (jarPath.contains("/.gradle/caches/modules-2/files-2.1/")) {
                val sourcesPath = jarPath.replace(".jar", "-sources.jar")
                val gradleSourcesFile = File(sourcesPath)
                if (gradleSourcesFile.exists()) {
                    return gradleSourcesFile
                }
            }

            return null
        }

        fun processAarFile(aarFile: File, tempDir: File, targetJarName: String): File? {
            try {
                // Create target JAR file with proper name
                val targetJar = File(tempDir, targetJarName)
                
                // Skip if already processed and up-to-date
                if (targetJar.exists() && targetJar.lastModified() >= aarFile.lastModified()) {
                    return targetJar
                }
                
                val aarName = aarFile.nameWithoutExtension
                val extractedDir = File(tempDir, "temp-aar-$aarName")
                
                try {
                    extractedDir.mkdirs()
                    
                    // Extract AAR file (it's a ZIP)
                    project.copy {
                        from(project.zipTree(aarFile))
                        into(extractedDir)
                    }
                    
                    // Look for classes.jar inside the AAR
                    val classesJar = File(extractedDir, "classes.jar")
                    if (classesJar.exists()) {
                        // Copy classes.jar to properly named target JAR
                        classesJar.copyTo(targetJar, overwrite = true)
                        logger.lifecycle("Processed AAR: ${aarFile.name} -> ${targetJar.name}")
                        return targetJar
                    } else {
                        logger.warn("No classes.jar found in AAR: ${aarFile.name}")
                        return null
                    }
                } finally {
                    // Clean up temp extraction directory
                    extractedDir.deleteRecursively()
                }
            } catch (e: Exception) {
                logger.warn("Failed to process AAR ${aarFile.name}: ${e.message}")
                return null
            }
        }

        fun extractArtifactInfo(jarFileName: String): Triple<String, String, String> {
            // Try to extract groupId, artifactId, version from jar file name
            // This is best effort - some may not follow standard naming
            val nameWithoutExt = jarFileName.removeSuffix(".jar")
            
            // Look for version pattern at the end
            val versionRegex = Regex("-(\\d+\\.\\d+\\.\\d+[^-]*)$")
            val versionMatch = versionRegex.find(nameWithoutExt)
            
            return if (versionMatch != null) {
                val version = versionMatch.groupValues[1]
                val artifactId = nameWithoutExt.removeSuffix("-$version")
                Triple("unknown", artifactId, version)
            } else {
                Triple("unknown", nameWithoutExt, "unknown")
            }
        }

        fun findSourcesForDependency(groupId: String, artifactId: String, version: String): File? {
            try {
                // Create a detached configuration to resolve sources
                val sourcesConfig = project.configurations.detachedConfiguration()
                val sourceDep = project.dependencies.create("${groupId}:${artifactId}:${version}:sources@jar")
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
                        val targetJarName = "${id.name}-${id.version}.jar"
                        val jarFile = if (originalFile.extension == "aar") {
                            processAarFile(originalFile, tempDir, targetJarName) ?: originalFile
                        } else {
                            // Copy regular JARs to temp directory for path consistency
                            val targetJar = File(tempDir, targetJarName)
                            
                            // Copy JAR if not already copied or if newer
                            if (!targetJar.exists() || targetJar.lastModified() < originalFile.lastModified()) {
                                originalFile.copyTo(targetJar, overwrite = true)
                            }
                            targetJar
                        }
                        
                        // Try to find sources jar using correct dependency ID
                        val originalSourcesFile = findSourcesForDependency(id.group ?: "unknown", id.name, id.version ?: "unknown") ?: findSourcesJar(originalFile)
                        
                        // Copy sources JAR to temp directory for consistency with main JAR
                        val sourcesFile = if (originalSourcesFile != null) {
                            val sourcesTargetName = "${id.name}-${id.version}-sources.jar"
                            val sourcesTarget = File(tempDir, sourcesTargetName)
                            
                            // Copy sources JAR if not already copied or if newer
                            if (!sourcesTarget.exists() || sourcesTarget.lastModified() < originalSourcesFile.lastModified()) {
                                originalSourcesFile.copyTo(sourcesTarget, overwrite = true)
                            }
                            sourcesTarget
                        } else null
                        
                        libraries[libName] = LibraryInfo(
                            name = libName, 
                            jarPath = jarFile, 
                            sourcesPath = sourcesFile, 
                            scope = "compile",
                            groupId = id.group ?: "unknown",
                            artifactId = id.name,
                            version = id.version
                        )
                    }
                } catch (e: Exception) {
                    // Skip configurations that can't be resolved
                    logger.warn("Could not resolve configuration '${config.name}': ${e.message}")
                }
            }
            
            return libraries
        }

        fun declaredCompileOnlyGA(): Set<String> {
            return project.configurations.flatMap { config ->
                config.allDependencies.mapNotNull { dep ->
                    if (config.name.contains("compileOnly", ignoreCase = true)) {
                        "${dep.group}:${dep.name}"
                    } else null
                }
            }.toSet()
        }

        // Find Android SDK
        fun findAndroidSdk(): String? {
            // Try local.properties first
            val localProps = File(project.rootProject.projectDir, "local.properties")
            if (localProps.exists()) {
                localProps.readLines().forEach { line ->
                    if (line.startsWith("sdk.dir=")) {
                        return line.substringAfter("sdk.dir=")
                    }
                }
            }

            // Try ANDROID_HOME
            System.getenv("ANDROID_HOME")?.let { return it }

            return null
        }

        fun findAndroidCompileSdk(): Int? {
            // Try to get from Android Gradle Plugin
            project.subprojects.forEach { subproject ->
                try {
                    subproject.extensions.findByName("android")?.let { android ->
                        val compileSdkMethod = android.javaClass.getMethod("getCompileSdk")
                        val compileSdk = compileSdkMethod.invoke(android)
                        if (compileSdk is Int) {
                            return compileSdk
                        }
                    }
                } catch (e: Exception) {
                    // Ignore and continue
                }
            }

            return null
        }

        fun addAndroidSdkLibraries(allLibraries: MutableMap<String, LibraryInfo>, androidSdkPath: String?, compileSdk: Int?) {
            if (androidSdkPath == null || compileSdk == null) return

            val androidJar = File("$androidSdkPath/platforms/android-$compileSdk/android.jar")
            if (androidJar.exists()) {
                // Copy Android JAR to temp directory if copyJarsToTemp is enabled
                val finalAndroidJar = if (copyJarsToTemp.get()) {
                    val copiedJar = File(tempDir, "android-$compileSdk.jar")
                    androidJar.copyTo(copiedJar, overwrite = true)
                    logger.lifecycle("Copying Android JAR to: ${copiedJar.absolutePath}")
                    copiedJar
                } else {
                    androidJar
                }

                // Create Android SDK sources JAR if sources directory exists
                val sourcesDir = File("$androidSdkPath/sources/android-$compileSdk")
                val sourcesJar = if (sourcesDir.exists()) {
                    val sourcesJarFile = File(tempDir, "android-$compileSdk-sources.jar")
                    if (!sourcesJarFile.exists()) {
                        logger.lifecycle("Creating Android SDK sources JAR: ${sourcesJarFile.absolutePath}")
                        logger.lifecycle("From sources directory: ${sourcesDir.absolutePath}")
                        
                        // Create a sources JAR from the Android sources
                        project.ant.invokeMethod("jar", mapOf(
                            "destfile" to sourcesJarFile.absolutePath,
                            "basedir" to sourcesDir.absolutePath
                        ))
                        logger.lifecycle("Successfully created Android SDK sources JAR")
                    }
                    sourcesJarFile
                } else null
                
                if (sourcesJar != null) {
                    logger.lifecycle("Added Android platform sources: ${sourcesJar.absolutePath}")
                }

                allLibraries["android"] = LibraryInfo(
                    name = "android",
                    jarPath = finalAndroidJar,
                    sourcesPath = sourcesJar,
                    scope = "provided",
                    groupId = "android",
                    artifactId = "android", 
                    version = compileSdk.toString()
                )
                
                logger.lifecycle("Added Android platform library: ${finalAndroidJar.absolutePath}")
            }
        }


        // Clean temp directory
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        // Collect all subprojects
        val androidSdkPath = findAndroidSdk()
        val compileSdk = findAndroidCompileSdk()
        val allLibraries = mutableMapOf<String, LibraryInfo>()
        val modulesJson = mutableListOf<JSONObject>()

        // Process each subproject
        project.subprojects.forEach { subproject ->
            val moduleName = toModuleName(subproject.path)
            
            // Collect all configurations for this project
            val compileConfigs = findCompileClasspaths(subproject)
            val libraries = collectLibrariesWithPaths(compileConfigs)
            allLibraries.putAll(libraries)

            // Get source directories
            val sourceDirs = mutableListOf<String>()
            
            // Try to get Android source sets
            try {
                subproject.extensions.findByName("android")?.let { android ->
                    val sourceSetsMethod = android.javaClass.getMethod("getSourceSets")
                    val sourceSets = sourceSetsMethod.invoke(android)
                    
                    // This is a simplified approach - in reality you'd need to handle the Android source sets properly
                    val mainSourceDir = File(subproject.projectDir, "src/main/java")
                    if (mainSourceDir.exists()) {
                        sourceDirs.add(wsPath(mainSourceDir))
                    }
                    val kotlinSourceDir = File(subproject.projectDir, "src/main/kotlin")
                    if (kotlinSourceDir.exists()) {
                        sourceDirs.add(wsPath(kotlinSourceDir))
                    }
                    val resourcesDir = File(subproject.projectDir, "src/main/resources") 
                    if (resourcesDir.exists()) {
                        sourceDirs.add(wsPath(resourcesDir))
                    }
                }
            } catch (e: Exception) {
                // Fallback to standard directories
                val standardDirs = listOf("src/main/java", "src/main/kotlin", "src/main/resources")
                standardDirs.forEach { dir ->
                    val sourceDir = File(subproject.projectDir, dir)
                    if (sourceDir.exists()) {
                        sourceDirs.add(wsPath(sourceDir))
                    }
                }
            }

            // Build module JSON - format for expected workspace.json
            val moduleObject = buildModuleObject(moduleName, subproject.name, libraries, sourceDirs, androidSdkPath, compileSdk?.toString())
            modulesJson.add(moduleObject)
        }

        // Add Android SDK libraries
        addAndroidSdkLibraries(allLibraries, androidSdkPath, compileSdk)

        // Note: Removed hardcoded Compose dependencies injection
        // Plugin now only processes actual Gradle-reported dependencies

        // Build libraries JSON with correct format (using absolute paths for Kotlin LSP)
        val librariesJson = allLibraries.values.sortedBy { it.name }.map { lib ->
            buildLibraryObject(lib)
        }

        // Save metadata
        val metadata = mapOf(
            "modules" to modulesJson,
            "libraries" to librariesJson,
            "androidSdk" to (androidSdkPath ?: ""),
            "compileSdk" to (compileSdk ?: 0)
        )
        
        // Write metadata JSON for GenerateWorkspaceTask
        val modulesList = metadata["modules"] as List<JSONObject>
        val librariesList = metadata["libraries"] as List<JSONObject>
        
        val metadataJson = buildMetadataObject(modulesList, librariesList, metadata)
        dependencyMetadata.asFile.get().writeText(metadataJson.toString(2))
    }
    
    private fun buildModuleObject(
        moduleName: String, 
        subprojectName: String, 
        libraries: Map<String, LibraryInfo>, 
        sourceDirs: List<String>, 
        androidSdkPath: String?, 
        compileSdk: String?
    ): JSONObject {
        return JSONObject().apply {
            put("name", "$moduleName.main")
            put("dependencies", buildDependenciesArray(libraries, androidSdkPath, compileSdk))
            put("contentRoots", JSONArray().apply {
                put(JSONObject().apply {
                    put("path", "<WORKSPACE>/$subprojectName")
                    put("excludedPatterns", JSONArray())
                    put("excludedUrls", JSONArray())
                    put("sourceRoots", buildSourceRootsArray(sourceDirs))
                })
            })
            put("facets", JSONArray())
        }
    }
    
    private fun buildDependenciesArray(
        libraries: Map<String, LibraryInfo>, 
        androidSdkPath: String?, 
        compileSdk: String?
    ): JSONArray {
        val dependenciesArray = JSONArray()
        
        // Add Android SDK dependency first (if available)
        if (androidSdkPath != null && compileSdk != null) {
            val androidSdkLibName = "Gradle: android:android:$compileSdk"
            dependenciesArray.put(createLibraryDependency(androidSdkLibName, "provided"))
        }
        
        // Add regular library dependencies (sorted)
        libraries.keys.sorted().forEach { libName ->
            val scope = libraries[libName]?.scope ?: "compile"
            dependenciesArray.put(createLibraryDependency(libName, scope))
        }
        
        // Add moduleSource and inheritedSdk entries
        dependenciesArray.put(JSONObject().apply { put("type", "moduleSource") })
        dependenciesArray.put(JSONObject().apply { put("type", "inheritedSdk") })
        
        return dependenciesArray
    }
    
    private fun createLibraryDependency(name: String, scope: String): JSONObject {
        return JSONObject().apply {
            put("type", "library")
            put("name", name)
            put("scope", scope)
        }
    }
    
    private fun buildSourceRootsArray(sourceDirs: List<String>): JSONArray {
        val sourceRootsArray = JSONArray()
        sourceDirs.forEach { path ->
            val type = when {
                path.contains("/resources") -> "java-resource"
                else -> "java-source"
            }
            sourceRootsArray.put(JSONObject().apply {
                put("path", path)
                put("type", type)
            })
        }
        return sourceRootsArray
    }
    
    private fun buildLibraryObject(lib: LibraryInfo): JSONObject {
        return JSONObject().apply {
            put("name", "Gradle: ${lib.groupId}:${lib.artifactId}:${lib.version}")
            put("type", "java-imported")
            put("roots", buildLibraryRootsArray(lib))
            put("properties", JSONObject().apply {
                put("attributes", JSONObject().apply {
                    put("groupId", lib.groupId)
                    put("artifactId", lib.artifactId)
                    put("version", lib.version)
                    put("baseVersion", lib.version)
                })
            })
        }
    }
    
    private fun buildLibraryRootsArray(lib: LibraryInfo): JSONArray {
        val rootsArray = JSONArray()
        
        // Add main jar root
        rootsArray.put(JSONObject().apply {
            put("path", lib.jarPath.absolutePath)
        })
        
        // Add sources root if available
        if (lib.sourcesPath != null) {
            rootsArray.put(JSONObject().apply {
                put("path", lib.sourcesPath.absolutePath)
                put("type", "SOURCES")
            })
        }
        
        return rootsArray
    }
    
    private fun buildMetadataObject(
        modulesList: List<JSONObject>, 
        librariesList: List<JSONObject>, 
        metadata: Map<String, Any>
    ): JSONObject {
        return JSONObject().apply {
            put("modules", JSONArray(modulesList))
            put("libraries", JSONArray(librariesList))
            put("androidSdk", metadata["androidSdk"] ?: "")
            put("compileSdk", metadata["compileSdk"] ?: 0)
        }
    }
    
}