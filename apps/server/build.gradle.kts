plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }
application { mainClass = "com.myjournal.server.MainKt" }

dependencies {
    implementation(project(":shared:data"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.ktor.netty)
    implementation(libs.ktor.status.pages)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.server.logging)
    testImplementation(libs.ktor.test.host)
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    val testDatabase = providers.environmentVariable("LIFEAPP_TEST_DB_URL")
    outputs.upToDateWhen { false }
    doFirst { check(testDatabase.isPresent) { "Set LIFEAPP_TEST_DB_URL to an isolated disposable PostgreSQL database." } }
}
