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

// Soon will be implemented as part of Gradle plugin.
tasks.create("collectGcov") {
    dependsOn("macosTest")

    description = "Create .gcov files based on .gdca and .gcno"

    fileTree("$buildDir/gcov").matching { include("**/.gcda") }.forEach { file ->
        exec {
            // TODO: change workingDir to something more useful
            workingDir = file.parentFile
            // TODO: use `llvm-cov` from distribution
            commandLine("llvm-cov", "gcov", file.name)
        }
    }

}

tasks.create("createCoverageReport") {
    dependsOn("collectGcov")

    description = "Create lcov report"
}