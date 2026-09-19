import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.3.10"
}
val mobileConfig = Properties().apply {
    val f = rootProject.file("mobile.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun mobileValue(name: String): String {
    val value = mobileConfig.getProperty(name, "")
    require(!value.contains('\n') && !value.contains('\r') && !value.contains('"') && !value.contains('\\'))
    return "\"" + value + "\""
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android {
    namespace = "com.example.finance_planning"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.finance_planning"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FIREBASE_APP_ID", mobileValue("firebase.appId"))
        buildConfigField("String", "FIREBASE_API_KEY", mobileValue("firebase.apiKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", mobileValue("firebase.projectId"))
        buildConfigField("String", "FIREBASE_SENDER_ID", mobileValue("firebase.senderId"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", mobileValue("google.webClientId"))
    }
    buildTypes { release { optimization { enable = false } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
