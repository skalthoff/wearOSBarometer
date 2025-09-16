plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vibecode.gasketcheck.testflow"
    compileSdk = 34

    defaultConfig { minSdk = 30 }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(project(":core:sensors"))
    implementation(project(":core:signal"))
    implementation(project(":data:store"))

    implementation(platform("androidx.wear.compose:compose-bom:1.3.0"))
    implementation("androidx.wear.compose:compose-material")
    implementation("androidx.wear.compose:compose-foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
