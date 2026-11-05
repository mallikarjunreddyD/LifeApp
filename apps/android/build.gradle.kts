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
        versionCode = 6
        versionName = "0.4.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Optional stable identity for data-preserving updates. Values come only from
    // the environment; keystores/passwords are never stored in this repository.
    val signingNames = listOf("LIFEAPP_SIGNING_STORE_FILE", "LIFEAPP_SIGNING_STORE_PASSWORD",
        "LIFEAPP_SIGNING_KEY_ALIAS", "LIFEAPP_SIGNING_KEY_PASSWORD")
    val signingValues = signingNames.associateWith { providers.environmentVariable(it).orNull }
    require(signingValues.values.all { it == null } || signingValues.values.all { !it.isNullOrEmpty() }) {
        "Set all four LIFEAPP_SIGNING_* variables to use an existing signing identity."
    }
    if (signingValues.values.all { it != null }) {
        val updateSigning = signingConfigs.create("localUpdate") {
            storeFile = file(signingValues.getValue("LIFEAPP_SIGNING_STORE_FILE")!!)
            storePassword = signingValues.getValue("LIFEAPP_SIGNING_STORE_PASSWORD")
            keyAlias = signingValues.getValue("LIFEAPP_SIGNING_KEY_ALIAS")
            keyPassword = signingValues.getValue("LIFEAPP_SIGNING_KEY_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = updateSigning
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
    implementation(project(":shared:domain"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.ink.brush)
    implementation(libs.ink.strokes)
    implementation(libs.ink.rendering)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.savedstate)
    androidTestImplementation(libs.sqlite.bundled)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.core)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
    implementation(libs.activity.compose)
}
