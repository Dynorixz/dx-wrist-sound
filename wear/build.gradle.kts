plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }
android {
    namespace = "dev.dx.wristsound.wear"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.dx.wristsound"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "1.2.4"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release {
        isMinifyEnabled = true
        isShrinkResources = true
        signingConfig = signingConfigs.getByName("debug") // Local upgrades keep the existing certificate.
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    } }
}
dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.material3)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
