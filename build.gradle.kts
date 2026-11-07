import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    group = "dev.fajar.inspector"
    version = "0.1.0-SNAPSHOT"
}

allprojects {
    plugins.withType<WasmNodeJsPlugin> {
        extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
    }
    @OptIn(ExperimentalWasmDsl::class)
    plugins.withType<BinaryenPlugin> {
        extensions.configure<BinaryenEnvSpec> { downloadBaseUrl.set(null as String?) }
    }
}
