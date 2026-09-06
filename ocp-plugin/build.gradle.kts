// ocp-plugin module - Paper/Bukkit integration

plugins {
    id("com.gradleup.shadow")
}

dependencies {
    api(project(":ocp-api"))
    implementation(kotlin("stdlib"))
    implementation(project(":ocp-core"))
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.11.0")
    implementation("commons-io:commons-io:2.15.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testImplementation("net.dmulloy2:ProtocolLib:5.4.0")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:mongodb:1.19.3")
}

tasks {
    shadowJar {
        archiveClassifier.set("all")
        archiveBaseName.set("OpenCreativePlus")
        mergeServiceFiles()
        // Relocate coroutines to avoid conflicts with other plugins
        relocate("kotlinx.coroutines", "com.opencreativeplus.shaded.coroutines")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        enabled = true
        // Full suite (~930 tests) exhausts the default heap — property tests
        // accumulate mock-heavy objects. 3g stays well within 32g RAM.
        // Parallel forks disabled: runTest's strict uncaught-exception checking
        // causes flaky failures when tests share a JVM fork.
        maxHeapSize = "3g"
        maxParallelForks = 1
        // Workaround for Gradle test worker ClassNotFoundException on Cyrillic paths:
        // Prepend the non-Cyrillic mirror of the test/main class directories
        // so the worker's BuiltinClassLoader finds classes before the corrupted entries.
        classpath = files(
            "C:/ocp-test-build/classes/kotlin/test",
            "C:/ocp-test-build/classes/kotlin/main",
            "C:/ocp-test-build/libs/core/ocp-core-1.0.0-SNAPSHOT.jar",
            "C:/ocp-test-build/libs/api/ocp-api-1.0.0-SNAPSHOT.jar"
        ) + classpath
    }
}
