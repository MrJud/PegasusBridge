package com.pegasus.bridge.hasher

/**
 * Writes a `.chd` image of a disc for the tests that go through the library.
 *
 * Version 5 and not packed, which is the plainest image there is: the
 * header, a map of four bytes a hunk that says where each hunk lies (in
 * hunks from the start of the file, or 0 for one of zeros), the text that
 * describes the disc, and the hunks at multiples of their size. That is
 * enough to show that the library the JVM loads reads a disc out of one. The
 * packed images, and the ones made to mislead the reader, are rows of
 * `shared/tests/native_repro_test.py`, whose generator is
 * `shared/tests/chd_fixture.py`; this is the same layout in the language of
 * these tests.
 */
internal object ChdImage {

    /** A frame of a CD: 2352 bytes of sector and 96 of subcode. */
    const val CD_UNIT = 2448
    const val DVD_UNIT = 2048

    /**
     * One track of a CD: its kind as chdman names it (`MODE1_RAW` for
     * sectors of 2352 bytes with their sync and address, `MODE1` for the
     * 2048 bytes of data alone) and its sectors.
     */
    class Track(val type: String, val sectors: List<ByteArray>)

    /** A CD as chdman lays one out: each track filled up to a multiple of four frames, eight frames to a hunk. */
    fun cd(tracks: List<Track>): ByteArray {
        var frames = ByteArray(0)
        val lines = ArrayList<Pair<String, ByteArray>>()
        tracks.forEachIndexed { index, track ->
            for (sector in track.sectors) frames += sector.copyOf(CD_UNIT)
            frames += ByteArray(CD_UNIT * ((4 - track.sectors.size % 4) % 4))
            val line = "TRACK:${index + 1} TYPE:${track.type} SUBTYPE:NONE FRAMES:${track.sectors.size} " +
                       "PREGAP:0 PGTYPE:${track.type} PGSUB:NONE POSTGAP:0"
            lines += "CHT2" to (line.toByteArray(Charsets.US_ASCII) + 0)
        }
        return image(frames, CD_UNIT, 8 * CD_UNIT, lines)
    }

    /** A DVD as `chdman createdvd` writes one: a unit is a sector, and the text says only that it is a DVD. */
    fun dvd(iso: ByteArray, sectorsToAHunk: Int = 2): ByteArray =
        image(iso, DVD_UNIT, sectorsToAHunk * DVD_UNIT, listOf("DVD " to byteArrayOf(0)))

    private fun image(disc: ByteArray, unit: Int, hunk: Int, metadata: List<Pair<String, ByteArray>>): ByteArray {
        require(disc.size % unit == 0) { "a disc is a whole number of units" }
        val hunks = (disc.size + hunk - 1) / hunk
        val mapAt = 124

        // The pieces of metadata, each naming where the next one is.
        var chain = ByteArray(0)
        val chainAt = mapAt + 4 * hunks
        metadata.forEachIndexed { index, (tag, text) ->
            val next = if (index == metadata.lastIndex) 0L else (chainAt + chain.size + 16 + text.size).toLong()
            chain += tag.toByteArray(Charsets.US_ASCII) + be(1L shl 24 or text.size.toLong(), 4) + be(next, 8) + text
        }

        val bodyAt = (chainAt + chain.size + hunk - 1) / hunk * hunk
        var map = ByteArray(0)
        var body = ByteArray(0)
        for (index in 0 until hunks) {
            // The last hunk is written whole, as chdman writes it.
            val piece = disc.copyOfRange(index * hunk, minOf((index + 1) * hunk, disc.size)).copyOf(hunk)
            if (piece.all { it == 0.toByte() }) {
                map += be(0, 4)
            } else {
                map += be(((bodyAt + body.size) / hunk).toLong(), 4)
                body += piece
            }
        }

        val header = ByteArray(124)
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        be(124, 4).copyInto(header, 8)                      // the header's length
        be(5, 4).copyInto(header, 12)                       // the version; the four compressors after it stay 0
        be(disc.size.toLong(), 8).copyInto(header, 32)
        be(mapAt.toLong(), 8).copyInto(header, 40)
        be(if (metadata.isEmpty()) 0 else chainAt.toLong(), 8).copyInto(header, 48)
        be(hunk.toLong(), 4).copyInto(header, 56)
        be(unit.toLong(), 4).copyInto(header, 60)

        return (header + map + chain).copyOf(bodyAt) + body
    }

    private fun be(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }
}
