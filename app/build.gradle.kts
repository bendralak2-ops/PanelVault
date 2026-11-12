plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    useLibrary("android.test.mock")
    namespace = "com.panelvault.reader"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.panelvault.reader"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        versionCode = providers.environmentVariable("PANELVAULT_VERSION_CODE").orElse("100").get().toInt()
        versionName = "0.4.0"
    }
    signingConfigs {
        getByName("debug") {
            System.getenv("PANELVAULT_DEBUG_KEYSTORE")?.let { storeFile = file(it) }
        }
        create("persistentRelease") {
            val keyPath = System.getenv("PANELVAULT_KEYSTORE")
            if (keyPath != null) {
                storeFile = file(keyPath)
                storePassword = System.getenv("PANELVAULT_KEY_PASSWORD")
                keyAlias = "panelvault"
                keyPassword = System.getenv("PANELVAULT_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".dev"; versionNameSuffix = "-dev" }
        getByName("release") { signingConfig = signingConfigs.getByName("persistentRelease") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2"); implementation("me.zhanghai.android.libarchive:library:1.1.6") }

// Never distribute a release silently signed with an ephemeral debug key.
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        check(!System.getenv("PANELVAULT_KEYSTORE").isNullOrBlank()) { "PANELVAULT_KEYSTORE must point to the permanent release key" }
        check(!System.getenv("PANELVAULT_KEY_PASSWORD").isNullOrBlank()) { "PANELVAULT_KEY_PASSWORD is required" }
    }
}
