plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared:ui"))
    implementation(compose.desktop.currentOs)
}
compose.desktop { application { mainClass = "com.myjournal.desktop.MainKt" } }
