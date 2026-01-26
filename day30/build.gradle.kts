plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP клиент для Ollama API
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    
    // SQLite + Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    
    // JSON сериализация
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    // CLI парсинг
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    
    // Логирование
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.example.MainKt")
}

// Поддержка интерактивного stdin для команды chat
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}