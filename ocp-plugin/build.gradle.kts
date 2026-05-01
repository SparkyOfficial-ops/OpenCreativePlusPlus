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
        archiveClassifier.set("")
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
        jvmArgs("-Xmx1536m")
    }
}
