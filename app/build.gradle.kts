import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing values come from environment variables (CI) or from keystore.properties
// in the repository root (local builds; the file is git-ignored, see keystore.properties.example).
val keystoreProps = Properties().also { props ->
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val signStoreFile = signingValue("KEYSTORE_FILE", "storeFile")
val signStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val signKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val signKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val hasSigning = listOf(signStoreFile, signStorePassword, signKeyAlias, signKeyPassword).all { it != null }

android {
    namespace = "app.dimwall"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dimwall"
        minSdk = 29
        targetSdk = 36
        // CI passes these from the git tag (v1.2.3 -> versionName 1.2.3, versionCode 10203).
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.0.0-dev"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
