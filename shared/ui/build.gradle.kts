plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.myjournal.ui"
        compileSdk = 37
        minSdk = 23
        withHostTest {}
    }
    jvm()
    if (providers.gradleProperty("enableIos").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            binaries.framework { baseName = "JournalUI"; isStatic = true }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
    }
}
