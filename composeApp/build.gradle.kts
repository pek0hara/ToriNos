import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.nostr.torinos.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "com.nostr.torinos")
        }
        iosTarget.compilations["main"].cinterops {
            val keychainHelper by creating {
                defFile(project.file("src/iosMain/cinterop/KeychainHelper.def"))
                packageName("com.nostr.torinos.crypto.interop")
            }
        }
    }

    sourceSets {
        val commonMain by getting
        val mobileMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(mobileMain)
            dependencies {
                implementation(compose.preview)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.secp256k1.kmp)
                implementation(libs.secp256k1.kmp.jni.android)
                implementation(libs.datastore.preferences)
                implementation(libs.activity.compose)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.ui)
                implementation(libs.credentials)
                implementation(libs.credentials.play.services.auth)
            }
        }
        val iosMain by creating {
            dependsOn(mobileMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.secp256k1.kmp)
            }
        }
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        iosArm64Main.dependsOn(iosMain)
        iosSimulatorArm64Main.dependsOn(iosMain)
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val verifyNoDirectProfileSubscriptions by tasks.registering {
    group = "verification"
    description = "ProfileRepository外にkind:0の直接購読がないことを検証します。"
    val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    inputs.files(sources)

    doLast {
        val directKindZero = Regex("""kinds\s*=\s*listOf\(\s*0\s*\)""")
        val violations = sources.files
            .filter { it.readText().contains(directKindZero) }
            .map { it.relativeTo(projectDir).invariantSeparatorsPath }
            .sorted()
        check(violations.isEmpty()) {
            "kind:0の直接購読は禁止されています。ProfileRepositoryを使用してください:\n" +
                violations.joinToString("\n")
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyNoDirectProfileSubscriptions)
}
