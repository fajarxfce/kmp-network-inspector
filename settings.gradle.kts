pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy(
                    providers
                        .gradleProperty("matrixSdkRepository")
                        .orElse("https://github.com/fajarxfce/hris-mobile/releases/download")
                        .get()
                ) {
                    name = "Matrix observer SDK"
                    patternLayout {
                        ivy("matrix-sdk-[revision]/[module]-[revision].ivy.xml")
                        artifact("matrix-sdk-[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources { ivyDescriptor() }
                }
            }
            filter { includeModule("dev.fajar.hris.matrix", "sdk-android") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "kmp-network-inspector"

include(":core", ":ktor", ":compose", ":matrix", ":matrix-rust-android")
