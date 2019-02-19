package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMConstBitCast
import llvm.LLVMCreatePGOFunctionNameVar
import llvm.LLVMInstrProfIncrement
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

internal class LLVMCoverageInstrumenter(
        override val context: Context,
        val functionRegions: LLVMFunctionRegions,
        val callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit
) : CoverageInstrumenter, ContextUtils {

    private val functionNameGlobal = createFunctionNameGlobal(functionRegions.function)

    // TODO: implement hash
    private val functionHash = Int64(100500).llvm

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    fun instrumentIrElement(element: IrElement) {
        functionRegions.regions[element]?.let {
            placeRegionIncrement(it)
        }
    }

    private fun placeRegionIncrement(region: LLVMRegion) {
        val numberOfRegions = Int32(functionRegions.regions.size).llvm
        val regionNum = Int32(functionRegions.regionEnumeration.getValue(region)).llvm
        callSitePlacer(LLVMInstrProfIncrement(context.llvmModule)!!, listOf(functionNameGlobal, functionHash, numberOfRegions, regionNum))
    }

    private fun createFunctionNameGlobal(function: IrFunction): LLVMValueRef {
        val name = function.symbolName
        val x = LLVMCreatePGOFunctionNameVar(function.llvmFunction, name)!!
        return LLVMConstBitCast(x, int8TypePtr)!!
    }
}