plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.myjournal.domain"
        compileSdk = 37
        minSdk = 23
        withHostTest {}
    }
    jvm()
    if (providers.gradleProperty("enableIos").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
    }
}
