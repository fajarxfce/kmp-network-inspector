import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    android {
        namespace = "dev.fajar.inspector.notification"
        compileSdk = 36
        minSdk = 24
        withDeviceTest {
            instrumentationRunner = "dev.fajar.inspector.notification.NotificationInstrumentation"
        }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets { commonMain.dependencies { api(project(":core")) } }
}
