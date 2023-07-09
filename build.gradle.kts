import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.22"
    kotlin("plugin.serialization") version "1.8.22"
    application
}

group = "ru.megageorgio.utils"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.deepl.api:deepl-java:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    implementation(fileTree("dir" to "lib", "includes" to listOf("*.jar")))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("ru.megageorgio.utils.swftranslator.MainKt")
}

tasks.jar {
    manifest.attributes["Main-Class"] = application.mainClass
    val dependencies = configurations
        .runtimeClasspath
        .get()
        .map(::zipTree)
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
