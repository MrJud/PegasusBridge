cmake_minimum_required(VERSION 3.22.1)
project("rahasher" C)

# rcheevos is vendored in rcheevos/, as plain files of this repository: upstream
# 12.5.0 with the local patches rcheevos-patches/ describes.
set(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rcheevos")

# One list of sources for both builds. shared/native/build.sh reads the same
# file for the desktop library, and reads it the same way: a line is a path
# from this folder, `#` starts a comment, and space counts for nothing. The
# list used to be whatever a glob over rhash/ found, so a file added upstream
# was in the Android library and not in the other.
#
# The comments are taken out of the text before it is made a list of lines.
# To CMake a `[` in a list opens a bracket that holds everything up to its
# `]` together as one item, so a comment with one in it took the lines after
# it into the comment, and the sources they named out of the library.
set(RAHASHER_LIST "${CMAKE_CURRENT_SOURCE_DIR}/rahasher.sources")
file(READ "${RAHASHER_LIST}" RAHASHER_TEXT)
string(REGEX REPLACE "#[^\n]*" "" RAHASHER_TEXT "${RAHASHER_TEXT}")
string(REGEX REPLACE "[ \t\r]" "" RAHASHER_TEXT "${RAHASHER_TEXT}")
string(REGEX REPLACE "\n+" ";" RAHASHER_LINES "${RAHASHER_TEXT}")
set(RAHASHER_SOURCES "")
foreach(line IN LISTS RAHASHER_LINES)
    if(NOT line STREQUAL "")
        if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${line}")
            message(FATAL_ERROR "rahasher.sources names ${line}, which is not there")
        endif()
        list(APPEND RAHASHER_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/${line}")
    endif()
endforeach()
if(NOT RAHASHER_SOURCES)
    message(FATAL_ERROR "rahasher.sources names no source")
endif()
# A source added to the list is a build to configure again.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${RAHASHER_LIST}")

# rahasher_jni.c is the one file with functions the JVM calls, the same file
# for the desktop library.
add_library(rahasher SHARED
    rahasher_jni.c
    ${RAHASHER_SOURCES}
)

# src/ is where rc_version.h is, which the JNI file reads the version from.
target_include_directories(rahasher PRIVATE
    "${RCHEEVOS_DIR}/include"
    "${RCHEEVOS_DIR}/src"
    "${RCHEEVOS_DIR}/src/rhash"
)

# The 3DS is the one console left out: hash_encrypted.c and aes.c are not on
# the list, and this is what keeps the rest from calling them.
target_compile_definitions(rahasher PRIVATE
    RC_HASH_NO_ENCRYPTED
)

# C11 with the GNU extensions, as build.sh says it (-std=gnu11), and not
# whatever the compiler of the day takes C to be. Hidden by default, so that
# this build's own functions stay out of the symbol table.
set_target_properties(rahasher PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
    C_VISIBILITY_PRESET hidden
)

target_compile_options(rahasher PRIVATE -O2 -Wall)

# jni.map lets the functions the JVM looks up by name out of the library and
# nothing else: rcheevos marks its own functions for export, and md5_init in
# the symbol table is a name another library of the app may have as well.
# Nothing else is linked. The library used to write to Android's log, and now
# hands what it has to say back to its caller.
set(RAHASHER_MAP "${CMAKE_CURRENT_SOURCE_DIR}/jni.map")
target_link_options(rahasher PRIVATE "-Wl,--version-script=${RAHASHER_MAP}")
set_property(TARGET rahasher APPEND PROPERTY LINK_DEPENDS "${RAHASHER_MAP}")
