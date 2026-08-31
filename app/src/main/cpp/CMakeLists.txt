cmake_minimum_required(VERSION 3.22.1)

project("voiceassistant")

add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libespeak-ng.so)

add_library(voiceassistant SHARED espeak_jni.c)

target_include_directories(voiceassistant PRIVATE include)

target_link_libraries(voiceassistant espeak-ng log)
