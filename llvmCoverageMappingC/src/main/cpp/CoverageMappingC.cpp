/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <CoverageMappingC.h>

#include "CoverageMappingC.h"

#include <llvm/ProfileData/Coverage/CoverageMapping.h>
#include <llvm/ProfileData/Coverage/CoverageMappingWriter.h>
#include <llvm/IR/GlobalVariable.h>
#include <llvm/Support/raw_ostream.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Type.h>
#include <llvm/IR/DerivedTypes.h>
#include <llvm/IR/Constants.h>
#include <string>
#include <vector>

using namespace llvm;
using namespace llvm::coverage;

namespace llvm {
    DEFINE_SIMPLE_CONVERSION_FUNCTIONS(coverage::CounterMappingRegion, LLVMCounterMappingRegionRef)
}

LLVMCounterMappingRegionRef
LLVMCounterMappingMakeRegion(int fileId, int lineStart, int columnStart, int lineEnd, int columnEnd) {
    auto regionKind = llvm::coverage::CounterMappingRegion::RegionKind::CodeRegion;
    const auto &counter = llvm::coverage::Counter();
    return llvm::wrap(new llvm::coverage::CounterMappingRegion(counter, fileId, 0, lineStart, columnStart, lineEnd, columnEnd, regionKind));
}

const char *LLVMWriteCoverageRegionMapping(unsigned int *fileIdMapping, size_t fileIdMappingSize,
                                           LLVMCounterMappingRegionRef *mappingRegions, size_t mappingRegionsSize) {

    std::vector<coverage::CounterMappingRegion> mrv;
    for (size_t i = 0; i < mappingRegionsSize; ++i) {
        mrv.emplace_back(*(llvm::unwrap(mappingRegions[i])));
    }

    MutableArrayRef<llvm::coverage::CounterMappingRegion> mra(mrv);
    CoverageMappingWriter writer(ArrayRef<unsigned int>(fileIdMapping, fileIdMappingSize), None, mra);
    std::string CoverageMapping;
    llvm::raw_string_ostream OS(CoverageMapping);
    writer.write(OS);
    dbgs() << "Coverage mapping: " << CoverageMapping << "\n";
    return CoverageMapping.c_str();
}

static llvm::Constant * addFunctionMappingRecord(llvm::LLVMContext &Ctx, StringRef NameValue, uint64_t FuncHash, const std::string &CoverageMapping) {
#define COVMAP_FUNC_RECORD(Type, LLVMType, Name, Init) LLVMType,
    llvm::Type *FunctionRecordTypes[] = {
#include "llvm/ProfileData/InstrProfData.inc"
    };
    llvm::StructType* FunctionRecordTy = llvm::StructType::get(Ctx, makeArrayRef(FunctionRecordTypes), /*isPacked=*/true);

#define COVMAP_FUNC_RECORD(Type, LLVMType, Name, Init) Init,
    llvm::Constant *FunctionRecordVals[] = {
#include "llvm/ProfileData/InstrProfData.inc"
    };
    return llvm::ConstantStruct::get(FunctionRecordTy, makeArrayRef(FunctionRecordVals));
}

LLVMValueRef
LLVMAddFunctionMappingRecord(LLVMContextRef context, const char *name, uint64_t hash, const char *coverageMapping) {
    return llvm::wrap(addFunctionMappingRecord(*llvm::unwrap(context), name, hash, coverageMapping));
}




