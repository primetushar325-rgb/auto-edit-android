plugins { id("com.android.application") }

android {
    namespace = "com.autoedit"
    compileSdk = 35
    defaultConfig { applicationId = "com.autoedit"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "1.0.7"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies { testImplementation("junit:junit:4.13.2") }
