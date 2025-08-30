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

dependencies {
    implementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("com.google.code.gson:gson:2.10.1")
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