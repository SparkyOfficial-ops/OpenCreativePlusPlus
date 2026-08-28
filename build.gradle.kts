plugins {
    kotlin("jvm") version "1.9.25" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

allprojects {
    group = "com.opencreativeplus"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://repo.dmulloy2.net/repository/public/")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        val implementation by configurations
        implementation(kotlin("stdlib"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-Xmx1g", "-Xms256m")
    }
}
