plugins {
    kotlin("jvm") version "1.9.20" apply false
}

allprojects {
    group = "com.opencreativeplus"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        val implementation by configurations
        val testImplementation by configurations

        implementation(kotlin("stdlib"))
        testImplementation(kotlin("test"))
    }

    .withType<org.jetbrains.kotlin.gradle..KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    .withType<Test> {
        useJUnitPlatform()
    }
}
