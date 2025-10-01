cmake_minimum_required(VERSION 3.10)

project(whisper_android_local)

# Standards
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Chemins locaux (OpenEER) ---
set(JNI_ROOT         ${CMAKE_SOURCE_DIR}/..)          # .../src/main/jni
set(WHISPER_INC_DIR  ${JNI_ROOT}/include)
set(WHISPER_SRC_DIR  ${JNI_ROOT}/src)
set(GGML_ROOT        ${JNI_ROOT}/ggml)

# --- ggml local ---
add_subdirectory(${GGML_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/ggml_build)

# --- Sources natives ---
set(SOURCE_FILES
    ${WHISPER_SRC_DIR}/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

# --- Bibliothèque principale ---
add_library(whisper SHARED ${SOURCE_FILES})

target_include_directories(whisper PRIVATE
    ${WHISPER_INC_DIR}
    ${WHISPER_SRC_DIR}
    ${GGML_ROOT}/include
    ${GGML_ROOT}/src
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(whisper PRIVATE
    ${LOG_LIB}
    ${ANDROID_LIB}
    ggml
)

# Définitions requises par whisper.cpp (évitent l'erreur WHISPER_VERSION)
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE
    WHISPER_VERSION="local"
    GGML_VERSION="local"
    GGML_COMMIT="unknown"
)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
    # target_link_options(whisper PRIVATE -Wl,--gc-sections)
endif ()

# --- Variante optimisée arm64 (fp16) ---
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_library(whisper_v8fp16_va SHARED ${SOURCE_FILES})
    target_include_directories(whisper_v8fp16_va PRIVATE
        ${WHISPER_INC_DIR} ${WHISPER_SRC_DIR} ${GGML_ROOT}/include ${GGML_ROOT}/src
    )
    target_link_libraries(whisper_v8fp16_va PRIVATE
        ${LOG_LIB} ${ANDROID_LIB} ggml
    )
    target_compile_definitions(whisper_v8fp16_va PUBLIC GGML_USE_CPU)
    target_compile_definitions(whisper_v8fp16_va PRIVATE
        WHISPER_VERSION="local"
        GGML_VERSION="local"
        GGML_COMMIT="unknown"
    )
    target_compile_options(whisper_v8fp16_va PRIVATE -march=armv8.2-a+fp16)
endif ()
