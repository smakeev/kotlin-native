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

using namespace llvm;
using namespace llvm::coverage;

namespace llvm {
    DEFINE_SIMPLE_CONVERSION_FUNCTIONS(CounterMappingRegion, LLVMCounterMappingRegionRef)
}

extern "C" {

LLVMCounterMappingRegionRef
LLVMCounterMappingMakeRegion(int fileId, int lineStart, int columnStart, int lineEnd, int columnEnd) {
    return llvm::wrap(new CounterMappingRegion(Counter(), fileId, 0, lineStart, columnStart, lineEnd, columnEnd,
                                               CounterMappingRegion::RegionKind::CodeRegion));
}

}
