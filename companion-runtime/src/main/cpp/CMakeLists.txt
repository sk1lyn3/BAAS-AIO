cmake_minimum_required(VERSION 3.22.1)
project(baas_companion_capture)

add_library(baas_companion_capture SHARED capture_bridge.cpp preview.cpp)
# Debug APKs still need production-speed per-frame copies and PNG encoding.
target_compile_options(baas_companion_capture PRIVATE -O2)

target_link_libraries(
        baas_companion_capture
        android
        log
        mediandk
        z
        EGL
        GLESv2)
