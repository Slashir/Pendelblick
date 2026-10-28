plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signatur für Release-Builds kommt aus Umgebungsvariablen (GitHub Secrets, siehe ENTWICKLUNG.md).
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "app.pendelwecker"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.pendelwecker"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

        // MapLibre bringt native Bibliotheken je Prozessor mit; ältere 32-Bit-x86-Geräte gibt es praktisch nicht.
        // Mit -Pabis=arm64-v8a lässt sich eine kleinere APK für ein bestimmtes Gerät bauen.
        val abis = (project.findProperty("abis") as String?)?.split(",")
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        ndk { abiFilters += abis }

        buildConfigField("String", "TRANSITOUS_BASE", "\"https://api.transitous.org\"")
        // Transitous verlangt Kontakt im User-Agent: über dieses Repo erreichen sie den Entwickler.
        buildConfigField("String", "CONTACT", "\"https://github.com/Slashir/Pendelblick\"")
        // Freiwillige Unterstützung des Entwicklers (Ko-fi). Leer = Knopf ausgeblendet.
        buildConfigField("String", "DONATE_URL", "\"https://ko-fi.com/slashir\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Ohne Verkleinerung: R8 bräuchte eigene Regeln für Serialisierung und MapLibre.
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Zwei Varianten derselben App, nur die Kartenansicht unterscheidet sich.
    flavorDimensions += "karte"
    productFlavors {
        create("travic") {
            dimension = "karte"
            applicationIdSuffix = ".travic"
            versionNameSuffix = "-travic"
            // Erst nach schriftlicher Erlaubnis der TRAVIC-Betreiber veröffentlichen.
            buildConfigField("String", "TRAVIC_URL", "\"https://travic.app/\"")
        }
        create("eigen") {
            dimension = "karte"
            applicationIdSuffix = ".eigen"
            versionNameSuffix = "-eigen"
            // Kartenhintergrund: OpenFreeMap, ohne API-Key, Attribution macht MapLibre automatisch.
            buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.datastore:datastore-preferences-core:1.3.0") // Widget-Zustand (Glance)

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Nur die Variante mit eigener Karte braucht MapLibre.
    "eigenImplementation"("org.maplibre.gl:android-sdk:13.7.1")

    testImplementation("junit:junit:4.13.2")
}
