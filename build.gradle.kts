import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.atomicfu") version "0.27.0"
}

group = "com.promptgenerator"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.animation)
    implementation(compose.materialIconsExtended)

    // Kotlin standard libraries
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")

    // Compose adaptive layouts
//    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0-alpha01")

    // Ktor client for HTTP requests - unified version
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-okhttp:3.1.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-client-plugins:3.1.1")

    // AI/LLM service providers
    implementation("com.aallam.openai:openai-client:4.0.1")
    implementation("com.anthropic:anthropic-java:0.9.2")
    implementation("dev.shreyaspatil.generativeai:generativeai-google:0.9.0-1.0.1")
    implementation("com.google.genai:google-genai:0.3.0")

    // YAML configuration
    implementation("com.charleskorn.kaml:kaml:0.73.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Dependency Injection
    implementation("io.insert-koin:koin-core:4.0.3")
    implementation("io.insert-koin:koin-compose:4.0.3")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.promptgenerator.MainApplicationKt"
    }

    // Include all dependencies in the JAR
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

compose.desktop {
    application {
        mainClass = "com.promptgenerator.MainApplicationKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "promptgenerator"
            packageVersion = "1.0.0"
        }
    }
}
