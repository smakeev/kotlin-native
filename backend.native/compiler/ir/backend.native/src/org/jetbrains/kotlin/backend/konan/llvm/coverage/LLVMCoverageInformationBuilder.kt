package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.konan.llvm.column
import org.jetbrains.kotlin.backend.konan.llvm.line
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid


class LLVMRegionCounter()

class LLVMRegion(
        override val file: IrFile,
        override val startLine: Int,
        override val startColumn: Int,
        override val endLine: Int,
        override val endColumn: Int,
        val counter: LLVMRegionCounter
) : Region

class LLVMFunctionRegions(
        override val function: IrFunction,
        override val regions: Map<IrElement, LLVMRegion>
) : FunctionRegions {
    val regionEnumeration = regions.values.mapIndexed { index, region -> region to index }.toMap()
}

class LLVMFileRegionInfo(
        override val file: IrFile,
        override val functions: List<LLVMFunctionRegions>
) : FileRegionInfo

class LLVMCoverageRegionCollector : CoverageRegionCollector {
    override fun collectFunctionRegions(irModuleFragment: IrModuleFragment): List<LLVMFileRegionInfo> =
            irModuleFragment.files.map {
                collectFunctionRegionsInFile(it)
            }

    private fun collectFunctionRegionsInFile(irFile: IrFile): LLVMFileRegionInfo {
        // TODO: add more declaration types.
        val regions = irFile.declarations.filterIsInstance<IrFunction>().map {
            collectRegions(irFile, it)
        }
        return LLVMFileRegionInfo(irFile, regions)
    }

    private fun collectRegions(irFile: IrFile, irFunction: IrFunction): LLVMFunctionRegions {
        val regionsCollector = IrFunctionRegionsCollector(irFile)
        regionsCollector.visitFunction(irFunction)
        return LLVMFunctionRegions(irFunction, regionsCollector.regions)
    }
}

private class IrFunctionRegionsCollector(val irFile: IrFile) : IrElementVisitorVoid {

    val regions = mutableMapOf<IrElement, LLVMRegion>()

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitBody(body: IrBody) = when (body) {
        is IrExpressionBody -> body.acceptChildrenVoid(this)
        is IrBlockBody -> body.acceptChildrenVoid(this)
        else -> error("Unexpected function body type: $body")
    }

    override fun visitCall(expression: IrCall) {
        regions[expression] = createRegionFromIr(expression)
        expression.acceptChildrenVoid(this)
    }

    override fun visitWhen(expression: IrWhen) {
        regions[expression] = createRegionFromIr(expression)
        expression.acceptChildrenVoid(this)
    }

    private fun createRegionFromIr(irElement: IrElement): LLVMRegion = LLVMRegion(
            irFile,
            irFile.fileEntry.line(irElement.startOffset),
            irFile.fileEntry.column(irElement.startOffset),
            irFile.fileEntry.line(irElement.endOffset),
            irFile.fileEntry.column(irElement.endOffset),
            LLVMRegionCounter()
        )
}