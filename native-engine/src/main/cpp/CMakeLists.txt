cmake_minimum_required(VERSION 3.22.1)
project("pocketserver_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enforce 16 KB Page Alignment linker flags for Android 15/16 compatibility
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

add_library(pocketserver_native SHARED
    native_engine.cpp
)

find_library(log-lib log)

target_link_libraries(pocketserver_native
    ${log-lib}
)
