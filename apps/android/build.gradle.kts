plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.myjournal.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.myjournal.android"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:data"))
    implementation(libs.activity.compose)
}
