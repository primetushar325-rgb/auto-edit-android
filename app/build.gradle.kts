plugins { id("com.android.application") }

// Optional release signing, driven ONLY by environment variables set by CI
// (GitHub Actions secrets). Nothing is hardcoded; without the env vars the
// release APK builds unsigned (testing only).
val signingStoreFile = System.getenv("SIGNING_KEYSTORE_FILE")
val signingStorePass = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val signingAlias = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPass = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = !signingStoreFile.isNullOrBlank()
        && !signingStorePass.isNullOrBlank()
        && !signingAlias.isNullOrBlank()

android {
    namespace = "com.autoedit"
    compileSdk = 35
    defaultConfig { applicationId = "com.autoedit"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "1.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePass
                keyAlias = signingAlias
                keyPassword = signingKeyPass ?: signingStorePass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // androidx.core only for FileProvider (content:// URIs for ZIP share + APK install).
    implementation("androidx.core:core:1.13.1")
}
