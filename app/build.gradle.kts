import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val cloud = Properties().apply {
    rootProject.file("cloud.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
providers.environmentVariable("LEXORA_BUILD_DIR").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "org.lexora.study"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.lexora.study"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0"
        for (key in listOf("FIREBASE_APP_ID","FIREBASE_API_KEY","FIREBASE_PROJECT_ID","GOOGLE_WEB_CLIENT_ID")) {
            val v = cloud.getProperty(key, "")
            require(v.all { it.isLetterOrDigit() || it in "_.:-" })
            buildConfigField("String", key, "\"" + v + "\"")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.github.open-spaced-repetition:fsrs:1.0.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
