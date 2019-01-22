package org.jetbrains.kotlin.backend.konan.llvm

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.konan.exec.Command
import org.jetbrains.kotlin.konan.target.AppleConfigurables
import org.jetbrains.kotlin.konan.target.WasmConfigurables

typealias BitcodeFile = String
typealias ObjectFile = String

internal class LlvmCli(val context: Context) {

    private val platform = context.config.platform

    private fun runTool(command: List<String>) = runTool(*command.toTypedArray())

    private fun runTool(vararg command: String) =
            Command(*command)
                    .logWith(context::log)
                    .execute()

    private fun temporary(name: String, suffix: String): String =
            context.config.tempFiles.create(name, suffix).absolutePath

    private fun MutableList<String>.addNonEmpty(elements: List<String>) {
        addAll(elements.filter { !it.isEmpty() })
    }

    private fun targetTool(tool: String, vararg arg: String) {
        val absoluteToolName = "${platform.absoluteTargetToolchain}/bin/$tool"
        runTool(absoluteToolName, *arg)
    }

    private fun hostLlvmTool(tool: String, vararg arg: String) {
        val absoluteToolName = "${platform.absoluteLlvmHome}/bin/$tool"
        runTool(absoluteToolName, *arg)
    }

    // llvm-lto, opt and llc share same profiling flags, so we can
    // reuse this function.
    private fun llvmProfilingFlags(): List<String> {
        val flags = mutableListOf<String>()
        if (context.shouldProfilePhases()) {
            flags += "-time-passes"
        }
        if (context.phase?.verbose == true) {
            flags += "-debug-pass=Structure"
        }
        return flags
    }

    fun llvmLto(files: List<BitcodeFile>, optimize: Boolean, debug: Boolean): ObjectFile {
        val combined = temporary("combined", ".o")

        val tool = "${platform.absoluteLlvmHome}/bin/llvm-lto"
        val command = mutableListOf(tool, "-o", combined)
        command.addNonEmpty(platform.llvmLtoFlags)
        command.addNonEmpty(llvmProfilingFlags())
        when {
            optimize -> command.addNonEmpty(platform.llvmLtoOptFlags)
            debug -> command.addNonEmpty(platform.llvmDebugOptFlags)
            else -> command.addNonEmpty(platform.llvmLtoNooptFlags)
        }
        command.addNonEmpty(platform.llvmLtoDynamicFlags)
        command.addNonEmpty(files)
        runTool(command)

        return combined
    }

    fun bitcodeToWasm(bitcodeFiles: List<BitcodeFile>, optimize: Boolean, debug: Boolean): String {
        val configurables = platform.configurables as WasmConfigurables

        val combinedBc = temporary("combined", ".bc")
        // TODO: use -only-needed for the stdlib
        hostLlvmTool("llvm-link", *bitcodeFiles.toTypedArray(), "-o", combinedBc)
        val optFlags = (configurables.optFlags + when {
            optimize -> configurables.optOptFlags
            debug -> configurables.optDebugFlags
            else -> configurables.optNooptFlags
        } + llvmProfilingFlags()).toTypedArray()
        val optimizedBc = temporary("optimized", ".bc")
        hostLlvmTool("opt", combinedBc, "-o", optimizedBc, *optFlags)
        val llcFlags = (configurables.llcFlags + when {
            optimize -> configurables.llcOptFlags
            debug -> configurables.llcDebugFlags
            else -> configurables.llcNooptFlags
        } + llvmProfilingFlags()).toTypedArray()
        val combinedO = temporary("combined", ".o")
        hostLlvmTool("llc", optimizedBc, "-o", combinedO, *llcFlags, "-filetype=obj")
        val linkedWasm = temporary("linked", ".wasm")
        hostLlvmTool("wasm-ld", combinedO, "-o", linkedWasm, *configurables.lldFlags.toTypedArray())
        return linkedWasm
    }

    fun llvmLinkAndLlc(bitcodeFiles: List<BitcodeFile>): String {
        val combinedBc = temporary("combined", ".bc")
        hostLlvmTool("llvm-link", "-o", combinedBc, *bitcodeFiles.toTypedArray())

        val optimizedBc = temporary("optimized", ".bc")
        val optFlags = llvmProfilingFlags() + listOf("-O3", "-internalize", "-globaldce")
        hostLlvmTool("opt", combinedBc, "-o=$optimizedBc", *optFlags.toTypedArray())

        val combinedO = temporary("combined", ".o")
        val llcFlags = llvmProfilingFlags() + listOf("-function-sections", "-data-sections")
        hostLlvmTool("llc", optimizedBc, "-filetype=obj", "-o", combinedO, *llcFlags.toTypedArray())

        return combinedO
    }

    fun profileWithGcov(bitcode: String): String {
        val gcovProfiledBc = temporary("gcov_profiled", ".bc")
        val llvmPassesDir = (platform.configurables as AppleConfigurables).absoluteLlvmPassesDir
        val gcovPassLibrary =  "$llvmPassesDir/GCOVProfilingPatched.dylib"
        hostLlvmTool("opt", bitcode,
                "-disable-opt", // Do not run any optimizations except specified
                "-load", gcovPassLibrary,
                "-insert-gcov-profiling-patched",
                "-o", gcovProfiledBc)
        return gcovProfiledBc
    }
}