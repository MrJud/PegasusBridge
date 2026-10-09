#!/usr/bin/env python3
"""Fails when the librahasher committed in native/out is not the library its
manifest describes, or was compiled from files that have changed since.

    ./tests/native_manifest_check.py [manifest]     (default: native/out's)

The library is a binary in git, and the tests load it as it is: nothing
compiles it on the way. So a change to the C beside it that nobody built is a
suite that goes on passing on the old code, and a library built from a tree
that was never committed is one nobody can make again. native/build.sh writes
a manifest with every library: the SHA-256 of the library, and of each file of
the repository it was compiled from, which is the sources, the headers the
compiler found them to include, the list of sources, the linker's list of what
to export and build.sh itself, where the flags are. This script works the same
sums out again and names every one that no longer holds. It compiles nothing,
so it runs wherever python3 does.

A sum can only be held against a file the manifest names. So the manifest must
name every source on the list of sources, which is read here as build.sh reads
it, and one C file more, the one with the functions the JVM calls, which the
build compiles beside them and the list does not name: a source with no line
could change for ever and nothing here would say so. The headers cannot be
asked for by name in the same way, since only the compiler knows which ones a
source includes.

What it cannot see is the compiler. Two compilers make two libraries from the
same files, and both satisfy their own manifest; the manifest says which one
it was, and that line is printed and not checked.

The paths in a manifest start at the top of the repository. The library is
looked for beside the manifest.
"""

import hashlib
import sys
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent
REPOSITORY = SHARED.parent
DEFAULT = SHARED / "native" / "out" / "librahasher.manifest"

# A manifest without these was not written by build.sh, whatever else it says:
# they are in every one, on every system.
CPP = "hasher/src/main/cpp"
SOURCE_LIST = f"{CPP}/rahasher.sources"
ALWAYS_LISTED = ("shared/native/build.sh", SOURCE_LIST)

REMEDY = ("run native/build.sh with JAVA_HOME set to a JDK, and commit the library "
          "and the manifest it writes in native/out together")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_manifest(path):
    """(libraries, inputs, notes): the first two as lists of (sum, name), the
    third the compiler and flags lines by their first word. A line this does
    not know is an error and not something to pass over: a manifest read only
    in part is checked only in part."""
    libraries, inputs, notes = [], [], {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        word, _, rest = line.partition(" ")
        if word in ("library", "input"):
            digest, _, name = rest.partition(" ")
            if len(digest) != 64 or not name:
                raise ValueError(f"line {number} is not `{word} <sha256> <path>`: {line!r}")
            (libraries if word == "library" else inputs).append((digest, name))
        elif word in ("compiler", "flags"):
            notes[word] = rest
        else:
            raise ValueError(f"line {number} begins with {word!r}, which is no kind of line "
                             "build.sh writes")
    return libraries, inputs, notes


def listed_sources(repository):
    """The sources rahasher.sources names, as paths from the top of the
    repository. The rule is build.sh's: a `#` starts a comment, a line is a
    path from the folder the list is in, and space counts for nothing."""
    names = []
    for line in (repository / SOURCE_LIST).read_text(encoding="utf-8").splitlines():
        name = "".join(line.partition("#")[0].split())
        if name:
            names.append(f"{CPP}/{name}")
    return names


def check(manifest, repository=REPOSITORY):
    """The list of what is wrong, empty when nothing is, and the notes."""
    if not manifest.is_file():
        return [f"there is no manifest at {manifest}"], {}
    try:
        libraries, inputs, notes = read_manifest(manifest)
    except (ValueError, UnicodeDecodeError) as error:
        return [f"{manifest} cannot be read as a manifest: {error}"], {}

    wrong = []
    if len(libraries) != 1:
        wrong.append(f"the manifest names {len(libraries)} libraries where it must name one")
    for digest, name in libraries:
        library = manifest.parent / name
        if not library.is_file():
            wrong.append(f"{name} is not beside the manifest")
        elif sha256(library) != digest:
            wrong.append(f"{name} is not the library this manifest was written for")

    listed = {name for _, name in inputs}
    for name in ALWAYS_LISTED:
        if name not in listed:
            wrong.append(f"the manifest does not list {name}, which every build reads")
    for digest, name in inputs:
        source = repository / name
        if not source.is_file():
            wrong.append(f"{name} was compiled into the library and is no longer there")
        elif sha256(source) != digest:
            wrong.append(f"{name} has changed since the library was compiled")

    # What the manifest ought to name and does not. The list is read as it is
    # now: if it has changed since the build, that is said above already, and
    # a name added to it since is one more thing the library lacks.
    try:
        sources = listed_sources(repository)
    except (OSError, UnicodeDecodeError):
        sources = []    # not there or not text, which the loop above has said
    for name in sources:
        if name not in listed:
            wrong.append(f"{SOURCE_LIST} names {name[len(CPP) + 1:]}, and the manifest has no "
                         "line for it: nothing holds the library to that source")
    # The file with the functions the JVM calls is on no list, and is asked for
    # by what it is, not by where: a C file the list does not name.
    if not any(name.endswith(".c") and name not in sources for name in listed):
        wrong.append(f"the manifest lists no C file but those {SOURCE_LIST} names. The one with "
                     "the functions the JVM calls is compiled in with them, and nothing holds "
                     "the library to it")
    return wrong, {**notes, "inputs": len(inputs),
                   "library": libraries[0][1] if libraries else "?"}


def main(argv, repository=REPOSITORY):
    if len(argv) > 2 or (len(argv) == 2 and argv[1].startswith("-")):
        print("usage: native_manifest_check.py [manifest]", file=sys.stderr)
        return 2
    manifest = Path(argv[1]).resolve() if len(argv) == 2 else DEFAULT

    wrong, notes = check(manifest, repository)
    if wrong:
        print(f"native_manifest_check: {len(wrong)} thing(s) wrong with {manifest}:")
        for line in wrong:
            print(f"  {line}")
        print(f"To put it right: {REMEDY}.")
        return 1
    print(f"native_manifest_check: {notes['library']} is the library its manifest describes, "
          f"and the {notes['inputs']} files it was compiled from are unchanged "
          f"(compiler: {notes.get('compiler', 'not said')})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
