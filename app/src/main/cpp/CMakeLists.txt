cmake_minimum_required(VERSION 3.10)
project(native_runner)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native_runner SHARED native_runner.cpp)
find_library(log-lib log)
target_link_libraries(native_runner ${log-lib} dl)