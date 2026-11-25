cmake_minimum_required(VERSION 3.22.1)
project(slm_jni LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_compile_options(-O3 -march=armv8.2-a+dotprod+fp16)
include(FetchContent)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b4513
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama_cpp)

add_library(slm_jni SHARED llama_jni.cpp)
target_compile_features(slm_jni PRIVATE cxx_std_17)
target_link_libraries(slm_jni PRIVATE llama ggml android log)
