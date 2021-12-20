import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Kotlin application project to get you started.
 * For more details take a look at the 'Building Java & JVM projects' chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.1.1/userguide/building_java_projects.html
 */

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.6.10"

    // Apply the application plugin to add support for building a CLI application in Java.
    application

    id("com.github.johnrengelman.shadow") version "7.1.1"
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    // Use the Kotlin JDK 8 standard library.
    implementation(group = "org.jetbrains.kotlin", name = "kotlin-stdlib-jdk8")

    // This dependency is used by the application.
    implementation(group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.13.0")
    implementation(group = "com.fasterxml.jackson.core", name = "jackson-core", version = "2.13.1")
    implementation(group = "com.fasterxml.jackson.module", name = "jackson-module-kotlin", version = "2.13.0")
    implementation(group = "com.fasterxml.jackson.module", name = "jackson-module-afterburner", version = "2.13.1")

    implementation(group = "com.github.kittinunf.fuel", name = "fuel", version = "2.3.1")
    implementation(group = "com.github.kittinunf.fuel", name = "fuel-jackson", version = "2.3.1")

    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.2.9")
    implementation(group = "org.fusesource.jansi", name = "jansi", version = "2.4.0")

    implementation(group = "org.apache.maven.shared", name = "maven-shared-utils", version = "3.3.4")
    implementation(group = "net.java.dev.jna", name = "jna", version = "5.10.0")
    implementation(group = "net.java.dev.jna", name = "jna-platform", version = "5.10.0")

    implementation(group = "commons-codec", name = "commons-codec", version = "1.15")

    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.36.0.3")

    implementation(group = "com.github.pengrad", name = "java-telegram-bot-api", version = "5.5.0")

    // Use the Kotlin test library.
    testImplementation(group = "org.jetbrains.kotlin", name = "kotlin-test")

    // Use the Kotlin JUnit integration.
    testImplementation(group = "org.jetbrains.kotlin", name = "kotlin-test-junit")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

tasks.withType<KotlinCompile> {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
    kotlinOptions.jvmTarget = JavaVersion.VERSION_1_8.toString()
}

application {
    // Define the main class for the application.
    mainClass.set("be.zvz.covid.remaining.vaccine.AppKt")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("shadow")
    mergeServiceFiles()
    manifest {
        attributes(mapOf("Main-Class" to application.mainClass.get()))
    }
}

tasks {
    build {
        dependsOn(shadowJar)
    }
}
