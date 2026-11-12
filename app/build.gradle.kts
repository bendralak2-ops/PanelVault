plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.panelvault.reader"; compileSdk = 35
 defaultConfig { applicationId = "com.panelvault.reader"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.3-alpha" }
}
dependencies { implementation("com.github.junrar:junrar:7.5.5") }