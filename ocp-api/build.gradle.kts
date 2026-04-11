// ocp-api module - Pure interfaces and contracts (no dependencies)

dependencies {
    // Paper API needed for org.bukkit.Material used in NodeRegistry interface
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    // Test dependencies
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    // Paper API also needed at test compile/runtime (Material enum values in property tests)
    testCompileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testRuntimeOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
}
