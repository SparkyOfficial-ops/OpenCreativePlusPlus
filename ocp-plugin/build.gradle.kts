// ocp-plugin module - Paper/Bukkit integration

plugins {
    id("com.github.johnrengelman.shadow")
}

dependencies {
    api(project(":ocp-api"))
    implementation(project(":ocp-core"))
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
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
}
