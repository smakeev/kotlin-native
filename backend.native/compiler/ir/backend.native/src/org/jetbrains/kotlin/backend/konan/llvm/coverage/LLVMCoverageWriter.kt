package org.jetbrains.kotlin.backend.konan.llvm.coverage

import kotlinx.cinterop.*
import llvm.*
import llvm.Region
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.ContextUtils
import org.jetbrains.kotlin.backend.konan.llvm.localHash
import org.jetbrains.kotlin.backend.konan.llvm.symbolName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.name

private data class IndexedFile(val index: Int, val file: IrFile)

internal class LLVMCoverageWriter(override val context: Context, val filesRegionsInfo: List<LLVMFileRegionInfo>) : ContextUtils {

    private val coverageMappings = mutableListOf<String>()

    private val indexedFiles = filesRegionsInfo.mapIndexed { index, fileRegionInfo -> IndexedFile(index, fileRegionInfo.file) }

    fun write() {
        val module = context.llvmModule
                ?: error("LLVM module should be initialized")

        val functionRecords = filesRegionsInfo.flatMap { it.functions }.map(this::addFunctionMappingRecord)

        val filenames = indexedFiles.map { it.file.name }
        val fileIds = IntArray(indexedFiles.size) { indexedFiles[it].index }

        val coverageGlobal = memScoped {
            LLVMCoverageEmit(LLVMGetModuleContext(module), module,
                    functionRecords.toCValues(), functionRecords.size.signExtend(),
                    filenames.toCStringArray(this), fileIds.toCValues(), fileIds.size.signExtend(),
                    coverageMappings.toCStringArray(this), coverageMappings.size.signExtend()
            )!!
        }
        context.llvm.usedGlobals.add(coverageGlobal)
    }

    private fun addFunctionMappingRecord(functionRegions: LLVMFunctionRegions): LLVMValueRef {
        return memScoped {
            val counterExpressionBuilder = LLVMCreateCounterExpressionBuilder()
            val regions = functionRegions.regions.values.map { region ->
                alloc<Region>().apply {
                    fileId = indexedFiles.first { it.file == region.file }.index
                    lineStart = region.startLine
                    columnStart = region.startColumn
                    lineEnd = region.endLine
                    columnEnd = region.endColumn
                    counterId = functionRegions.regionEnumeration.getValue(region)
                }.ptr
            }
            // TODO: Suboptimal performance.
            val fileIds = functionRegions.regions.map { it.value.file }.map { file -> indexedFiles.first { it.file == file}.index }.toIntArray().toCValues()
            val coverageMapping = LLVMWriteCoverageRegionMapping(fileIds, fileIds.size.signExtend(), regions.toCValues(), regions.size.signExtend(), counterExpressionBuilder)!!.toKString()
            coverageMappings += coverageMapping
            val hash = functionRegions.function.symbolName.localHash.value
            LLVMAddFunctionMappingRecord(LLVMGetModuleContext(context.llvmModule), functionRegions.function.symbolName, hash, coverageMapping)!!
        }
    }
}
