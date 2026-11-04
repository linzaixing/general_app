cmake_minimum_required(VERSION 3.22.1)
project(panostitch CXX)

add_library(panostitch SHARED pano_stitch.cpp)
target_compile_options(panostitch PRIVATE -O3 -fno-exceptions -fno-rtti)
