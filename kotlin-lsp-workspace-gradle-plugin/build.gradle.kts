import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import kotlin.collections.find

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.serhiiyaremych.kotlin.lsp"
version = "1.0.0-SNAPSHOT"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.json:json:20250517")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testImplementation("com.google.code.gson:gson:2.13.1")
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("kotlinLspWorkspace") {
            id = "dev.serhiiyaremych.kotlin.lsp.workspace"
            implementationClass = "dev.serhiiyaremych.kotlin.lsp.KotlinLspWorkspacePlugin"
            displayName = "Kotlin LSP Workspace Generator"
            description = "Generates workspace.json for Kotlin Language Server Protocol support"
        }
    }
}