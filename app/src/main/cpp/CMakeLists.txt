cmake_minimum_required(VERSION 3.22.1)
project(localcanvas_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LOCALCANVAS_HAS_SDCPP 0)
option(LOCALCANVAS_REQUIRE_SDCPP "Fail configuration when the real engine is absent" OFF)

# Android Gradle's nested CMake configure must receive the SPIR-V package path
# explicitly; relying only on the parent shell's CMAKE_PREFIX_PATH is not
# reliable when stable-diffusion.cpp is added as a subdirectory.
if(DEFINED ENV{SPIRV_HEADERS_DIR} AND EXISTS "$ENV{SPIRV_HEADERS_DIR}/SPIRV-HeadersConfig.cmake")
    set("SPIRV-Headers_DIR" "$ENV{SPIRV_HEADERS_DIR}" CACHE PATH "Pinned SPIRV-Headers package" FORCE)
endif()

add_library(localcanvas_native SHARED localcanvas_jni.cpp)

if(SDCPP_DIR AND EXISTS "${SDCPP_DIR}/CMakeLists.txt")
    # stable-diffusion.cpp calls find_package(SPIRV-Headers CONFIG REQUIRED)
    # from a nested directory. Android Gradle's CMake invocation does not
    # reliably inherit the shell's package path, so provide a deterministic
    # config package in this configure tree. The shader headers are vendored
    # by CI into the engine's ggml/src directory.
    set(LOCALCANVAS_SPIRV_PACKAGE "${CMAKE_BINARY_DIR}/localcanvas-spirv-package")
    file(MAKE_DIRECTORY "${LOCALCANVAS_SPIRV_PACKAGE}")
    file(WRITE "${LOCALCANVAS_SPIRV_PACKAGE}/SPIRV-HeadersConfig.cmake"
        "if(NOT TARGET SPIRV-Headers)\n"
        "  add_library(SPIRV-Headers INTERFACE)\n"
        "  add_library(SPIRV-Headers::SPIRV-Headers ALIAS SPIRV-Headers)\n"
        "  set_target_properties(SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${SDCPP_DIR}/ggml/src\")\n"
        "endif()\n")
    list(PREPEND CMAKE_PREFIX_PATH "${LOCALCANVAS_SPIRV_PACKAGE}")
    set(LOCALCANVAS_ENGINE_VULKAN_CMAKE "${SDCPP_DIR}/ggml/src/ggml-vulkan/CMakeLists.txt")
    if(NOT EXISTS "${LOCALCANVAS_ENGINE_VULKAN_CMAKE}")
        message(FATAL_ERROR "Pinned stable-diffusion.cpp Vulkan CMake file is missing")
    endif()
    file(READ "${LOCALCANVAS_ENGINE_VULKAN_CMAKE}" LOCALCANVAS_ENGINE_VULKAN_TEXT)
    string(FIND "${LOCALCANVAS_ENGINE_VULKAN_TEXT}" "LOCALCANVAS_SPIRV_INJECTED" LOCALCANVAS_INJECTED_AT)
    if(LOCALCANVAS_INJECTED_AT EQUAL -1)
        set(LOCALCANVAS_SPIRV_INCLUDE_LINE
            "include(${LOCALCANVAS_SPIRV_PACKAGE}/SPIRV-HeadersConfig.cmake)\n")
        string(REPLACE "find_package(SPIRV-Headers CONFIG REQUIRED)"
            "# LOCALCANVAS_SPIRV_INJECTED\n${LOCALCANVAS_SPIRV_INCLUDE_LINE}"
            LOCALCANVAS_ENGINE_VULKAN_TEXT
            "${LOCALCANVAS_ENGINE_VULKAN_TEXT}")
        file(WRITE "${LOCALCANVAS_ENGINE_VULKAN_CMAKE}" "${LOCALCANVAS_ENGINE_VULKAN_TEXT}")
    endif()

    set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(SD_VULKAN ON CACHE BOOL "" FORCE)
    set(SD_OPENCL OFF CACHE BOOL "" FORCE)
    add_subdirectory("${SDCPP_DIR}" "${CMAKE_BINARY_DIR}/stable-diffusion.cpp")
    target_include_directories(localcanvas_native PRIVATE "${SDCPP_DIR}/include")
    target_compile_definitions(localcanvas_native PRIVATE LOCALCANVAS_HAS_SDCPP=1)
    target_link_libraries(localcanvas_native PRIVATE stable-diffusion)
    set(LOCALCANVAS_HAS_SDCPP 1)
endif()

if(LOCALCANVAS_REQUIRE_SDCPP AND NOT LOCALCANVAS_HAS_SDCPP)
    message(FATAL_ERROR "LocalCanvas requires stable-diffusion.cpp; SDCPP_DIR was not supplied")
endif()

target_compile_definitions(localcanvas_native PRIVATE LOCALCANVAS_HAS_SDCPP=${LOCALCANVAS_HAS_SDCPP})

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)
target_link_libraries(localcanvas_native PRIVATE ${log-lib} ${android-lib} ${jnigraphics-lib} ${vulkan-lib})
