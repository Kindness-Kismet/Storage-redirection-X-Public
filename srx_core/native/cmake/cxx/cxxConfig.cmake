# 按 LSPlant 使用的版本导入官方 Prefab 包及其标准库模块配置。
if(TARGET cxx::cxx)
    return()
endif()

include(FetchContent)
FetchContent_Declare(srx_libcxx
    URL https://repo.maven.apache.org/maven2/org/lsposed/libcxx/libcxx/30.0.16248370/libcxx-30.0.16248370.aar
    URL_HASH SHA256=8bb6839964cdd5b814255c2674a9bb4a7ba5a6b2e15284c307694a571e8d5f7c
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(srx_libcxx)

set(cxx_module_dir "${srx_libcxx_SOURCE_DIR}/prefab/modules/cxx")
add_library(cxx::cxx STATIC IMPORTED GLOBAL)
set_target_properties(cxx::cxx PROPERTIES
    IMPORTED_LOCATION "${cxx_module_dir}/libs/android.${ANDROID_ABI}/libcxx.a"
    INTERFACE_INCLUDE_DIRECTORIES "${cxx_module_dir}/include"
)
include("${cxx_module_dir}/include/prefab/cmake-config.cmake")
