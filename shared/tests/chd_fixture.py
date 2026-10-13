"""Writes .chd images for native_repro_test.py, from discs made there.

A .chd is a disc cut into hunks, each packed by one of the compressors its
header names, with a map that says where each hunk lies and text that
describes the tracks. chdman writes them, and no test can count on chdman
being installed, so the images the rows need are written here, version 5
only, in the two shapes there are:

  not packed   the header names no compressor, the map is four bytes a hunk
               (where the hunk lies, counted in hunks, or 0 for one of
               zeros), and the hunks lie at multiples of their size;
  packed       the map is itself packed, bit by bit: a table of code
               lengths, a code for every hunk that says how it is kept, and
               then for every hunk its length and checksum, or the hunk it
               is a copy of.

A hunk can be written here in most of the ways libchdr unpacks one, with
what Python has: deflate from zlib, LZMA from lzma, and for the three it has
no encoder for (Zstandard, FLAC, Huffman) a stream that holds the bytes as
they are, or nearly, which each format allows. Such a stream goes through
the decoder's framing and little of what it was written for. libchdr takes
no packed hunk that is as long as the hunk itself, as chdman writes none, so
a hunk that does not come out shorter is written as it is: of noise, only
deflate, LZMA and the FLAC of a CD's frames, whose subcode of zeros makes
the room, give a packed hunk. The plain FLAC of a DVD has no such stream at
all and is not written here. The images in chd/ beside this file, which
chdman wrote, are what goes through the real thing.

What is written here is what chdman reads as well, and not only what
libchdr takes: `chdman extractcd` and `extractdvd` give back the disc of an
image packed by each of the eight ways below. That is held by no test, since
no test may need chdman, and is to be seen again by whoever changes a
packer: libchdr's decoders forgive what MAME's do not, a FLAC frame with no
checksums among it.

An image is described by a Chd, whose fields a row changes before it asks
for the bytes: most of what a file made to mislead the reader says is one
field set to something no real image has.
"""

import binascii
import lzma
import zlib

CD_UNIT = 2448       # a frame of a CD: 2352 bytes of sector and 96 of subcode
CD_SECTOR = 2352
DVD_UNIT = 2048

# How a hunk is kept, as the map of a packed image says it.
STORED, COPY, FROM_PARENT = 4, 5, 6

# How wide the map's numbers are written: a packed hunk's length, the hunk a
# copy is of, the unit of a parent. The image says the three widths itself,
# and may say up to 32; but MAME's reader of bits and libchdr's hold 32 at a
# time and fill up by the byte, so a number wider than 25 bits loses its low
# bits wherever it does not begin on a byte. chdman writes no more bits than
# the largest number needs, which for any image this reader takes is 23.
FIELD_BITS = 24


def crc16(data):
    """The checksum a .chd keeps of a hunk and of its map: CCITT, begun at 0xFFFF."""
    return binascii.crc_hqx(bytes(data), 0xFFFF)


class Bits:
    """Bits written first to last, the high bit of a byte first."""

    def __init__(self):
        self.whole = bytearray()
        self.value = 0      # the bits that do not make a byte yet
        self.count = 0

    def put(self, value, width):
        self.value = (self.value << width) | (value & ((1 << width) - 1))
        self.count += width
        while self.count >= 8:
            self.count -= 8
            self.whole.append(self.value >> self.count)
            self.value &= (1 << self.count) - 1

    def extend(self, other):
        for byte in other.whole:
            self.put(byte, 8)
        self.put(other.value, other.count)

    def bytes(self):
        """The bits so far, the last byte filled up with zeros."""
        return bytes(self.whole) + (bytes([self.value << (8 - self.count)]) if self.count else b"")


# ------------------------------------------------------------ the compressors

def deflate(data):
    packer = zlib.compressobj(9, zlib.DEFLATED, -15)
    return packer.compress(bytes(data)) + packer.flush()


