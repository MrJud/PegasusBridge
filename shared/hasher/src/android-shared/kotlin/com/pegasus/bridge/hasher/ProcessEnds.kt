package com.pegasus.bridge.hasher

/**
 * What a shell can say of how its processes ended, for the scan after one
 * whose process went with files in the hasher ([HashTrace]).
 *
 * A process that goes with a file in hand makes that file a suspect, and one
 * that goes with a file in hand alone convicts it. That is right for a
 * process the hasher ended, and wrong for one the system ended for a reason
 * that is none of the file's: the user stopped the app, the app was
 * updated, a permission was taken away. A JVM that is stopped in order says
 * so itself, by a shutdown hook; Android runs no hook for a process it
 * kills, and keeps a record of why each one ended instead. A shell that can
 * read such a record hands one of these to the scan, and a shell that
 * cannot hands none.
 */
interface ProcessEnds {

    /**
     * The name of the running process, written on every begin of the trace.
     * It has to be a name no other process of this installation has for as
     * long as the system remembers how one ended, or as near to that as the
     * system has: a process is only asked about by the name a begin
     * carries, and the running one is never asked about.
     */
    val current: String

    /**
     * When the process called [process] was ended from outside, as the
     * system's clock had it, in milliseconds since 1970; or null when it
     * was not ended from outside, or nothing is known of it. An end from
     * outside is one the system says it brought about for a reason that
     * lies with no file in the hasher. A crash, a signal, a kill for
     * memory and an end the system has no record of are none.
     */
    fun endedFromOutside(process: String): Long?
}
