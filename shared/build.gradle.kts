plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.dx.wristsound.shared"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(libs.coroutines.android)
    api(libs.coroutines.tasks)
    api(libs.datastore)
    api(libs.androidx.core)
    api(libs.wearable)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
