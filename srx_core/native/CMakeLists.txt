cmake_minimum_required(VERSION 3.28)
project(srx_lsplant_bridge)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(cxx_DIR "${CMAKE_CURRENT_LIST_DIR}/cmake/cxx")
find_package(cxx REQUIRED CONFIG)
add_compile_options($<$<COMPILE_LANGUAGE:CXX>:-nostdinc++>)

if (NOT DEFINED SRX_INLINE_HOOK_INCLUDE_DIR)
    message(FATAL_ERROR "SRX_INLINE_HOOK_INCLUDE_DIR is required")
endif()

set(PHMAP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PHMAP_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LSPLANT_STANDALONE ON CACHE BOOL "" FORCE)
set(DEX_BUILDER_BUILD_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../third_party/lsplant/lsplant/src/main/jni ${CMAKE_BINARY_DIR}/lsplant)

add_library(srx_lsplant_bridge STATIC
    srx_lsplant_bridge.cpp
)
set_target_properties(srx_lsplant_bridge PROPERTIES CXX_SCAN_FOR_MODULES ON)
target_include_directories(srx_lsplant_bridge PRIVATE
    ${SRX_INLINE_HOOK_INCLUDE_DIR}
)
target_link_libraries(srx_lsplant_bridge PRIVATE lsplant_static cxx::cxx log)

# Rust 链接实际生成的静态库，包含 Prefab 为 C++23 构建的模块对象。
file(GENERATE OUTPUT "${CMAKE_BINARY_DIR}/srx_native_libraries.txt" CONTENT
"$<TARGET_FILE:srx_lsplant_bridge>\n$<TARGET_FILE:lsplant_static>\n$<TARGET_FILE:dex_builder_static>\n$<TARGET_FILE:_prefab_cxx_std23_OFF>\n$<TARGET_FILE:cxx::cxx>\n")