def lzma_raw(data):
    """One LZMA stream with the properties libchdr's decoder is set up for
    (lc 3, lp 0, pb 2) and a dictionary no larger than the hunk."""
    filters = [{"id": lzma.FILTER_LZMA1, "lc": 3, "lp": 0, "pb": 2,
                "dict_size": max(4096, len(data))}]
    return lzma.compress(bytes(data), format=lzma.FORMAT_RAW, filters=filters)


def zstd_plain(data):
    """A Zstandard frame of blocks that hold their bytes as they are, or one
    byte and how often it comes: no table of the format is used. Cut small,
    so that a hunk which ends in zeros comes out shorter than it is."""
    frame = bytearray(bytes.fromhex("28b52ffd") + b"\xa0" + len(data).to_bytes(4, "little"))
    pieces = [bytes(data[at:at + 128]) for at in range(0, len(data), 128)]
    for index, piece in enumerate(pieces):
        last = 1 if index == len(pieces) - 1 else 0
        if piece.count(piece[0]) == len(piece):
            frame += (len(piece) << 3 | 1 << 1 | last).to_bytes(3, "little") + piece[:1]
        else:
            frame += (len(piece) << 3 | last).to_bytes(3, "little") + piece
    return bytes(frame)


def huffman_plain(data):
    """MAME's Huffman stream with a code fixed beforehand: one bit for a
    zero, eight for a one and nine for every other byte, so that a hunk
    which is mostly zeros comes out shorter than it is. First the table of
    the 256 code lengths, itself written with a small code, then the bytes."""
    bits = Bits()
    # The small code has four symbols of two bits each: 0 ("the last length
    # again"), 2, 9 and 10 (a length of 1, of 8 and of 9).
    bits.put(2, 3)              # symbol 0 has two bits
    bits.put(1, 3)              # the next that has any is symbol 2
    bits.put(2, 3)              # which has two
    for _ in range(6):
        bits.put(0, 3)          # symbols 3 to 8 have none
    bits.put(2, 3)              # symbol 9
    bits.put(2, 3)              # symbol 10
    bits.put(7, 3)              # and none after it
    bits.put(0b01, 2)           # byte 0: a length of 1
    bits.put(0b10, 2)           # byte 1: a length of 8
    bits.put(0b11, 2)           # byte 2: a length of 9
    bits.put(0b00, 2)           # and the same again,
    bits.put(7, 3)              # nine times and
    bits.put(253 - 9, 8)        # as many more as make 253
    for byte in bytes(data):
        if byte == 0:
            bits.put(1, 1)
        elif byte == 1:
            bits.put(127, 8)
        else:
            bits.put(byte - 2, 9)
    return bits.bytes()


def crc(data, width, polynomial):
    """A checksum as FLAC makes its two: the high bit first, begun at 0."""
    top = 1 << (width - 1)
    value = 0
    for byte in data:
        value ^= byte << (width - 8)
        for _ in range(8):
            value = ((value << 1) ^ polynomial if value & top else value << 1) & ((1 << width) - 1)
    return value


def flac_verbatim(samples, block):
    """FLAC frames of two channels of 16 bits whose subframes hold the
    samples as they are, the high byte of each first as a CD has them.
    `samples` is the bytes to come out, four to a stereo sample. Each frame
    carries the checksum of its header and the one of the whole of it:
    libchdr's decoder is built not to look at either, and MAME's, which
    chdman reads an image with, takes no frame without them."""
    out = b""
    for number, at in enumerate(range(0, len(samples), 4 * block)):
        piece = samples[at:at + 4 * block]
        count = len(piece) // 4
        # sync, a block size given in 16 bits after the frame's number,
        # 44.1 kHz, two channels kept apart, 16 bits
        frame = bytes([0xFF, 0xF8, 0x79, 0x18, number]) + (count - 1).to_bytes(2, "big")
        frame += bytes([crc(frame, 8, 0x07)])
        for channel in range(2):
            frame += b"\x02"                       # a subframe of samples as they are
            frame += b"".join(piece[4 * sample + 2 * channel:4 * sample + 2 * channel + 2]
                              for sample in range(count))
        out += frame + crc(frame, 16, 0x8005).to_bytes(2, "big")
    return out


