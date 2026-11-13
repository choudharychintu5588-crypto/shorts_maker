cmake_minimum_required(VERSION 3.18)
project(shortsmaker_native C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Always build the speech engine optimised, even in debug builds (an unoptimised build is ~10x slower).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # Redmi-class CPUs (Cortex-A75/A55) support these; the app checks /proc/cpuinfo before loading.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR}/ggml ggml)

add_library(whisper_cpp STATIC
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp)
target_include_directories(whisper_cpp PUBLIC
    ${WHISPER_DIR}/include ${WHISPER_DIR}/src ${WHISPER_DIR}/ggml/include)
target_compile_definitions(whisper_cpp PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper_cpp PRIVATE WHISPER_VERSION="1.9.5")
target_link_libraries(whisper_cpp PUBLIC ggml)

add_library(shortsnative_core STATIC whisper_core.cpp)
target_link_libraries(shortsnative_core PUBLIC whisper_cpp)

if (ANDROID)
    add_library(shortsmaker SHARED jni.cpp)
    find_library(LOG_LIB log)
    target_link_libraries(shortsmaker shortsnative_core ${LOG_LIB} android)
    target_link_options(shortsmaker PRIVATE -Wl,-z,max-page-size=16384)
else()
    # Host-side check (used only for testing on a normal computer)
    add_executable(core_test host_test.cpp)
    target_link_libraries(core_test shortsnative_core)
endif()
