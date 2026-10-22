cmake_minimum_required(VERSION 3.22.1)

project(node_bridge LANGUAGES CXX)

find_library(log-lib log REQUIRED)

# --- Node.js Mobile Bridge ---
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

add_library(node_bridge SHARED node_bridge.cpp)
target_include_directories(node_bridge PRIVATE "${CMAKE_CURRENT_LIST_DIR}/include/node")
target_link_libraries(node_bridge PRIVATE libnode ${log-lib})