def cd_parts(data):
    """The sectors of a hunk of CD frames, one after the other, and their
    subcode, likewise: the two are packed apart."""
    sectors = b"".join(data[at:at + CD_SECTOR] for at in range(0, len(data), CD_UNIT))
    subcode = b"".join(data[at + CD_SECTOR:at + CD_UNIT] for at in range(0, len(data), CD_UNIT))
    return sectors, subcode


def cd_hunk(sectors_packer, subcode_packer):
    """A hunk of one of the compressors for CD frames that begin with a
    header: which frames are to have their error correction made again
    (none), the length of what follows, the frames' sectors packed together
    and their subcode packed together."""
    def pack(data):
        frames = len(data) // CD_UNIT
        sectors, subcode = cd_parts(data)
        packed = sectors_packer(sectors)
        return (bytes((frames + 7) // 8) + len(packed).to_bytes(2 if len(data) < 65536 else 3, "big")
                + packed + subcode_packer(subcode))
    return pack


def cd_flac_hunk(data):
    """A hunk of CD frames packed by FLAC, which has no header: the sectors
    as FLAC frames, of as many samples as a sector has or half of that and
    so on down, and the subcode deflated straight after them. The decoder
    is what says where the one ends and the other begins."""
    sectors, subcode = cd_parts(data)
    block = len(sectors) // 4
    while block > CD_SECTOR:
        block //= 2
    return flac_verbatim(sectors, block) + deflate(subcode)


PACKERS = {
    b"zlib": deflate,
    b"lzma": lzma_raw,
    b"zstd": zstd_plain,
    b"huff": huffman_plain,
    b"cdzl": cd_hunk(deflate, deflate),
    b"cdlz": cd_hunk(lzma_raw, deflate),
    b"cdzs": cd_hunk(zstd_plain, zstd_plain),
    b"cdfl": cd_flac_hunk,
}


# ------------------------------------------------------------------ the image

class Chd:
    """One image. `data` is the disc, a whole number of units; `metadata` is
    (tag, text) pairs in the order they are chained. With `codecs` the image
    is packed, every hunk by the first of them unless `kinds` says otherwise
    for it: a place among the codecs, STORED, (COPY, hunk) or
    (FROM_PARENT, unit), or any other number for a kind no map has. A packed
    hunk that is the same as an earlier one is written as a copy of it
    unless `copies` is False. `data` may be shorter than the disc is said to
    be (`logical`, with `hunks` for how many the map is written for): what
    is not there is zeros, and a map of very many hunks need not have them
    all in memory.

    What a row changes afterwards: `logical`, `hunk_bytes`, `unit_bytes`,
    `version`, `header_length`, `parent`, `codecs`, `kinds`, `wrong_crc` (the
    hunks whose checksum in the map is not theirs), `packed_as` (hunk: bytes
    to write in place of what its packer gave), `map_bytes`, `map_at`,
    `metadata_at`, `first_at` and `loop` (the last piece of metadata is
    followed by the first again)."""

    def __init__(self, data, unit_bytes, hunk_bytes, metadata, codecs=(), copies=True):
        self.data = bytes(data)
        self.unit_bytes = unit_bytes
        self.hunk_bytes = hunk_bytes
        self.metadata = list(metadata)
        self.codecs = list(codecs)
        self.copies = copies
        self.laid = hunk_bytes  # the size the hunks are written with, whatever the header says
        self.logical = len(self.data)
        self.version = 5
        self.header_length = 124
        self.parent = bytes(20)
        self.kinds = {}
        self.wrong_crc = set()
        self.packed_as = {}
        self.map_bytes = None
        self.map_at = None
        self.metadata_at = None
        self.first_at = None
        self.loop = False
        self.hunks = None       # how many the map is written for, when not what the data has

    def hunk(self, index, size):
        """The hunk as it is unpacked: the last is filled up with zeros."""
        piece = self.data[index * size:(index + 1) * size]
        return piece + bytes(size - len(piece))

    def chained(self, at):
        """The pieces of metadata as they lie from `at` on, and where the
        first is (0 when there is none)."""
        out = b""
        places = []
        for tag, text in self.metadata:
            places.append(at + len(out))
            out += tag + (1 << 24 | len(text)).to_bytes(4, "big") + bytes(8) + text
        out = bytearray(out)
        for index, place in enumerate(places):
            following = places[index + 1] if index + 1 < len(places) else (places[0] if self.loop else 0)
            start = place - at
            out[start + 8:start + 16] = following.to_bytes(8, "big")
        return bytes(out), (places[0] if places else 0)

    def header(self, map_at, metadata_at):
        out = bytearray(124)
        out[0:8] = b"MComprHD"
        out[8:12] = self.header_length.to_bytes(4, "big")
        out[12:16] = self.version.to_bytes(4, "big")
        for index, codec in enumerate(self.codecs[:4]):
            out[16 + 4 * index:20 + 4 * index] = codec
        out[32:40] = self.logical.to_bytes(8, "big")
        out[40:48] = (self.map_at if self.map_at is not None else map_at).to_bytes(8, "big")
        out[48:56] = (self.metadata_at if self.metadata_at is not None else metadata_at).to_bytes(8, "big")
        out[56:60] = self.hunk_bytes.to_bytes(4, "big")
        out[60:64] = self.unit_bytes.to_bytes(4, "big")
        out[104:124] = self.parent
        return bytes(out)

    def bytes(self):
        size = self.laid
        count = self.hunks if self.hunks is not None else -(-len(self.data) // size)
        return self.packed(size, count) if self.codecs else self.plain(size, count)

    def plain(self, size, count):
        """Header, map, metadata, and then the hunks at multiples of their
        size, the last one whole."""
        map_at = 124
        metadata, first = self.chained(map_at + 4 * count)
        body_at = -(-(map_at + 4 * count + len(metadata)) // size) * size
        entries, body = bytearray(), bytearray()
        for index in range(count):
            hunk = self.hunk(index, size)
            if index in self.kinds:
                entries += self.kinds[index].to_bytes(4, "big")   # a place, as it stands
            elif hunk.count(0) == len(hunk):
                entries += bytes(4)
            else:
                entries += ((body_at + len(body)) // size).to_bytes(4, "big")
                body += hunk
        image = self.header(map_at, first) + bytes(entries) + metadata
        return image + bytes(body_at - len(image)) + bytes(body)

    def packed(self, size, count):
        """Header, metadata, the hunks one after the other, and the map."""
        metadata, first = self.chained(124)
        first_at = 124 + len(metadata)
        body = bytearray()
        seen = {}
        kinds, fields, decoded = Bits(), Bits(), bytearray()
        for _ in range(16):
            kinds.put(4, 4)     # every code is four bits long, so a kind is its own code
        for index in range(count):
            hunk = self.hunk(index, size)
            kind = self.kinds.get(index, 0)
            if index not in self.kinds and self.copies and hunk in seen:
                kind = (COPY, seen[hunk])
            seen.setdefault(hunk, index)
            crc = crc16(hunk) ^ (0x5A5A if index in self.wrong_crc else 0)
            at = first_at + len(body)
            if isinstance(kind, tuple):
                kinds.put(kind[0], 4)
                fields.put(kind[1], FIELD_BITS)
                decoded += bytes([kind[0]]) + bytes(3) + kind[1].to_bytes(6, "big") + bytes(2)
            elif kind < 4 and index not in self.packed_as and index not in self.kinds \
                    and len(PACKERS[self.codecs[kind]](hunk)) >= size:
                # As chdman does with a hunk no compressor makes shorter.
                kinds.put(STORED, 4)
                fields.put(crc, 16)
                decoded += (bytes([STORED]) + size.to_bytes(3, "big") + at.to_bytes(6, "big")
                            + crc.to_bytes(2, "big"))
                body += hunk
            elif kind < 4:
                packed = self.packed_as.get(index)
                if packed is None:
                    packed = PACKERS[self.codecs[kind]](hunk)
                kinds.put(kind, 4)
                fields.put(len(packed), FIELD_BITS)
                fields.put(crc, 16)
                decoded += (bytes([kind]) + len(packed).to_bytes(3, "big") + at.to_bytes(6, "big")
                            + crc.to_bytes(2, "big"))
                body += packed
            elif kind == STORED:
                kinds.put(kind, 4)
                fields.put(crc, 16)
                decoded += (bytes([kind]) + size.to_bytes(3, "big") + at.to_bytes(6, "big")
                            + crc.to_bytes(2, "big"))
                body += hunk
            else:
                kinds.put(kind, 4)
                decoded += bytes([kind]) + bytes(3) + at.to_bytes(6, "big") + bytes(2)
        kinds.extend(fields)
        packed_map = kinds.bytes()
        map_at = first_at + len(body)
        map_header = ((self.map_bytes if self.map_bytes is not None else len(packed_map)).to_bytes(4, "big")
                      + (self.first_at if self.first_at is not None else first_at).to_bytes(6, "big")
                      + crc16(decoded).to_bytes(2, "big")
                      + bytes([FIELD_BITS, FIELD_BITS, FIELD_BITS, 0]))
        return self.header(map_at, first) + metadata + bytes(body) + map_header + packed_map


# ------------------------------------------------------------------- the discs

def frame(sector):
    """A frame of a CD image from a sector of 2352 bytes or of 2048, which
    is followed by zeros; the subcode is zeros."""
    return bytes(sector) + bytes(CD_UNIT - len(sector))


def cd_tracks(tracks, tag=b"CHT2"):
    """(the frames, the metadata) of a CD as chdman lays one out: each
    track's frames, filled up with frames of zeros to a multiple of four, and
    a line of text for each. A track is a dict: `type`, `sectors` (a list of
    sectors, with those of a pregap that is in the image among them), and
    where they are not the usual `pregap`, `pgtype`, `pad`, `frames` (when
    the line is to say another number than there are), `line` (the whole
    text, in place of the one put together here)."""
    data, metadata = b"", []
    for number, track in enumerate(tracks, 1):
        sectors = track["sectors"]
        data += b"".join(frame(sector) for sector in sectors)
        data += bytes(CD_UNIT * (-len(sectors) % 4))
        kind = track["type"]
        frames = track.get("frames", len(sectors))
        text = f"TRACK:{number} TYPE:{kind} SUBTYPE:NONE FRAMES:{frames}"
        gap = (f" PREGAP:{track.get('pregap', 0)} PGTYPE:{track.get('pgtype', kind)}"
               f" PGSUB:NONE POSTGAP:0")
        if tag == b"CHGD":
            text += f" PAD:{track.get('pad', 0)}" + gap
        elif tag == b"CHT2":
            text += gap
        metadata.append((tag, (track.get("line") or text).encode("ascii") + b"\0"))
    return data, metadata


def cd(tracks, tag=b"CHT2", codecs=(), hunk_frames=8, **more):
    data, metadata = cd_tracks(tracks, tag)
    return Chd(data, CD_UNIT, hunk_frames * CD_UNIT, metadata, codecs, **more)


def dvd(image, codecs=(), hunk_sectors=2, **more):
    """A DVD image as chdman createdvd writes one: units of a sector, and a
    piece of metadata that says no more than that it is a DVD."""
    return Chd(image, DVD_UNIT, hunk_sectors * DVD_UNIT, [(b"DVD ", b"\0")], codecs, **more)


def sectors_of(image, size):
    return [image[at:at + size] for at in range(0, len(image), size)]
