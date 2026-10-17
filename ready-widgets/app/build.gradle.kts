import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.daxbot.readywidgets"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daxbot.readywidgets"
        // Android 12+: widgets use RemoteCollectionItems, so no RemoteViewsService is needed.
        minSdk = 31
        targetSdk = 35
        // CI passes its run number, so every build is newer than the last (see Updater).
        val build = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionCode = build
        versionName = "0.1.$build"

        buildConfigField("String", "UPDATE_REPO", "\"levi-hanson/security-logger\"")
        buildConfigField("String", "UPDATE_TAG", "\"${project.findProperty("updateTag") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Updates only install over a build signed with the same key, so CI signs every build
    // with the key from the READY_KEYSTORE_BASE64 secret. Without it, the default debug key.
    val keystore = System.getenv("READY_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("stable") {
                storeFile = keystore
                storePassword = "readywidgets"
                keyAlias = "readywidgets"
                keyPassword = "readywidgets"
            }
        }
    }

    buildTypes {
        debug {
            if (keystore != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
