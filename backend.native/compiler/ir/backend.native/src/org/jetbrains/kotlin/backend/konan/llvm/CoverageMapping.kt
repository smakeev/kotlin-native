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
        val endColumn: Int,
        val counterId: Int
)

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
                    FunctionMapping(IntArray(fileIdMapping.indexOf(file)), file.name, function.symbolName, regions, function.symbolName.localHash.value)
                }
                .filter { it.regions.isNotEmpty() }
                .let {
                    CoverageMappings(it, fileIdMapping)
                }
    }

    private fun collectFunctionRegions(file: IrFile, irFunction: IrFunction): List<SourceMappingRegion> =
            irFunction.body?.let { body ->
                val coverageVisitor = CoverageVisitor(file, fileIdMapping.indexOf(file), FunctionCoverageBuilder(file, irFunction))
                body.acceptVoid(coverageVisitor)
                coverageVisitor.regionStack
            } ?: emptyList()
}

internal class FunctionCoverageBuilder(val irFile: IrFile, val irFunction: IrFunction) {
    val enumeration: Map<IrElement, Int> = with(CoverageRegionIrEnumerator()) {
        visitElement(irFunction)
        irEnumeration
    }
}

private class CoverageRegionIrEnumerator : IrElementVisitorVoidWithContext() {

    val irEnumeration = mutableMapOf<IrElement, Int>()

    private var counter = 0

    override fun visitElement(element: IrElement) {
        irEnumeration[element] = counter++
        element.acceptChildrenVoid(this)
    }
}

// TODO: #coverage extend to other IrElements
// TODO: #coverage What if we meet nested function?
private class CoverageVisitor(val file: IrFile, val fileId: Int, val functionCoverageBuilder: FunctionCoverageBuilder) : IrElementVisitorVoidWithContext() {

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
                file.fileEntry.column(body.endOffset),
                functionCoverageBuilder.enumeration.getValue(body)
        ))
    }
}

internal interface CoverageMappingsWriter {
    fun write(coverageMappings: CoverageMappings)
}

internal class LLVMCoverageMappingsWriter(val context: Context) : CoverageMappingsWriter {

    private val covMaps = mutableListOf<String>()

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
                    covMaps.toCStringArray(this), covMaps.size.signExtend()
            )!!
        }
        context.llvm.usedGlobals.add(coverageGlobal)
    }

    private fun addFunctionMappingRecord(functionMapping: FunctionMapping): LLVMValueRef {
        return memScoped {
            val counterExpressionBuilder = LLVMCreateCounterExpressionBuilder()
            val regions = functionMapping.regions.map { region ->
                LLVMBuilderAddCounters(counterExpressionBuilder, region.counterId, region.counterId)
                alloc<Region>().apply {
                    fileId = region.fileId
                    lineStart = region.startLine
                    columnStart = region.startColumn
                    lineEnd = region.endLine
                    columnEnd = region.endColumn
                    counterId = region.counterId
                }.ptr
            }
            val fileIds = functionMapping.fileIds.toCValues()
            val coverageMapping = LLVMWriteCoverageRegionMapping(fileIds, fileIds.size.signExtend(), regions.toCValues(), regions.size.signExtend(), counterExpressionBuilder)!!.toKString()
            covMaps += coverageMapping
            LLVMAddFunctionMappingRecord(LLVMGetModuleContext(context.llvmModule), functionMapping.function, functionMapping.hash, coverageMapping)!!
        }
    }
}

internal class IrToCoverageRegionMapper(
        override val context: Context,
        val module: LLVMModuleRef,
        function: IrFunction,
        val callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit) : ContextUtils {

    private val funcGlobal = getOrPutFunctionName(function)
    private val hash = Int64(function.symbolName.localHash.value).llvm

    fun placeRegionIncrement(region: Int) {
        val numberOfRegions = Int32(1).llvm
        val region = Int32(region).llvm
        callSitePlacer(LLVMInstrProfIncrement(module)!!, listOf(funcGlobal, hash, numberOfRegions, region))
    }

    private fun getOrPutFunctionName(function: IrFunction): LLVMValueRef {
        val name = function.symbolName
        val x = LLVMCreatePGOFunctionNameVar(function.llvmFunction, name)!!
        return LLVMConstBitCast(x, int8TypePtr)!!
    }
}

