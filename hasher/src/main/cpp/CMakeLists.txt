cmake_minimum_required(VERSION 3.22.1)
project("rahasher" C)

# ── rcheevos sources (git submodule at app/src/main/cpp/rcheevos/) ──
set(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rcheevos")

# Collect all rhash sources except hash_encrypted.c
file(GLOB RHASH_SOURCES "${RCHEEVOS_DIR}/src/rhash/*.c")
list(FILTER RHASH_SOURCES EXCLUDE REGEX "hash_encrypted\\.c$")

# Shared library for JNI
add_library(rahasher SHARED
    ra_hasher_jni.c
    ${RHASH_SOURCES}
    "${RCHEEVOS_DIR}/src/rc_compat.c"
)

target_include_directories(rahasher PRIVATE
    "${RCHEEVOS_DIR}/include"
)

target_compile_definitions(rahasher PRIVATE
    RC_HASH_NO_ENCRYPTED
)

target_compile_options(rahasher PRIVATE -O2 -Wall)

# Link Android log library for __android_log_print
find_library(log-lib log)
target_link_libraries(rahasher ${log-lib})
