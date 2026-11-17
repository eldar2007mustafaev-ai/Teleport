cmake_minimum_required(VERSION 3.22.1)
project("so2")

add_library(
        so2
        SHARED
        so2_native.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        so2
        ${log-lib}
        ${android-lib}
)
