package com.pegasus.bridge.core

/**
 * Logging seam. The shared code cannot call `android.util.Log`, so it goes
 * through here: the Android shell installs an adapter that forwards to Logcat,
 * and the desktop daemon installs one that writes to a file or stderr.
 *
 * Defaults to stderr so that code under test and one-off tools are never silent.
 */
interface BridgeLog {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)

    companion object {
        @Volatile
        var current: BridgeLog = StderrLog

        fun d(tag: String, msg: String) = current.d(tag, msg)
        fun i(tag: String, msg: String) = current.i(tag, msg)
        fun w(tag: String, msg: String, t: Throwable? = null) = current.w(tag, msg, t)
        fun e(tag: String, msg: String, t: Throwable? = null) = current.e(tag, msg, t)
    }
}

object StderrLog : BridgeLog {
    override fun d(tag: String, msg: String) = out("D", tag, msg, null)
    override fun i(tag: String, msg: String) = out("I", tag, msg, null)
    override fun w(tag: String, msg: String, t: Throwable?) = out("W", tag, msg, t)
    override fun e(tag: String, msg: String, t: Throwable?) = out("E", tag, msg, t)

    private fun out(level: String, tag: String, msg: String, t: Throwable?) {
        System.err.println("$level/$tag: $msg")
        t?.printStackTrace(System.err)
    }
}

/** Discards everything. Useful in tests that assert on stderr. */
object NoopLog : BridgeLog {
    override fun d(tag: String, msg: String) = Unit
    override fun i(tag: String, msg: String) = Unit
    override fun w(tag: String, msg: String, t: Throwable?) = Unit
    override fun e(tag: String, msg: String, t: Throwable?) = Unit
}
