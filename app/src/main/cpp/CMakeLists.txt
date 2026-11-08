cmake_minimum_required(VERSION 3.22.1)

project(lfmomni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# llama.cpp (LFM3-Omni 対応版) を取得して静的リンクする
#   LFM3-Omni-2B の GGUF / mmproj は llama.cpp b11960 (v0.8.0) 以降で対応。
#   libmtmd が画像・音声・動画の前処理と推論を受け持つ。
# ---------------------------------------------------------------------------
set(LFM3_LLAMA_TAG "b11960" CACHE STRING "llama.cpp tag to build against")

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)

# ライブラリとしてだけ使うためツール類はすべて無効化し、ビルド時間を抑える
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD         ON  CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS       OFF CACHE BOOL "" FORCE)

# ARM64 CPU 推論: NEON + OpenMP。GPU バックエンドは今回ビルドしない
set(GGML_OPENMP              ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        llama
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LFM3_LLAMA_TAG}.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_DIR ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
)
FetchContent_MakeAvailable(llama)

message(STATUS "llama.cpp source: ${llama_SOURCE_DIR}")

# ---------------------------------------------------------------------------
# JNI ブリッジ
# ---------------------------------------------------------------------------
add_library(lfmomni SHARED lfm_omni_jni.cpp)

target_include_directories(lfmomni PRIVATE
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/ggml/include
        ${llama_SOURCE_DIR}/tools/mtmd
)

target_compile_options(lfmomni PRIVATE -O2 -fexceptions -Wno-unused-parameter)

target_link_libraries(lfmomni PRIVATE
        mtmd
        llama
        android
        log
)
