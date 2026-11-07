import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

val deviceTestAbi = providers.gradleProperty("matrixObserverTestAbi").orNull

require(
    deviceTestAbi == null || deviceTestAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
) {
    "Unsupported Matrix observer test ABI"
}

kotlin {
    android {
        namespace = "dev.fajar.inspector.matrix.rust"
        compileSdk = 36
        minSdk = 24
        packaging {
            jniLibs.keepDebugSymbols += "**/libmatrix_sdk_ffi.so"
            deviceTestAbi?.let { selected ->
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "armeabi", "mips", "mips64")
                    .filter { it != selected }
                    .forEach { jniLibs.excludes += "**/$it/**" }
            }
        }
        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "dev.fajar.inspector.matrix.rust.ObserverInstrumentation"
        }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies { api(project(":matrix")) }
        androidMain.dependencies { api(libs.matrix.sdk.android) }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
