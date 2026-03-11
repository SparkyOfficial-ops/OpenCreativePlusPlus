// ocp-core module - Core execution engine

dependencies {
    api(project(":ocp-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.11.0")
}
