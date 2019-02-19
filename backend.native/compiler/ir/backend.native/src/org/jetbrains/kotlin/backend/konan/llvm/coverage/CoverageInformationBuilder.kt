package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

interface Region {
    val file: IrFile
    val startLine: Int
    val startColumn: Int
    val endLine: Int
    val endColumn: Int
}

interface FunctionRegions {
    val function: IrFunction
    val regions: Map<IrElement, Region>
}

interface FileRegionInfo {
    val file: IrFile
    val functions: List<FunctionRegions>
}

interface CoverageRegionCollector {
    fun collectFunctionRegions(irModuleFragment: IrModuleFragment): List<FileRegionInfo>
}

interface CoverageInstrumenter : IrElementVisitorVoid {

}
