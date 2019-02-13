package org.jetbrains.kotlin.backend.konan.llvm

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.common.IrElementVisitorVoidWithContext
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid


// #coverage TODO: Probably should hold either irFile ref or FileID
internal data class SourceMappingRegion(
        val fileId: Int,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int
) {
    override fun toString(): String =
            "$startLine:$startColumn - $endLine:$endColumn"
}

internal class FunctionMapping(
        val fileIds: IntArray,
        val pathToFile: String,
        val function: String,
        val regions: List<SourceMappingRegion>,
        val hash: Long
)

internal class CoverageMappings(
        val functionMappings: List<FunctionMapping>,
        val fileIdMapping: List<IrFile>
)

internal class CoverageMappingsBuilder {

    private val toCover = mutableMapOf<IrFile, MutableList<IrFunction>>()

    // Position in list is represents file's id.
    // TODO: It can be simplified.
    private val fileIdMapping = mutableListOf<IrFile>()

    fun collect(file: IrFile, irFunction: IrFunction) {
        if (file !in toCover) {
            toCover[file] = mutableListOf()
            fileIdMapping += file
        }
        toCover.getValue(file).add(irFunction)
    }

    fun build(): CoverageMappings {
        return toCover.flatMap { (file, functions) -> functions.map { file to it } }
                .map { (file, function) ->
                    val regions = collectFunctionRegions(file, function)
                    // TODO: use global hash instead
                    // TODO: Ids of the inline functions
                    FunctionMapping(IntArray(fileIdMapping.indexOf(file)), file.name, function.functionName, regions, function.name.localHash.value)
                }
                .filter { it.regions.isEmpty() }
                .let {
                    CoverageMappings(it, fileIdMapping)
                }
    }

    private fun collectFunctionRegions(file: IrFile, irFunction: IrFunction): List<SourceMappingRegion> =
            irFunction.body?.let { body ->
                val coverageVisitor = CoverageVisitor(file, fileIdMapping.indexOf(file))
                body.acceptVoid(coverageVisitor)
                coverageVisitor.regionStack
            } ?: emptyList()

    // TODO: #coverage extend to other IrElements
    // TODO: #coverage What if we meet nested function?
    private class CoverageVisitor(val file: IrFile, val fileId: Int) : IrElementVisitorVoidWithContext() {

        val regionStack = mutableListOf<SourceMappingRegion>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitBody(body: IrBody) {

            regionStack.push(SourceMappingRegion(
                    fileId,
                    file.fileEntry.line(body.startOffset),
                    file.fileEntry.column(body.startOffset),
                    file.fileEntry.line(body.endOffset),
                    file.fileEntry.column(body.endOffset)
            ))
        }
    }
}

internal interface CoverageMappingsWriter {
    fun write(coverageMappings: CoverageMappings)
}

internal class PrintCoverageMappingsWriter : CoverageMappingsWriter {
    override fun write(coverageMappings: CoverageMappings) {
        coverageMappings.functionMappings.forEach {
            println("${it.pathToFile} ${it.function}")
            it.regions.forEachIndexed { index, region ->
                println("$index: $region")
            }
        }
    }
}

internal class LLVMCoverageMappingsWriter(val context: Context) : CoverageMappingsWriter {

    private val zzz = mutableListOf<String>()

    override fun write(coverageMappings: CoverageMappings) {
        val module = context.llvmModule
                ?: error("LLVM module should be initialized")

        val functionRecords = coverageMappings.functionMappings.map(this::addFunctionMappingRecord)

        val filenames = coverageMappings.fileIdMapping.map { it.name }
        val fileIds = IntArray(coverageMappings.fileIdMapping.size) { it }

        val coverageGlobal = memScoped {
            LLVMCoverageEmit(LLVMGetModuleContext(module), module,
                    functionRecords.toCValues(), functionRecords.size.signExtend(),
                    filenames.toCStringArray(this), fileIds.toCValues(), fileIds.size.signExtend(),
                    zzz.toCStringArray(this), zzz.size.signExtend()
            )!!
        }
        val section = LLVMCoverageGetCoverageSection(module)!!.toKString()
        LLVMSetSection(coverageGlobal, section)
        LLVMSetAlignment(coverageGlobal, 8)
        context.llvm.usedGlobals.add(coverageGlobal)
    }

    private fun addFunctionMappingRecord(functionMapping: FunctionMapping): LLVMValueRef {
        val regions = functionMapping.let(this::makeFunctionCounterMapping)
        val cv = regions.toCValues()
        val fileIds = functionMapping.fileIds.toCValues()
        val coverageMapping = (LLVMWriteCoverageRegionMapping(fileIds, fileIds.size.signExtend(), cv, cv.size.signExtend())?.toKString()
                ?: error("Cannot write coverage region mapping"))
        zzz += coverageMapping
        return LLVMAddFunctionMappingRecord(LLVMGetModuleContext(context.llvmModule), functionMapping.function, functionMapping.hash, coverageMapping)!!
    }

    private fun makeFunctionCounterMapping(functionMapping: FunctionMapping): List<LLVMCounterMappingRegionRef?> {
        val regions = functionMapping.regions.map { region ->
            val (fileId, startLine, startColumn, endLine, endColumn) = region
            LLVMCounterMappingMakeRegion(fileId, startLine, startColumn, endLine, endColumn)
        }
        return regions
    }

}

