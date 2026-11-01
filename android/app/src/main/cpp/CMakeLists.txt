cmake_minimum_required(VERSION 3.22)
project(taildeckterm C)

# Prebuilt by term/build-ghostty.sh.
set(GHOSTTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ghostty)
if(NOT EXISTS ${GHOSTTY_DIR}/lib/${ANDROID_ABI}/libghostty-vt.a)
  message(FATAL_ERROR "libghostty-vt missing for ${ANDROID_ABI}; run term/build-ghostty.sh")
endif()

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES IMPORTED_LOCATION ${GHOSTTY_DIR}/lib/${ANDROID_ABI}/libghostty-vt.a)

add_library(taildeckterm SHARED term_jni.c)
target_include_directories(taildeckterm PRIVATE ${GHOSTTY_DIR}/include)
target_compile_options(taildeckterm PRIVATE -Wall -Wextra -O2)
target_link_libraries(taildeckterm ghostty-vt log)
target_link_options(taildeckterm PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
