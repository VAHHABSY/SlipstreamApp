cmake_minimum_required(VERSION 3.10.2)
project("native_runner")

add_library(native_runner SHARED native_runner.cpp)

find_library(log-lib log)
target_link_libraries(native_runner ${log-lib})