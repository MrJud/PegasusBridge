package com.pegasus.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names a request may give the files of its answer. The router holds a
 * job id, an RA user and a gameId to this before they become a path, since
 * any app and any web page can send it any of them.
 */
class PathsFileNameTest {

    @Test fun `what the theme sends is a file name`() {
        for (name in listOf("steamcreds_1760400000000_4242", "clearcreds_1760400000000_7",
                            "ra_1760400000000_99998", "pdl_1", "SomeUser",
                            "3fa85f64-5717-4562-b3fc-2c963f66afa6", ".", "..", "a".repeat(100))) {
            assertTrue("'$name' was refused", Paths.isFileName(name))
            // And it stays in the folder it is written in.
            assertEquals(Paths.PENDING, Paths.pending(name).parentFile)
            assertEquals(Paths.CREDENTIAL_ANSWERS, Paths.credentialAnswer(name).parentFile)
        }
    }

    /** BridgeApi.credentialsResult reads dataRoot + "/credentials/" + jobId + ".json". */
    @Test fun `a credentials answer is where the theme's file transport reads one`() {
        assertEquals(java.io.File("/sdcard/PegasusData/credentials/steamcreds_1760400000000_4242.json"),
                     Paths.credentialAnswer("steamcreds_1760400000000_4242"))
        assertEquals(java.io.File("/sdcard/PegasusData/config/credentials.json"), Paths.CREDENTIALS)
    }

    @Test fun `a name that reaches out of its folder, or none, is not`() {
        for (name in listOf("../config/credentials", "../../ReStory/hasher_config",
                            "a/b", "/sdcard/PegasusData/config/credentials", "x\u0000y",
                            "", "a".repeat(101))) {
            assertFalse("'$name' was taken", Paths.isFileName(name))
        }
        // What the first would have written over.
        assertEquals(Paths.CREDENTIALS,
                     Paths.pending("../config/credentials").toPath().normalize().toFile())
    }
}
