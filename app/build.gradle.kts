import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "com.theo.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theo.hush"
        minSdk = 26
        targetSdk = 35
        // numéro de version donné par GitHub (1.N) ; en local : 1.0
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (run != null) 100 + run else 10
        versionName = "2.${run ?: 0}"

        // Identifiants de l'API Tidal, lus depuis local.properties (fichier local, jamais partagé)
        val local = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"${local.getProperty("tidal.clientId", "")}\"")
        buildConfigField("String", "TIDAL_CLIENT_SECRET", "\"${local.getProperty("tidal.clientSecret", "")}\"")
    }

    // Sur GitHub : on signe avec la clé de l'Android Studio de Theo (déchiffrée par la compilation),
    // sinon Android refuse d'installer la mise à jour par-dessus.
    signingConfigs {
        getByName("debug") {
            System.getenv("HUSH_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // La version installée depuis Android Studio est optimisée (R8, non déboguable) :
        // en mode « debug » classique, Compose est nettement plus lent et l'appli rame.
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("com.google.zxing:core:3.5.3")
    // traduction des paroles, sur le téléphone
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.compose.material:material-icons-extended")

    // tests lancés avant chaque version
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
