cmake_minimum_required(VERSION 3.22)
project(meshwise_native CXX)

# Shared by the Android build (via the app module's externalNativeBuild) and the
# desktop host build used for tests and offscreen renders.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(FSV_CORE_SOURCES
  core/mapped_file.cpp
  core/zip.cpp
  core/model.cpp
  core/loaders.cpp
  core/load_stl.cpp
  core/load_obj.cpp
  core/load_ply.cpp
  core/load_off.cpp
  core/load_3mf.cpp
  core/load_fsm.cpp
  core/bvh.cpp
  core/analysis.cpp
  core/feature_finder.cpp
  core/print_prep.cpp
  core/print_estimate.cpp
  core/exporters.cpp
  core/camera.cpp
  core/shaders.cpp
  core/renderer.cpp
  core/engine.cpp
)

find_package(ZLIB REQUIRED)

add_library(fsvcore STATIC ${FSV_CORE_SOURCES})
target_include_directories(fsvcore PUBLIC core)
target_link_libraries(fsvcore PUBLIC ZLIB::ZLIB)
set_target_properties(fsvcore PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(fsvcore PRIVATE -Wall -Wextra -Wno-unused-parameter -ffunction-sections -fdata-sections)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
  target_compile_options(fsvcore PRIVATE -O3 -fno-math-errno)
endif()

if(ANDROID)
  find_library(GLES3_LIB GLESv3)
  find_library(LOG_LIB log)
  find_library(JNIGRAPHICS_LIB jnigraphics)
  target_link_libraries(fsvcore PUBLIC ${GLES3_LIB} ${LOG_LIB})
  target_compile_definitions(fsvcore PUBLIC FSV_ANDROID=1)

  add_library(meshwise SHARED jni/jni_core.cpp)
  target_link_libraries(meshwise PRIVATE fsvcore ${JNIGRAPHICS_LIB} android)
  target_link_options(meshwise PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
  set_target_properties(meshwise PROPERTIES CXX_VISIBILITY_PRESET hidden)

  # Optional CAD import (STEP/IGES/BREP) when OCCT static libraries exist for this ABI.
  set(FSV_OCCT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/occt/${ANDROID_ABI}" CACHE PATH "OCCT install prefix")
  if(EXISTS "${FSV_OCCT_ROOT}/OCCT_VERSION")
    add_subdirectory(cad)
  else()
    message(STATUS "OCCT not found for ${ANDROID_ABI} at ${FSV_OCCT_ROOT}: CAD import disabled")
  endif()
else()
  find_library(EGL_LIB EGL)
  find_library(GLES_LIB GLESv2)
  find_package(Threads REQUIRED)
  target_link_libraries(fsvcore PUBLIC ${GLES_LIB} Threads::Threads)

  add_executable(fsv_render host/render_main.cpp host/egl_context.cpp host/png.cpp)
  target_link_libraries(fsv_render PRIVATE fsvcore ${EGL_LIB})

  add_executable(fsv_tests tests/tests.cpp host/egl_context.cpp)
  target_link_libraries(fsv_tests PRIVATE fsvcore ${EGL_LIB})

  add_executable(fsv_bench host/bench.cpp)
  target_link_libraries(fsv_bench PRIVATE fsvcore)
  add_executable(fsv_estimate host/estimate_main.cpp)
  target_link_libraries(fsv_estimate PRIVATE fsvcore)

  # The JNI bridge built for the desktop JVM, used by the app's Robolectric tests.
  find_package(JNI)
  if(JNI_FOUND)
    add_library(meshwise_jvm SHARED jni/jni_core.cpp host/jni_stubs.cpp)
    target_include_directories(meshwise_jvm PRIVATE ${JNI_INCLUDE_DIRS} host/stubs)
    target_link_libraries(meshwise_jvm PRIVATE fsvcore)
    set_target_properties(meshwise_jvm PROPERTIES OUTPUT_NAME meshwise)
  endif()
endif()
