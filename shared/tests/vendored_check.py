#!/usr/bin/env python3
"""Fails when the rcheevos vendored beside the hasher is not the upstream tree
its manifest names with the local patches applied to it, and nothing else; and
when the libchdr vendored beside it is not the files its manifest names of its
upstream tree, as upstream has them.

    ./tests/vendored_check.py [repository]     (default: the one this file is in)

The hasher is compiled from a copy of rcheevos kept in this repository, with a
few patches of ours on it. Which upstream tree the copy is, is written down in
hasher/src/main/cpp/rcheevos.version: the release, the commit, and for every
file of that commit's tree the id git gives its content there, so that the
list can be held against upstream's own (`git ls-tree -r <commit>`) without
fetching a file. The patches are the files of rcheevos-patches/, each a diff
of one upstream file. This script holds the copy to those two, and names
every way it finds them apart:

  - a file of upstream's tree that is not in git here, or one in git here
    that upstream does not have. The folder carries upstream's own .gitignore,
    which hides *.map, *.so, *.out, *.hex and more: a plain `git add` of a new
    tree leaves such a file out without a word. include/module.modulemap was
    left out that way once. A file that is left out on purpose has a line
    saying so in the manifest, `left-out` where the others say `file`, and
    that one has to be absent;
  - a file in the folder that git does not know, for the same reason seen
    from the other side;
  - a file git has that is not in the folder, or is there as a link. What
    is tracked is asked of git and what a file holds is read from the
    folder, so a file in the one and not in the other would be held against
    nothing, and a link would be read as whatever it leads to;
  - a file whose content is not upstream's and that no patch names;
  - a file a patch names that does not come out as upstream's when the
    patches are taken off it again: a patch lost or half applied in an
    upgrade, a line changed by hand and written into no patch, a patch file
    that was edited and not the source.

It also holds the three places that count the patches to each other: the
manifest's `patch` lines, the patch files that are there, and the sections of
PATCHES.md, with the number in pb_patchlevel.h, which the library reports as
part of its version.

The second tree, libchdr/ with the decompressors it carries, is held the same
way to libchdr.version, with two differences. It has no patches: a `patch`
line in its manifest is refused, and every file has to be upstream's byte for
byte. And its manifest names, on `licence` lines, the licence texts of two of
those decompressors, which upstream's tree does not carry and which are kept
in licenses/: each has to be in git and to have the content the line says.
Most of upstream's tree is not in that copy, each file on a `left-out` line.

And it holds licenses/NATIVE-LICENSES.txt, the one file that goes into every
download with the library, to the licence texts it is there to carry: every
file of either copy that is called LICENSE, the two texts kept beside
libchdr, and two notices that upstream has in a source file and nowhere
else. Each has to be in it byte for byte. A text that changed upstream, or a
copy taken anew with another licence in it, is then a file to write again,
and not a download that says something its authors did not.

It needs git, to ask what is tracked and to take the patches off, and it
compiles nothing. Without git, or outside a checkout, it says so and ends
with 2: that is no answer either way.
"""

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent.parent
CPP = "hasher/src/main/cpp"
TREE = f"{CPP}/rcheevos"
MANIFEST = f"{CPP}/rcheevos.version"
PATCHES = f"{CPP}/rcheevos-patches"
PATCH_LEVEL = f"{CPP}/pb_patchlevel.h"
LICENCES = f"{CPP}/licenses"
NOTICES = f"{LICENCES}/NATIVE-LICENSES.txt"

# A file of a copy that is its licence, by what it is called.
LICENCE_NAME = re.compile(r"(^|/)(LICEN[CS]E|COPYING)(\.txt|\.md)?$")


class Vendored:
    """One tree kept here as a copy of somebody else's: where it is, the
    manifest that says which copy, and what it has beside those two. A tree
    with no folder of patches is one that has to be upstream's as it is."""

    def __init__(self, name, tree, manifest, remedy, patches=None, patch_level=None, licences=None,
                 notices=()):
        self.name = name
        self.tree = tree
        self.manifest = manifest
        self.remedy = remedy
        self.patches = patches
        self.patch_level = patch_level
        self.licences = licences
        # (file, first line, last line): a notice upstream keeps inside a
        # source file, from the one line to the other.
        self.notices = notices


RCHEEVOS = Vendored(
    "rcheevos", TREE, MANIFEST,
    "take the folder from upstream's tree again with `git add -f`, apply the patches of "
    f"{PATCHES}, and write the diffs and {MANIFEST} again; PATCHES.md says how",
    patches=PATCHES, patch_level=PATCH_LEVEL)
