package com.pegasus.bridge.hasher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Which of the reasons Android gives for the end of a process are ends from
 * outside: ones the system brought about for something that lies with no
 * file the hasher had in hand.
 *
 * The eight below are. Every other reason is left where it was, a possible
 * death by a file: a crash in native code or in the JVM, a signal, a kill
 * for memory (which some devices report as a signal), an ANR, a kill for
 * using too much of something, an exit of the process's own, a failure to
 * start, a reason unknown, and any number a later Android adds. A hasher
 * that a file drives to take all the memory there is ends as a kill for
 * memory, so that one cannot be told from the system's own housekeeping
 * and stays on this side.
 */
// The constants are whole numbers the compiler copies in, held against a
// number the system hands back: nothing of a later Android is called for them.
@SuppressLint("InlinedApi")
internal object ExitReasons {

    val FROM_OUTSIDE: Set<Int> = setOf(
        // A permission was taken away while the app ran.
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        // The user: a force stop, a swipe out of the recent apps, and before
        // Android 14 an update of the app as well.
        ApplicationExitInfo.REASON_USER_REQUESTED,
        // The user it ran as was stopped.
        ApplicationExitInfo.REASON_USER_STOPPED,
        // Something the app was bound to went, and took it along.
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        // The system's own housekeeping, which the documentation calls not
        // caused by the app.
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED
    )

    fun fromOutside(reason: Int): Boolean = reason in FROM_OUTSIDE
}

/**
 * Android's record of how the app's processes ended, for the scan's trace
 * of the files in the hasher.
 *
 * A process is named by its pid. The system keeps a short history of the
 * ends of an app's processes from Android 11 on, each with the pid, a
 * reason and the time; before that there is nothing to ask, and every end
 * reads as it always did. The newest record of the pid is the one taken,
 * and only when it is of that pid: a process ended by a reboot or a power
 * cut leaves no record, and what is found then is some earlier process's,
 * or nothing. Whether a record is this process's and not an earlier one's
 * of the same pid is for [HashTrace] to say, by the time of it.
 */
internal class AndroidProcessEnds(context: Context) : ProcessEnds {

    private val context = context.applicationContext

    override val current: String = Process.myPid().toString()

    override fun endedFromOutside(process: String): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pid = process.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return recorded(pid)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recorded(pid: Int): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val record = manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
        if (record == null || record.pid != pid) {
            Log.i(TAG, "process $pid: the system has no record of how it ended")
            return null
        }
        val outside = ExitReasons.fromOutside(record.reason)
        Log.i(TAG, "process $pid ended for reason ${record.reason} (${record.description ?: "no description"}), " +
                   if (outside) "which is an end from outside" else "which may be a file's doing")
        return if (outside) record.timestamp else null
    }

    private companion object {
        const val TAG = "ProcessExit"
    }
}
