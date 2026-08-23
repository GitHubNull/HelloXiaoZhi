# HelloXiaoZhi Native 层构建脚本
# 用途：编译 libopus（静态库）+ opus_jni（JNI 封装动态库）
#
# Opus 源码获取策略：
# 1. 优先使用本地源码：手动下载 opus-1.5.2.tar.gz 并解压到 src/main/cpp/opus/ 目录
#    （国内网络无法访问 GitHub 时推荐此方式）
# 2. 本地不存在时，构建期通过 FetchContent 从 GitHub 拉取
#    （FetchContent_MakeAvailable 内部会 add_subdirectory，勿再重复添加）
cmake_minimum_required(VERSION 3.22)

project(opus_jni C)

# opus 编译选项：静态库、跳过测试与命令行程序。
# 必须在 opus 进入构建（add_subdirectory / FetchContent）之前设置。
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)

set(OPUS_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/opus)

if(EXISTS ${OPUS_ROOT}/CMakeLists.txt)
    # 本地源码：手动 add_subdirectory
    add_subdirectory(${OPUS_ROOT} opus-build)
else()
    # 远程拉取：FetchContent_MakeAvailable 已完成 add_subdirectory，
    # 源码目录在 ${opus_SOURCE_DIR}
    include(FetchContent)
    FetchContent_Declare(opus
        URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz)
    FetchContent_MakeAvailable(opus)
    set(OPUS_ROOT ${opus_SOURCE_DIR})
endif()

add_library(opus_jni SHARED opus_jni.c)
target_include_directories(opus_jni PRIVATE ${OPUS_ROOT}/include)
target_link_libraries(opus_jni opus)
