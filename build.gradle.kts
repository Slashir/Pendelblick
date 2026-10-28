// Versionen geprüft am 27.10.2026 (Quellen siehe README).
//
// AGP 9 bringt Kotlin selbst mit ("built-in Kotlin"), das Plugin
// org.jetbrains.kotlin.android entfällt. Die Kotlin-Version wird über den
// Klassenpfad unten auf 2.4.21 gehoben, damit Compose- und Serialization-Plugin
// zur gleichen Kotlin-Version passen.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
