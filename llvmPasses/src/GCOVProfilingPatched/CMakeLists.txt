cmake_minimum_required(VERSION 3.10)
project(GCOVProfilingPatched)

set(CMAKE_CXX_STANDARD 14)

# TODO: Check KONAN_DATA_DIR
set(LLVM_ROOT "$ENV{HOME}/.konan/dependencies/clang-llvm-6.0.1-darwin-macos")

list(APPEND CMAKE_PREFIX_PATH "${LLVM_ROOT}/lib/cmake/llvm")
find_package(LLVM REQUIRED CONFIG)

list(APPEND CMAKE_MODULE_PATH "${LLVM_CMAKE_DIR}")
include(HandleLLVMOptions)
include(AddLLVM)

add_definitions(${LLVM_DEFINITIONS})
include_directories(${LLVM_INCLUDE_DIRS})

include_directories(${CMAKE_SOURCE_DIR}/include)

add_llvm_loadable_module(GCOVProfilingPatched GCOVProfilingPatched.cpp)