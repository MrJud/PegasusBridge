package com.pegasus.bridge

import android.util.Log
import com.pegasus.bridge.core.BridgeLog

/**
 * The adapter [BridgeLog] expects the Android shell to install: what the code
 * both shells compile has to say goes to Logcat, under the tag and at the level
 * that code gave it.
 *
 * Until something is installed that code writes to stderr. Android does put
 * stderr in Logcat, but every line as a warning under the one tag `System.err`,
 * a stack trace as one such line per frame: nothing to filter a scan's lines by,
 * and nothing to tell its errors from its chatter.
 *
 * It lives in `app` and not beside the interface, so that `core` goes on
 * importing nothing from android.* and can be compiled on a plain JVM.
 */
object LogcatBridgeLog : BridgeLog {
    override fun d(tag: String, msg: String) { Log.d(tag, msg) }
    override fun i(tag: String, msg: String) { Log.i(tag, msg) }
    override fun w(tag: String, msg: String, t: Throwable?) { Log.w(tag, msg, t) }
    override fun e(tag: String, msg: String, t: Throwable?) { Log.e(tag, msg, t) }
}
