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

internal fun getCoveredLibraries(
        configuration: CompilerConfiguration,
        librariesToLink: List<KonanLibrary>
): Pair<List<KonanLibrary>, List<KonanLibrary>> {
    val coveredLibraries = configuration.getList(KonanConfigKeys.LIBRARIES_TO_COVER).map { File(it) }.toSet()

    return librariesToLink.partition { it.libraryFile in coveredLibraries }
}

internal fun generateGcovMetadataIfNeeded(context: Context, compileUnit: DICompileUnitRef, path: FileAndFolder, irFile: IrFile) {
    if (context.shouldEmitGcov()) {
        generateGcovMetadata(context, compileUnit, path, irFile)
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