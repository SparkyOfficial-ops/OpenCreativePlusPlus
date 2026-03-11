// ocp-plugin module - Paper/Bukkit integration

dependencies {
    api(project(":ocp-api"))
    implementation(project(":ocp-core"))
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("com.grinderwolf:slimeworldmanager-api:2.9.0")
}
