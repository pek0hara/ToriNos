import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

dependencies {
    implementation(project(":composeApp"))
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)

android {
    namespace = "com.nostr.torinos"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.nostr.torinos"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 17
        versionName = "1.1.1"
    }

    val releaseStoreFile = signingProperty("TORINOS_RELEASE_STORE_FILE")
    val releaseStorePassword = signingProperty("TORINOS_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = signingProperty("TORINOS_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingProperty("TORINOS_RELEASE_KEY_PASSWORD")

    if (
        !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }

        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        disable += "CredManMissingDal"
    }
}