LIBCHDR = Vendored(
    "libchdr", f"{CPP}/libchdr", f"{CPP}/libchdr.version",
    "take the files again from a checkout of upstream at the commit the manifest names, with "
    f"`git add -f`, and the licence texts from the tags it names; {CPP}/libchdr.version says how",
    licences=LICENCES,
    notices=(("src/libchdr_chd.c", "    Copyright Aaron Giles\n", "    POSSIBILITY OF SUCH DAMAGE.\n"),
             ("include/dr_libs/dr_flac.h",
              "This software is available as a choice of the following licenses. Choose\n",
              "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\nSOFTWARE.\n")))
TREES = (RCHEEVOS, LIBCHDR)


class CannotCheck(Exception):
    """git is not there, or this is not a checkout."""


def blob_id(data):
    """The id git gives a file of this content."""
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def read_manifest(path):
    """(upstream, patches, files, left_out, licences): the release and commit
    as a pair, the patch files' names in the order written, {path: blob id} of
    the files the copy has, the paths of those it leaves out on purpose, and
    {file name: blob id} of the licence texts kept beside the copy. A line
    this does not know is an error: a manifest read in part is checked in
    part."""
    upstream, patches, files, left_out, licences = None, [], {}, [], {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        word, _, rest = line.partition(" ")
        if word == "upstream":
            release, _, commit = rest.partition(" ")
            if upstream or not release or not re.fullmatch(r"[0-9a-f]{40}", commit):
                raise ValueError(f"line {number} is not the one `upstream <release> <commit>`: {line!r}")
            upstream = (release, commit)
        elif word == "patch":
            if not rest.endswith(".patch") or "/" in rest or rest in patches:
                raise ValueError(f"line {number} is not `patch <file name>.patch`, once: {line!r}")
            patches.append(rest)
        elif word in ("file", "left-out"):
            digest, _, name = rest.partition(" ")
            if not re.fullmatch(r"[0-9a-f]{40}", digest) or not name or name in files or name in left_out:
                raise ValueError(f"line {number} is not `{word} <blob id> <path>`, once: {line!r}")
            if word == "file":
                files[name] = digest
            else:
                left_out.append(name)
        elif word == "licence":
            digest, _, name = rest.partition(" ")
            if not re.fullmatch(r"[0-9a-f]{40}", digest) or not name or "/" in name or name in licences:
                raise ValueError(f"line {number} is not `licence <blob id> <file name>`, once: {line!r}")
            licences[name] = digest
        else:
            raise ValueError(f"line {number} begins with {word!r}, which is no kind of line of a manifest")
    if not upstream:
        raise ValueError("no `upstream` line says which tree this is")
    return upstream, patches, files, left_out, licences


def git(folder, *arguments, stdin=None, alone=False):
    """git run in `folder`. With `alone` it is kept from looking above the
    folder for a repository: a temporary folder may lie inside one, and git
    would then read a patch's paths as paths of that repository."""
    env = dict(os.environ, LC_ALL="C")   # what git says of a patch is repeated, so in one language
    if alone:
        env["GIT_CEILING_DIRECTORIES"] = str(Path(folder).resolve().parent)
    try:
        return subprocess.run(["git", *arguments], cwd=folder, input=stdin, env=env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as error:
        raise CannotCheck(f"git could not be run ({error})")


def tracked_files(repository, folder):
    """The files git has under `folder`, as paths from it."""
    done = git(repository, "ls-files", "-z", "--", folder)
    if done.returncode != 0:
        raise CannotCheck("git does not answer for this folder: "
                          + done.stderr.decode("utf-8", "replace").strip())
    return {name[len(folder) + 1:] for name in done.stdout.decode("utf-8").split("\0") if name}


def files_named(patch_text):
    """The files a patch changes, as paths from the vendored folder."""
    return re.findall(r"(?m)^diff --git a/(\S+) b/\1$", patch_text)


def without_patches(tree, names, patches):
    """{path: content} of the files `names` with the patches taken off again,
    and what could not be taken off. Done on copies, in a folder of its own."""
    wrong, contents = [], {}
    with tempfile.TemporaryDirectory(prefix="vendored-check-") as scratch:
        scratch = Path(scratch)
        for name in names:
            (scratch / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(tree / name, scratch / name)
        for patch, text in patches:
            done = git(scratch, "apply", "--reverse", "-", stdin=text, alone=True)
            if done.returncode != 0:
                said = done.stderr.decode("utf-8", "replace").strip().splitlines()
                wrong.append(f"{patch} does not come off the tree: what it adds is not there as "
                             f"it wrote it ({said[0] if said else 'git apply said nothing'})")
        for name in names:
            contents[name] = (scratch / name).read_bytes()
    return contents, wrong


def patches_counted(repository, listed_patches):
    """What is wrong among the three places that count rcheevos' patches, and
    the patch files that are there."""
    wrong = []
    there = sorted(path.name for path in (repository / PATCHES).glob("*.patch"))
    for name in listed_patches:
        if name not in there:
            wrong.append(f"{MANIFEST} lists {name}, and {PATCHES} has no such file")
    for name in there:
        if name not in listed_patches:
            wrong.append(f"{PATCHES}/{name} is on no `patch` line of {MANIFEST}")
    try:
        described = re.findall(r"(?m)^## (\S+\.patch)\s*$",
                               (repository / PATCHES / "PATCHES.md").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError):
        described = None
        wrong.append(f"{PATCHES}/PATCHES.md cannot be read")
    if described is not None and sorted(described) != there:
        wrong.append(f"PATCHES.md has a section for {', '.join(described) or 'no patch'}, "
                     f"and the patch files are {', '.join(there) or 'none'}")
    try:
        level = re.search(r"(?m)^\s*#\s*define\s+PB_RCHEEVOS_PATCHLEVEL\s+(\d+)\s*$",
                          (repository / PATCH_LEVEL).read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError):
        level = None
    if not level:
        wrong.append(f"{PATCH_LEVEL} does not define PB_RCHEEVOS_PATCHLEVEL as a number")
    elif int(level.group(1)) != len(there):
        wrong.append(f"{PATCH_LEVEL} says {level.group(1)} patches and there are {len(there)} patch files")
    return wrong, there


def licences_held(repository, vendored, licences):
    """What is wrong with the licence texts a manifest names: each has to be
    a file of its own in git, with the content the line says. They are texts
    taken from somewhere else than the tree, and a word changed in one is a
    licence nobody gave."""
    if not vendored.licences:
        return [f"{vendored.manifest} names the licence text {name}, and no folder of such "
                f"texts goes with {vendored.name}" for name in sorted(licences)]
    wrong = []
    folder = repository / vendored.licences
    tracked = tracked_files(repository, vendored.licences)
    for name, digest in sorted(licences.items()):
        if (folder / name).is_symlink():
            wrong.append(f"the licence text {name} is a link in {vendored.licences}")
        elif not (folder / name).is_file():
            wrong.append(f"the licence text {name} is not in {vendored.licences}")
        elif blob_id((folder / name).read_bytes()) != digest:
            wrong.append(f"the licence text {name} is not the one {vendored.manifest} names")
        elif name not in tracked:
            wrong.append(f"the licence text {name} is in {vendored.licences} and not in git here")
    return wrong


def notices_carried(repository, vendored, files, licences):
    """What is wrong between the licence texts of one copy and the file that
    carries them into the downloads: each text has to be in it whole, as its
    bytes are."""
    try:
        carried = (repository / NOTICES).read_bytes()
    except OSError:
        return [f"there is no {NOTICES}, which is to carry the licence texts of {vendored.name}"]
    wrong = []
    texts = [(f"{vendored.tree}/{name}", None, None) for name in sorted(files) if LICENCE_NAME.search(name)]
    texts += [(f"{vendored.licences}/{name}", None, None) for name in sorted(licences) if vendored.licences]
    texts += [(f"{vendored.tree}/{name}", first, last) for name, first, last in vendored.notices]
    for path, first, last in texts:
        try:
            text = (repository / path).read_bytes()
        except OSError:
            continue    # said already, as a file that is not there
        if first is not None:
            start = text.find(first.encode("utf-8"))
            end = text.find(last.encode("utf-8"), start) if start >= 0 else -1
            if end < 0:
                wrong.append(f"{path} no longer has the notice that begins {first.strip()!r}")
                continue
            text = text[start:end + len(last.encode("utf-8"))]
        if text not in carried:
            wrong.append(f"{NOTICES} does not carry {'the notice in ' if first is not None else ''}"
                         f"{path} as it stands")
    return wrong


def check(repository=REPOSITORY, vendored=RCHEEVOS):
    """The list of what is wrong with one tree, empty when nothing is, and
    what was read."""
    manifest = repository / vendored.manifest
    tree = repository / vendored.tree
    if not manifest.is_file():
        return [f"there is no {vendored.manifest}"], {}
    try:
        upstream, listed_patches, files, left_out, licences = read_manifest(manifest)
    except (ValueError, UnicodeDecodeError) as error:
        return [f"{vendored.manifest} cannot be read as a manifest: {error}"], {}

    wrong = []

    # The three places that count the patches, for the tree that has any.
    there = []
    if vendored.patches:
        counted, there = patches_counted(repository, listed_patches)
        wrong += counted
    else:
        for name in listed_patches:
            wrong.append(f"{vendored.manifest} lists the patch {name}, and the copy of "
                         f"{vendored.name} is kept as upstream has it, with none")

    wrong += licences_held(repository, vendored, licences)
    wrong += notices_carried(repository, vendored, files, licences)

    # What is in git, and what is in the folder, against upstream's list.
    tracked = tracked_files(repository, vendored.tree)
    for name in sorted(set(files) - tracked):
        wrong.append(f"{name} is in upstream's tree and not in git here"
                     + ("" if (tree / name).is_file() else ", nor in the folder"))
    for name in sorted(tracked - set(files)):
        wrong.append(f"{name} is in git here and not in upstream's tree")
    # A link counts as lying in the folder whatever it leads to, or to nothing.
    on_disk = {path.relative_to(tree).as_posix() for path in tree.rglob("*")
               if path.is_file() or path.is_symlink()}
    for name in sorted(on_disk - tracked - set(files)):
        wrong.append(f"{name} is in the folder, and neither git nor upstream's tree has it")
    # git was asked what is tracked and the folder is asked what a file
    # holds. A file deleted and not yet staged is in the first and not in the
    # second, and nothing below would read it; a link is read as the file it
    # leads to, which may well have upstream's content and lie anywhere.
    for name in sorted(set(files) & tracked):
        if (tree / name).is_symlink():
            wrong.append(f"{name} is a link in the folder, and upstream has a file of its own there")
        elif not (tree / name).is_file():
            wrong.append(f"{name} is in git here and not in the folder")
    # Said after the two loops above, which name such a file too, as one
    # upstream does not have: this is the line that says what it is.
    for name in sorted(left_out):
        if name in tracked or name in on_disk:
            wrong.append(f"{name} is left out of the copy on purpose, the manifest says, and is "
                         + ("in git here" if name in tracked else "in the folder"))

    # The content: upstream's, or upstream's once the patches are off.
    patches, patched = [], {}
    for name in there:
        text = (repository / vendored.patches / name).read_bytes()
        named = files_named(text.decode("utf-8", "replace"))
        if not named:
            wrong.append(f"{PATCHES}/{name} names no file it changes")
        for path in named:
            if path not in files:
                wrong.append(f"{PATCHES}/{name} changes {path}, which upstream's tree does not have")
            else:
                patched.setdefault(path, []).append(name)
        patches.append((name, text))

    present = [name for name in sorted(files)
               if (tree / name).is_file() and not (tree / name).is_symlink()]
    changed = [name for name in present if blob_id((tree / name).read_bytes()) != files[name]]
    for name in changed:
        if name not in patched:
            wrong.append(f"{name} is not as upstream has it, "
                         + ("and no patch says how" if vendored.patches else
                            f"and the copy of {vendored.name} has no patches"))
    for name in sorted(patched):
        if name in present and name not in changed:
            wrong.append(f"{name} is as upstream has it, and {', '.join(patched[name])} "
                         "should have changed it")

    to_restore = [name for name in sorted(patched) if name in present]
    restored, failed = without_patches(tree, to_restore, patches)
    wrong += failed
    if not failed:
        for name in to_restore:
            if blob_id(restored[name]) != files[name]:
                wrong.append(f"{name} with {', '.join(patched[name])} taken off is not the file "
                             "upstream has: something else in it was changed")

    return wrong, {"release": upstream[0], "commit": upstream[1], "files": len(files),
                   "left_out": len(left_out), "patches": len(there), "patched": len(patched),
                   "licences": len(licences)}


def main(argv):
    if len(argv) > 2 or (len(argv) == 2 and argv[1].startswith("-")):
        print("usage: vendored_check.py [repository]", file=sys.stderr)
        return 2
    repository = Path(argv[1]).resolve() if len(argv) == 2 else REPOSITORY

    # Every tree is gone over, so that one run names all there is to put
    # right, and what is wrong is said before what is well.
    status, well = 0, []
    for vendored in TREES:
        try:
            wrong, read = check(repository, vendored)
        except CannotCheck as error:
            print(f"vendored_check: nothing was checked: {error}", file=sys.stderr)
            return 2
        if wrong:
            print(f"vendored_check: {len(wrong)} thing(s) wrong with {vendored.tree}:")
            for line in wrong:
                print(f"  {line}")
            print(f"To put it right: {vendored.remedy}.")
            status = 1
            continue
        said = (f"vendored_check: {vendored.tree} is {vendored.name} {read['release']} "
                f"({read['commit'][:12]}), {read['files']} files and {read['left_out']} left out "
                "on purpose, ")
        if vendored.patches:
            said += (f"with {read['patches']} local patches on {read['patched']} of them and "
                     "nothing else")
        else:
            said += (f"each as upstream has it, and {read['licences']} licence text(s) beside "
                     "them as the manifest names them")
        well.append(said)
    for said in well:
        print(said)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
