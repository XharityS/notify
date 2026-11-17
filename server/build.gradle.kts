plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "ru.echosystems.notify"
version = "1.0.0"
application {
    mainClass = "ru.echosystems.notify.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation("io.ktor:ktor-server-websockets:${libs.versions.ktor.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}