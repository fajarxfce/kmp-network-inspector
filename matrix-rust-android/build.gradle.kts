import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    android {
        namespace = "dev.fajar.inspector.matrix.rust"
        compileSdk = 36
        minSdk = 24
        packaging { jniLibs.keepDebugSymbols += "**/libmatrix_sdk_ffi.so" }
        withHostTest {}
        withDeviceTest {
            applicationId = "dev.fajar.inspector.matrix.rust.proof"
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
