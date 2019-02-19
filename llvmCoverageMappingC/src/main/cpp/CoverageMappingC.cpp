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
#include <llvm/ADT/Triple.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/IR/Type.h>
#include <llvm/IR/DerivedTypes.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/Intrinsics.h>
#include <llvm/Support/FileSystem.h>
#include <llvm/Support/Path.h>
#include <string>
#include <vector>

using namespace llvm;
using namespace llvm::coverage;

const char *LLVMWriteCoverageRegionMapping(unsigned int *fileIdMapping, size_t fileIdMappingSize,
                                           struct Region **mappingRegions, size_t mappingRegionsSize) {

    std::vector<coverage::CounterMappingRegion> mrv;
    for (size_t i = 0; i < mappingRegionsSize; ++i) {
        auto codeRegion = coverage::CounterMappingRegion::RegionKind::CodeRegion;
        struct Region region = *mappingRegions[i];
        coverage::CounterMappingRegion counterMappingRegion(coverage::Counter(), region.fileId, 0, region.lineStart, region.columnStart, region.lineEnd, region.columnEnd, codeRegion);
        mrv.emplace_back(counterMappingRegion);
    }
    MutableArrayRef<llvm::coverage::CounterMappingRegion> mra(mrv);
    CoverageMappingWriter writer(ArrayRef<unsigned int>(fileIdMapping, fileIdMappingSize), None, mra);
    std::string CoverageMapping;
    raw_string_ostream OS(CoverageMapping);
    writer.write(OS);
    return CoverageMapping.c_str();
}

static StructType *getFunctionRecordTy(LLVMContext &Ctx) {
#define COVMAP_FUNC_RECORD(Type, LLVMType, Name, Init) LLVMType,
    Type *FunctionRecordTypes[] = {
#include "llvm/ProfileData/InstrProfData.inc"
    };
    StructType *FunctionRecordTy = StructType::get(Ctx, makeArrayRef(FunctionRecordTypes), /*isPacked=*/true);
    return FunctionRecordTy;
}

