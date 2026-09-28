import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                // index.html の CSP は eval を許可しないため、開発ビルドでも eval を使うソースマップにしない。
                devtool = "source-map"
            }
        }
        binaries.executable()
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
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
                // kotlinx-datetime の JS 実装は名前付きタイムゾーンの DB を同梱しない。
                // kotlinx-datetime が使う @js-joda/core 3.x と組み合わせられる版に固定する。
                implementation(npm("@js-joda/timezone", "2.3.0"))
                // secp256k1 と SHA-256。secp256k1-kmp は JS / Wasm 版を配布していないため、Web はこちらを使う。
                implementation(npm("@noble/curves", "2.4.0"))
                implementation(npm("@noble/hashes", "2.4.0"))
            }
        }
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
        // runBlocking と実スレッドを前提にするテストは Wasm では動かせないため、mobileTest に置く。
        val commonTest by getting
        val mobileTest by creating {
            dependsOn(commonTest)
        }
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        iosArm64Test.dependsOn(mobileTest)
        iosSimulatorArm64Test.dependsOn(mobileTest)
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
