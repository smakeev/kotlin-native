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
        }
        compilations["main"].extraOpts = mutableListOf("-Xgcov-dir=$buildDir/gcov")
        binaries.getExecutable("test", DEBUG).apply {
            freeCompilerArgs = mutableListOf("-Xgcov-dir=$buildDir/gcov", "-Xlibrary-to-profile=${compilations["main"].output.classesDirs.singleFile.absolutePath}")
        }
    }
}

tasks.create("createCoverageReport") {
    dependsOn("macosTest")

    description = "Create lcov report"

    doLast {
        exec {
            workingDir = File("$buildDir/gcov")
            commandLine("lcov", "-c", "-d", ".", "-o", "coverage_results.info")
        }
        exec {
            workingDir = File("$buildDir/gcov")
            commandLine("genhtml", "coverage_results.info", "--output-directory", "out")
        }
    }
}