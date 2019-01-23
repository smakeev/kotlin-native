plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }

    macosX64("macos") {
        binaries {
            executable(listOf(DEBUG)) {
                entryPoint = "coverage.main"
            }
            getExecutable("test", DEBUG)
        }
        compilations["main"].extraOpts = mutableListOf("-Xgcov-dir=$buildDir/gcov")
    }
}