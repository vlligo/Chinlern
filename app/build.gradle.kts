import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

val syncConfig = Properties().apply {
    val file = rootProject.file("sync-access.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val syncVerifier = providers.environmentVariable("CHINLEARN_SYNC_PASSWORD_SHA256")
    .orElse(syncConfig.getProperty("syncPasswordSha256", "")).get()
require(syncVerifier.isEmpty() || syncVerifier.matches(Regex("[0-9a-fA-F]{64}"))) {
    "Sync verifier must be a SHA-256 hex digest."
}

android {
    buildFeatures { buildConfig = true }

    namespace = "com.example.chinlern"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.chinlern"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.1"
        buildConfigField("String", "SYNC_PASSWORD_SHA256", "\"$syncVerifier\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // BoM 35 requires Kotlin 2.4 metadata; AGP's bundled compiler is currently 2.2.
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
