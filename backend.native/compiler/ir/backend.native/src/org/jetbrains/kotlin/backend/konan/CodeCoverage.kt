package org.jetbrains.kotlin.backend.konan

import kotlinx.cinterop.reinterpret
import llvm.*
import org.jetbrains.kotlin.backend.konan.ir.IrFileImpl
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.js.translate.utils.splitToRanges
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KonanLibrary
import org.jetbrains.kotlin.konan.library.resolver.KonanLibraryResolveResult

sealed class CodeCoverageMode {

    object None : CodeCoverageMode()

    object CompilerOutput : CodeCoverageMode()

    class Libraries(val librariesToProfile: List<String>) : CodeCoverageMode() {

        // Split linked libraries into two lists:
        // 1. Libs that should be profiled
        // 2. And that should not
        fun getCoveredLibraries(librariesToLink: List<KonanLibrary>): Pair<List<KonanLibrary>, List<KonanLibrary>> {
            val libsSet = librariesToProfile.map { File(it) }.toSet()
            return librariesToLink.partition { it.libraryFile in libsSet }
        }
    }
}

internal fun determineCoverageMode(configuration: CompilerConfiguration): CodeCoverageMode =
        when {
            configuration.get<String>(KonanConfigKeys.GCOV_DIRECTORY) == null -> CodeCoverageMode.None
            configuration.getList(KonanConfigKeys.LIBRARIES_TO_COVER).isNullOrEmpty() -> CodeCoverageMode.CompilerOutput
            else -> CodeCoverageMode.Libraries(configuration.getList(KonanConfigKeys.LIBRARIES_TO_COVER))
        }

internal fun generateGcovMetadataIfNeeded(context: Context, compileUnit: DICompileUnitRef, path: FileAndFolder, irFile: IrFile) {
    if (context.coverageMode == CodeCoverageMode.CompilerOutput) {
        generateGcovMetadata(context, compileUnit, path, irFile)
    }
}

internal fun profileModuleIfNeeded(llvmModule: LLVMModuleRef, context: Context): LLVMModuleRef {
    val outputKind = context.config.configuration.get(KonanConfigKeys.PRODUCE)!!
    return if (context.coverageMode is CodeCoverageMode.CompilerOutput && outputKind.isNativeBinary) {
        val nonProfiledModuleFile = context.config.tempFiles.create("non_profiled", ".bc")
        LLVMWriteBitcodeToFile(llvmModule, nonProfiledModuleFile.absolutePath)
        val profiledModulePath = LlvmCli(context).profileWithGcov(nonProfiledModuleFile.absolutePath)
        parseBitcodeFile(profiledModulePath)
    } else {
        llvmModule
    }
}

// Create !llvm.gcov triple for the file in the following format:
// { gcovDir/fqName.gcno, gcovDir/fqName.gcda, compileUnit }
internal fun generateGcovMetadata(context: Context, compileUnit: DICompileUnitRef, path: FileAndFolder, irFile: IrFile) {
    // Skip fake files
    if (irFile is IrFileImpl) return

    val cuAsValue = LLVMMetadataAsValue(LLVMGetModuleContext(context.llvmModule), compileUnit.reinterpret())!!
    val gcovDir = context.createDirForGcov(context.config.configuration.get(KonanConfigKeys.GCOV_DIRECTORY)!!)
    val filename = mangleFilenameForGcov(path.folder, irFile.name.removeSuffix(".kt"))

    val gcnoPath = "${gcovDir.absolutePath}/$filename.gcno"
    val gcdaPath = "${gcovDir.absolutePath}/$filename.gcda"
    val gcovNode = node(gcnoPath.mdString(), gcdaPath.mdString(), cuAsValue)

    LLVMAddNamedMetadataOperand(context.llvmModule, "llvm.gcov", gcovNode)
}

// TODO: is such mangling is to aggressive?
private fun mangleFilenameForGcov(absolutePath: String, filename: String): String =
        "$filename${base64Encode(absolutePath.toByteArray())}"

private fun Context.createDirForGcov(path: String) = File(path).also {
    when {
        it.isFile -> reportCompilationError("Given path is not a directory: $path")
        !it.exists -> it.mkdirs()
    }
}