static llvm::Constant *addFunctionMappingRecord(llvm::LLVMContext &Ctx, StringRef NameValue, uint64_t FuncHash,
                                                const std::string &CoverageMapping) {
    llvm::StructType *FunctionRecordTy = getFunctionRecordTy(Ctx);

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

static std::string normalizeFilename(StringRef Filename) {
    llvm::SmallString<256> Path(Filename);
    llvm::sys::fs::make_absolute(Path);
    llvm::sys::path::remove_dots(Path, true);
    return Path.str().str();
}

static llvm::GlobalVariable *emitCoverageGlobal(
        llvm::LLVMContext &Ctx,
        llvm::Module &module,
        std::vector<llvm::Constant *> &FunctionRecords,
        llvm::SmallDenseMap<const char *, unsigned, 8> &FileEntries,
        std::string &RawCoverageMappings,
        llvm::StructType *FunctionRecordTy) {
    auto *Int32Ty = llvm::Type::getInt32Ty(Ctx);

    // Create the filenames and merge them with coverage mappings
    llvm::SmallVector<std::string, 16> FilenameStrs;
    llvm::SmallVector<StringRef, 16> FilenameRefs;
    FilenameStrs.resize(FileEntries.size());
    FilenameRefs.resize(FileEntries.size());
    for (const auto &Entry : FileEntries) {
        auto I = Entry.second;
        FilenameStrs[I] = normalizeFilename(Entry.first);
        FilenameRefs[I] = FilenameStrs[I];
    }

    std::string FilenamesAndCoverageMappings;
    llvm::raw_string_ostream OS(FilenamesAndCoverageMappings);
    CoverageFilenamesSectionWriter(FilenameRefs).write(OS);
    OS << RawCoverageMappings;
    size_t CoverageMappingSize = RawCoverageMappings.size();
    size_t FilenamesSize = OS.str().size() - CoverageMappingSize;
    // Append extra zeroes if necessary to ensure that the size of the filenames
    // and coverage mappings is a multiple of 8.
    if (size_t Rem = OS.str().size() % 8) {
        CoverageMappingSize += 8 - Rem;
        for (size_t I = 0, S = 8 - Rem; I < S; ++I)
            OS << '\0';
    }
    auto *FilenamesAndMappingsVal =
            llvm::ConstantDataArray::getString(Ctx, OS.str(), false);

    // Create the deferred function records array
    auto RecordsTy =
            llvm::ArrayType::get(FunctionRecordTy, FunctionRecords.size());
    auto RecordsVal = llvm::ConstantArray::get(RecordsTy, FunctionRecords);

    llvm::Type *CovDataHeaderTypes[] = {
#define COVMAP_HEADER(Type, LLVMType, Name, Init) LLVMType,

#include "llvm/ProfileData/InstrProfData.inc"
    };
    auto CovDataHeaderTy =
            llvm::StructType::get(Ctx, makeArrayRef(CovDataHeaderTypes));
    llvm::Constant *CovDataHeaderVals[] = {
#define COVMAP_HEADER(Type, LLVMType, Name, Init) Init,

#include "llvm/ProfileData/InstrProfData.inc"
    };
    auto CovDataHeaderVal = llvm::ConstantStruct::get(
            CovDataHeaderTy, makeArrayRef(CovDataHeaderVals));

    // Create the coverage data record
    llvm::Type *CovDataTypes[] = {CovDataHeaderTy, RecordsTy,
                                  FilenamesAndMappingsVal->getType()};
    auto CovDataTy = llvm::StructType::get(Ctx, makeArrayRef(CovDataTypes));
    llvm::Constant *TUDataVals[] = {CovDataHeaderVal, RecordsVal,
                                    FilenamesAndMappingsVal};
    auto CovDataVal =
            llvm::ConstantStruct::get(CovDataTy, makeArrayRef(TUDataVals));
    auto CovData = new llvm::GlobalVariable(
            module, CovDataTy, true, llvm::GlobalValue::InternalLinkage,
            CovDataVal, llvm::getCoverageMappingVarName());


    return CovData;
}

// TODO: Ugly as the ugliest place in hell.
LLVMValueRef LLVMCoverageEmit(
        LLVMContextRef context, LLVMModuleRef moduleRef,
        LLVMValueRef *records, size_t recordsSize,
        const char **filenames, int *fileIds, size_t filenamesSize,
        const char **covMappings, size_t covMappingsSize) {
    LLVMContext &Ctx = *unwrap(context);
    Module &module = *unwrap(moduleRef);

    std::vector<Constant *> FunctionRecords;
    for (size_t i = 0; i < recordsSize; ++i) {
        auto *x = dyn_cast_or_null<Constant>(unwrap(records[i]));
        FunctionRecords.push_back(x);
    }
    SmallDenseMap<const char *, unsigned, 8> FileEntries;
    for (size_t i = 0; i < filenamesSize; ++i) {
        FileEntries.insert(std::make_pair(filenames[i], fileIds[i]));
    }
    std::vector<std::string> CoverageMappings;
    for (size_t i = 0; i < covMappingsSize; ++i) {
        CoverageMappings.emplace_back(covMappings[i]);
    }
    StructType *FunctionRecordTy = getFunctionRecordTy(Ctx);
    std::string RawCoverageMappings = llvm::join(CoverageMappings.begin(), CoverageMappings.end(), "");
    GlobalVariable *coverageGlobal = emitCoverageGlobal(
            Ctx,
            module,
            FunctionRecords,
            FileEntries,
            RawCoverageMappings,
            FunctionRecordTy
    );
    const std::string &section = getInstrProfSectionName(IPSK_covmap,
                                                         Triple(module.getTargetTriple()).getObjectFormat());
    coverageGlobal->setSection(section);
    coverageGlobal->setAlignment(8);
    return wrap(coverageGlobal);
}

void LLVMCoverageAddFunctionNamesGlobal(LLVMContextRef context, LLVMModuleRef moduleRef,
                                        LLVMValueRef *functionNames, size_t functionNamesSize) {
    LLVMContext &Ctx = *llvm::unwrap(context);
    Module &module = *llvm::unwrap(moduleRef);

    std::vector<Constant *> FunctionNames;
    for (size_t i = 0; i < functionNamesSize; ++i) {
        FunctionNames.push_back(llvm::dyn_cast_or_null<Constant>(llvm::unwrap(functionNames[i])));
    }

    auto NamesArrTy = llvm::ArrayType::get(llvm::Type::getInt8PtrTy(Ctx), functionNamesSize);
    auto NamesArrVal = llvm::ConstantArray::get(NamesArrTy, FunctionNames);
    // This variable will *NOT* be emitted to the object file. It is used
    // to pass the list of names referenced to codegen.
    new llvm::GlobalVariable(module, NamesArrTy, true,
                             llvm::GlobalValue::InternalLinkage, NamesArrVal,
                             llvm::getCoverageUnusedNamesVarName());
}

LLVMValueRef LLVMInstrProfIncrement(LLVMModuleRef moduleRef) {
    Module &module = *unwrap(moduleRef);
    return wrap(Intrinsic::getDeclaration(&module, Intrinsic::instrprof_increment, None));
}

const char *LLVMGetPGOFunctionName(LLVMValueRef llvmFunction) {
    auto *fnPtr = cast<Function>(unwrap(llvmFunction));
    return getPGOFuncName(*fnPtr).c_str();
}

LLVMValueRef LLVMCreatePGOFunctionNameVar(LLVMValueRef llvmFunction, const char *pgoFunctionName) {
    auto *fnPtr = cast<llvm::Function>(unwrap(llvmFunction));
    return wrap(createPGOFuncNameVar(*fnPtr, pgoFunctionName));
}

