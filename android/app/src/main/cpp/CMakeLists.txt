cmake_minimum_required(VERSION 3.22.1)
project(panostitch CXX)

add_library(panostitch SHARED pano_stitch.cpp)
target_compile_options(panostitch PRIVATE -O3 -fno-exceptions -fno-rtti)
# Android 15+ 的 16 KB 页设备要求 ELF LOAD 段按 16 KB 对齐，否则加载失败
target_link_options(panostitch PRIVATE -Wl,-z,max-page-size=16384)
