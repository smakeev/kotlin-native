/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.llvm.BitcodeFile
import org.jetbrains.kotlin.backend.konan.llvm.LlvmCli
import org.jetbrains.kotlin.konan.KonanExternalToolFailure
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.file.isBitcode
import org.jetbrains.kotlin.konan.library.KonanLibrary
import org.jetbrains.kotlin.konan.target.*

typealias ExecutableFile = String

internal class LinkStage(val context: Context, val phaser: PhaseManager) {

    private val config = context.config.configuration
    private val target = context.config.target
    private val platform = context.config.platform
    private val linker = platform.linker

    private val optimize = context.shouldOptimize()
    private val debug = context.config.debug
    private val linkerOutput = when (context.config.produce) {
        CompilerOutputKind.DYNAMIC, CompilerOutputKind.FRAMEWORK -> LinkerOutputKind.DYNAMIC_LIBRARY
        CompilerOutputKind.STATIC -> LinkerOutputKind.STATIC_LIBRARY
        CompilerOutputKind.PROGRAM -> LinkerOutputKind.EXECUTABLE
        else -> TODO("${context.config.produce} should not reach native linker stage")
    }
    private val nomain = config.get(KonanConfigKeys.NOMAIN) ?: false
    private val emitted = context.bitcodeFileName
    private val libraries = context.llvm.librariesToLink

    private val llvmCli = LlvmCli(context)

    private fun asLinkerArgs(args: List<String>): List<String> {
        if (linker.useCompilerDriverAsLinker) {
            return args
        }

        val result = mutableListOf<String>()
        for (arg in args) {
            // If user passes compiler arguments to us - transform them to linker ones.
            if (arg.startsWith("-Wl,")) {
                result.addAll(arg.substring(4).split(','))
            } else {
                result.add(arg)
            }
        }
        return result
    }

    // Ideally we'd want to have 
    //      #pragma weak main = Konan_main
    // in the launcher.cpp.
    // Unfortunately, anything related to weak linking on MacOS
    // only seems to be working with dynamic libraries.
    // So we stick to "-alias _main _konan_main" on Mac.
    // And just do the same on Linux.
    private val entryPointSelector: List<String>
        get() = if (nomain || linkerOutput != LinkerOutputKind.EXECUTABLE) emptyList() else platform.entrySelector

    private fun link(objectFiles: List<ObjectFile>,
                     includedBinaries: List<String>,
                     libraryProvidedLinkerFlags: List<String>): ExecutableFile {
        val frameworkLinkerArgs: List<String>
        val executable: String

        if (context.config.produce != CompilerOutputKind.FRAMEWORK) {
            frameworkLinkerArgs = emptyList()
            executable = context.config.outputFile
        } else {
            val framework = File(context.config.outputFile)
            val dylibName = framework.name.removeSuffix(".framework")
            val dylibRelativePath = when (target.family) {
                Family.IOS -> dylibName
                Family.OSX -> "Versions/A/$dylibName"
                else -> error(target)
            }
            frameworkLinkerArgs = listOf("-install_name", "@rpath/${framework.name}/$dylibRelativePath")
            val dylibPath = framework.child(dylibRelativePath)
            dylibPath.parentFile.mkdirs()
            executable = dylibPath.absolutePath
        }

        try {
            File(executable).delete()
            linker.linkCommands(objectFiles = objectFiles, executable = executable,
                    libraries = linker.linkStaticLibraries(includedBinaries) + context.config.defaultSystemLibraries,
                    linkerArgs = entryPointSelector +
                            asLinkerArgs(config.getNotNull(KonanConfigKeys.LINKER_ARGS)) +
                            BitcodeEmbedding.getLinkerOptions(context.config) +
                            libraryProvidedLinkerFlags + frameworkLinkerArgs,
                    optimize = optimize, debug = debug, kind = linkerOutput,
                    outputDsymBundle = context.config.outputFile + ".dSYM").forEach {
                it.logWith(context::log)
                it.execute()
            }
        } catch (e: KonanExternalToolFailure) {
            context.reportCompilationError("${e.toolName} invocation reported errors")
        }
        return executable
    }

    private fun KonanLibrary.getBitcodeFiles(): List<BitcodeFile> =
            bitcodePaths.filter { it.isBitcode }

    fun linkStage() {

        val bitcodeFiles = prepareBitcodeFiles()

        val includedBinaries = libraries.map { it.includedPaths }.flatten()

        val libraryProvidedLinkerFlags = libraries.map { it.linkerOpts }.flatten()

        val objectFiles: MutableList<String> = mutableListOf()

        phaser.phase(KonanPhase.OBJECT_FILES) {
            objectFiles.add(
                    when (platform.configurables) {
                        is WasmConfigurables
                        -> llvmCli.bitcodeToWasm(bitcodeFiles, optimize, debug)
                        is ZephyrConfigurables
                        -> llvmCli.llvmLinkAndLlc(bitcodeFiles)
                        else
                        -> llvmCli.llvmLto(bitcodeFiles, optimize, debug)
                    }
            )
        }
        phaser.phase(KonanPhase.LINKER) {
            link(objectFiles, includedBinaries, libraryProvidedLinkerFlags)
        }
    }

    private fun prepareBitcodeFiles(): List<BitcodeFile> =
        listOf(emitted) + if (context.coverageMode is CodeCoverageMode.Libraries) {
            val (libsToCover, otherLibs) = context.coverageMode.getCoveredLibraries(libraries)

            val coveredBitcode = libsToCover.flatMap { lib ->
                lib.getBitcodeFiles().map { llvmCli.profileWithGcov(it) }
            }
            coveredBitcode + otherLibs.flatMap { it.getBitcodeFiles() }
        } else {
            libraries.flatMap { it.getBitcodeFiles() }
        }

}

