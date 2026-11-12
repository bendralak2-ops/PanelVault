plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.panelvault.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.panelvault.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3-alpha"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("me.zhanghai.android.libarchive:library:1.1.6") }
