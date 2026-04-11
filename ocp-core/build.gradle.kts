// ocp-core module - Core execution engine

dependencies {
    api(project(":ocp-api"))
    // Paper API needed to implement ExecutionContext (which references Player?)
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.11.0")
    
    // Test dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:mongodb:1.19.3")
    testCompileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testRuntimeOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
}
