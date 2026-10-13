# Fails when a library could not be loaded by a device whose pages are PAGE
# bytes: when a segment of it that is loaded is aligned to less than that.
#
#   cmake -DLIBRARY=<file> -DPAGE=<bytes> [-DDISCARD=ON] -P check_page_size.cmake
#
# CMakeLists.txt runs it on the library it has just linked, so that an Android
# build with the alignment lost does not end with a library to ship. The
# option that sets the alignment is one line there, and nothing else would
# say that it had gone, or that a toolchain had stopped honouring it: the
# library loads as before on every device with pages of 4 KB, which is every
# device at hand.
#
# DISCARD has a library that is refused removed, and the build gives it. The
# link has succeeded by the time this runs, and ninja leaves the output of a
# step that failed where it is. Were the option then put back as it was, the
# command would again be the one ninja last saw succeed, with an output newer
# than everything it is made from: nothing to do, and the library that was
# refused is the one packed. Seen once, by doing just that. With the file
# gone the next build has it to link whatever else is the same.
#
# The file is read here, a few bytes at a time, and no tool is asked: which
# readelf an NDK brings, and where, is not something a build should hang on.
# shared/tests/page_size_check_test.py runs this on libraries made to pass it
# and to fail it.

if(NOT DEFINED LIBRARY OR NOT DEFINED PAGE)
    message(FATAL_ERROR "check_page_size.cmake needs -DLIBRARY=<file> and -DPAGE=<bytes>")
endif()
if(NOT EXISTS "${LIBRARY}" OR IS_DIRECTORY "${LIBRARY}")
    message(FATAL_ERROR "${LIBRARY}: no such file")
endif()

# Every refusal of a file that is there goes through here.
function(refuse why)
    if(DISCARD)
        file(REMOVE "${LIBRARY}")
        string(APPEND why "\nThe file has been removed, so that the next build links it again.")
    endif()
    message(FATAL_ERROR "${why}")
endfunction()

# The number in the `length` bytes at `offset`, lowest byte first.
function(number_at out offset length what)
    file(READ "${LIBRARY}" hex OFFSET ${offset} LIMIT ${length} HEX)
    string(LENGTH "${hex}" got)
    math(EXPR want "${length} * 2")
    if(NOT got EQUAL want)
        refuse("${LIBRARY}: the file ends inside ${what}")
    endif()
    set(digits "")
    foreach(i RANGE 1 ${length})
        math(EXPR at "(${length} - ${i}) * 2")
        string(SUBSTRING "${hex}" ${at} 2 byte)
        string(APPEND digits "${byte}")
    endforeach()
    # math() counts in 64 bits with a sign, and no offset or alignment of a
    # real file has the top bit set.
    if(length EQUAL 8 AND digits MATCHES "^[89a-fA-F]")
        refuse("${LIBRARY}: ${what} is 0x${digits}, which no library has")
    endif()
    math(EXPR value "0x${digits}")
    set(${out} "${value}" PARENT_SCOPE)
endfunction()

file(READ "${LIBRARY}" ident LIMIT 6 HEX)
if(NOT ident MATCHES "^7f454c46")
    refuse("${LIBRARY}: not an ELF file")
endif()
# Pages of 16 KB are a matter of 64-bit devices. A 32-bit library is said and
# let through, so that an ABI added to the build is not refused for it.
if(ident MATCHES "^7f454c4601")
    message(STATUS "${LIBRARY}: a 32-bit library, whose alignment is not held to ${PAGE}")
    return()
endif()
if(NOT ident STREQUAL "7f454c460201")
    refuse("${LIBRARY}: not a 64-bit little-endian ELF file, which is the one kind read here")
endif()

number_at(table 32 8 "its header")
number_at(entry 54 2 "its header")
number_at(count 56 2 "its header")

set(loaded 0)
set(short "")
if(count GREATER 0)
    math(EXPR last "${count} - 1")
    foreach(i RANGE 0 ${last})
        math(EXPR at "${table} + ${i} * ${entry}")
        number_at(type ${at} 4 "its program headers")
        # 1 is PT_LOAD: a part of the file the loader maps.
        if(type EQUAL 1)
            math(EXPR loaded "${loaded} + 1")
            math(EXPR at "${at} + 48")
            number_at(align ${at} 8 "its program headers")
            if(align LESS PAGE)
                math(EXPR shown "${align}" OUTPUT_FORMAT HEXADECIMAL)
                string(APPEND short "\n  LOAD segment ${loaded} (program header ${i}) is aligned to ${shown}")
            endif()
        endif()
    endforeach()
endif()

if(loaded EQUAL 0)
    refuse("${LIBRARY}: no LOAD segment, so not a library anything could load")
endif()
if(short)
    math(EXPR shown "${PAGE}" OUTPUT_FORMAT HEXADECIMAL)
    refuse("${LIBRARY} would not load on a device with pages of ${PAGE} bytes; every LOAD segment has to be aligned to ${shown} or more:${short}\nThe linker is told so by -Wl,-z,max-page-size=${PAGE}.")
endif()
message(STATUS "${LIBRARY}: ${loaded} LOAD segment(s), each aligned to ${PAGE} bytes or more")
