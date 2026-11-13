plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.tejashandigol.tiltcards"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tejashandigol.tiltcards.creator"
        minSdk = 28
        targetSdk = 36
        versionCode = 18
        versionName = "1.16"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
