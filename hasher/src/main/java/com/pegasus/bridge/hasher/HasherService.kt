package com.pegasus.bridge.hasher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.Paths
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * The ROM scan as Android has to run it: a foreground service, started by
 * DataLayerRouter with the roots and a job id, that holds a wake lock and a
 * notification with a Cancel on it for as long as the scan lasts.
 *
 * The scan itself is [RomScanPipeline]'s, the one the desktop daemon runs.
 * What is here is what stands around it on a device: the start requests and
 * the lock that keeps them apart from the end of a scan, the credentials,
 * the thermal back-off, and the job's record in `pending/{jobId}.json` with
 * its marker in `done/`, which is all the theme has to go by.
 */
class HasherService : Service() {

    companion object {
        private const val TAG             = "HasherService"
        private const val CHANNEL_ID      = "pegasus_bridge_hasher"
        private const val NOTIFICATION_ID = 2001

        // How many files a scan reads and hashes at once when the request does
        // not say. It was four, which is what the desktop daemon keeps. Here a
        // library is as a rule on one card, where more readers need not mean
        // more read, and each worker is a core kept busy in a device this
        // service already has to slow down for when it gets warm. Two is a
        // choice and not a result: the card has not been timed with one
        // reader, two or four. EXTRA_HASH_WORKERS is there so that it can be,
        // with one build.
        private const val DEFAULT_HASH_WORKERS = 2
        // The most a request is given: a bound on a number that comes from
        // outside. The pipeline has one of its own, the number of cores.
        private const val MAX_HASH_WORKERS = 8

        const val EXTRA_ROOTS  = "roots"   // pipe- or comma-separated directories
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_HASH_WORKERS = "hashWorkers"   // optional, an int

        @Volatile var isRunning = false
            private set
    }

    private val scope   = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // Taken by every start request and by the end of a scan, so the one cannot
    // run between the lines of the other. Guards isRunning, scanJob, the wake lock
    // and the foreground state as well as the id below.
    private val lifecycle = Any()
    // The newest start request: the only id stopSelf(int) honours.
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(lifecycle) {
        latestStartId = startId
        handleStart(intent, startId)
    }

    private fun handleStart(intent: Intent?, startId: Int): Int {
        if (intent?.action == "CANCEL") {
            // A Cancel that finds no scan — tapped as one was finishing — still has
            // to stop the service: the finally that would have has already run,
            // with an id that is no longer the newest.
            if (isRunning) scanJob?.cancel() else stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isRunning) {
            Log.i(TAG, "Scan already in progress, ignoring duplicate start request")
            // Do NOT call stopSelf(startId) here — startId is the latest, so Android
            // would tear down the whole service, cancelling the running scan. The
            // scan's finally stops it with this id instead.
            return START_NOT_STICKY
        }

        val rootsCsv = intent?.getStringExtra(EXTRA_ROOTS) ?: run { endWithoutScan(startId); return START_NOT_STICKY }
        val jobId    = intent.getStringExtra(EXTRA_JOB_ID) ?: java.util.UUID.randomUUID().toString()
        val roots    = rootsCsv.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }
        // Held to a range and not passed on as it came. It arrives in a URI that
        // anything on the device can send, and the pipeline refuses to be built
        // with no worker: a 0 would end the scan as an error about a parameter
        // the person looking at the popup has never heard of.
        val hashWorkers = intent.getIntExtra(EXTRA_HASH_WORKERS, DEFAULT_HASH_WORKERS)
            .coerceIn(1, MAX_HASH_WORKERS)
        // When the job began, read once. Every record it leaves carries this,
        // the one it ends on and an error included; each used to carry the
        // time it was written.
        val startedAt = BridgePaths.epochSeconds()

        val creds = Config.load()
        val raUser   = creds.ra?.user   ?: ""
        val raApiKey = creds.ra?.apiKey ?: ""
        if (raUser.isEmpty() || raApiKey.isEmpty()) {
            writeError(jobId, ScanJobRecord.error(jobId, "Missing RA credentials in credentials.json", startedAt))
            endWithoutScan(startId); return START_NOT_STICKY
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("Scanning ROM folders…", 0, 0))
        acquireWakeLock()

        scanJob = scope.launch {
            try {
                scan(roots, jobId, raUser, raApiKey, startedAt, hashWorkers)
            } catch (e: CancellationException) {
                Log.i(TAG, "Scan cancelled")
                writeError(jobId, ScanJobRecord.error(jobId, "Cancelled", startedAt))
            } catch (t: Throwable) {
                // Throwable, not Exception. An Error — an UnsatisfiedLinkError
                // from the native hasher, or one a lookup's owner hands every file
                // waiting on it — went straight past, so pending kept saying
                // "running", the theme's popup with it, and the Error left the
                // coroutine and killed the app. It ends the scan like any other
                // failure; rethrown after the error is written, it would still
                // kill the app.
                Log.e(TAG, "Scan failed", t)
                writeError(jobId, ScanJobRecord.error(jobId, t.message ?: t.javaClass.simpleName, startedAt))
            } finally {
                // Under the lock a start request takes. Outside it, one arriving
                // after isRunning went false began a scan that the rest of this
                // block then undid: its wake lock released, its notification
                // taken down and, with the newest id below, the service stopped
                // under it.
                synchronized(lifecycle) {
                    isRunning = false
                    releaseWakeLock()
                    // Caught, like the error record in the two catches above. On
                    // a data root that is full or read-only this write fails as
                    // the ones before it did, and thrown from here it went past
                    // the two lines under it and out of the coroutine: the
                    // notification stayed up, the service was not stopped, and
                    // what leaves a coroutine uncaught kills the app.
                    try {
                        Paths.markDone(jobId)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Scan $jobId: could not write the done marker", t)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // The newest start request, not this scan's. stopSelf(int)
                    // ignores any other id, so once a Cancel from the notification
                    // or a duplicate start had come in, the scan's own id stopped
                    // nothing and the service stayed up, idle, until Android
                    // reclaimed it.
                    stopSelf(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Ends a start request that will not become a scan.
     *
     * DataLayerRouter starts this service with startForegroundService, and from
     * Android 9 a service started that way which stops before calling
     * startForeground takes the app down: "Context.startForegroundService() did
     * not then call Service.startForeground()". So the notification goes up, for
     * as long as it takes to take it down again. The error, if there is one, is
     * written first, so the theme sees it either way.
     */
    private fun endWithoutScan(startId: Int) {
        startForeground(NOTIFICATION_ID, buildNotification("Scanning ROM folders…", 0, 0))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scanJob?.cancel(); scope.cancel()
        synchronized(lifecycle) { isRunning = false; releaseWakeLock() }
        super.onDestroy()
    }

    // ── Pipeline ────────────────────────────────────────────────────────────

    /**
     * One scan, run by the pipeline the desktop daemon runs. What is left to
     * this class is what only Android has: the record the theme polls, the
     * notification, and the thermal back-off the pipeline takes as its
     * throttle.
     *
     * A scan the pipeline stops itself comes back as a summary like any other
     * and is written here as an error, which it is for whoever is watching. A
     * cancel and a failure come out as what was thrown, for [handleStart] to
     * record. Once it has found the files, the pipeline rebuilds the index and
     * saves the ledger on each of the three ways out.
     */
    private suspend fun scan(
        roots: List<String>, jobId: String, raUser: String, raApiKey: String, startedAt: Long,
        hashWorkers: Int
    ) {
        // Not left to the pipeline, whose BridgePaths has no pegasus/ to make.
        Paths.ensureAll()
        // Before the directories are walked. The pipeline says nothing while it
        // walks them, which takes a while on a card, and a job that has left no
        // record by the theme's fifth poll is taken for one that has finished.
        writePending(jobId, ScanJobRecord.started(jobId, startedAt))

        // Said in the log, so that a timing can be put beside the count it was
        // taken with, and a misspelt parameter shows as the default it got. The
        // count is the one the pipeline starts: what the request came to, or
        // one for each core on a device with fewer. It was the first of the
        // two, whatever the device.
        Log.i(TAG, "Scan $jobId: ${RomScanPipeline.hashProducers(hashWorkers)} hash workers")
        // Whether a request that failed is to be put down to the connection.
        // The lookup asks when one has failed and at no other time, so nothing
        // is asked of Android before a scan or for one that finds everything
        // cached, and a scan is never turned away for what Android says: it
        // stops as offline only once a request has failed as well. The verdict
        // is the scan's own, like the lookup, because it remembers how long a
        // network has gone unvalidated. The lookup tells it of every answer,
        // which is what ends that wait on a network that carries requests.
        val linkState = ScanCollaborators.linkState
        val verdict = OfflineVerdict()
        val device = object : DeviceConnection {
            override fun offline() = verdict.offline(linkState(this@HasherService))
            override fun answered() = verdict.answered()
        }
        val pipeline = RomScanPipeline(
            paths         = Paths.bridge,
            hasher        = ArchiveAwareHasher(ScanCollaborators.hasher(), cacheDir),
            lookup        = ScanCollaborators.lookup(raUser, raApiKey, device),
            throttleMs    = ::thermalDelayMs,
            hashWorkers   = hashWorkers,
            // Every result, and not the fiftieth of the library the pipeline
            // reports by when it is not told. Which of them get a record is
            // decided below, and part of that is how long ago the last one
            // was written: told only of every hundredth result of a large
            // library, this could not write one in between however long the
            // hundred took.
            reportStep    = { 1 }
        )

        // Told of every result, which is far more often than a record is
        // worth writing, and ScanJobRecord.Pace says which of them are. Only
        // the pipeline's collector calls back, one result at a time, so what
        // Pace keeps needs no lock.
        val pace = ScanJobRecord.Pace()
        val summary = pipeline.scan(roots, onCounted = { total ->
            // The walk is over and the first result may be a while: a large
            // file to hash, a lookup that is being retried. The record said
            // "Scanning ROM folders…" all through that.
            val record = ScanJobRecord.checking(jobId, total, startedAt)
            writePending(jobId, record)
            updateNotification(record.getString("message"), 0, 0)
        }) { p ->
            if (pace.due(p.processed, p.total)) {
                val record = ScanJobRecord.running(jobId, p, startedAt)
                writePending(jobId, record)
                // What the popup shows, so the two cannot come to differ.
                updateNotification(record.getString("message"), p.processed, p.total)
            }
        }

        val record = ScanJobRecord.finished(jobId, summary, raUser, startedAt)
        if (summary.aborted) writeError(jobId, record) else writePending(jobId, record)
    }

    // What the record says is ScanJobRecord's; this is where it goes.
    //
    // Through Paths.writeAtomic, as the error record already went. A record of
    // a scan under way had a temp file and a rename of its own, whose result
    // nobody looked at: a rename that failed left the record before it in
    // place and said nothing. The record is written directly when that happens.
    private fun writePending(jobId: String, record: JSONObject) =
        Paths.writeAtomic(Paths.pending(jobId), record.toString())

    // Done is marked only once the record is whole. Written in place it was
    // truncated first: a theme poll landing in that gap read an empty file,
    // took it for a finished job, and never showed the abort, the cancel or
    // the error this is the only record of.
    //
    // Nothing is thrown from here. This is called where a job is already
    // ending: by the two catches that end a scan, by a scan the pipeline
    // stopped, and for a start request that is turned away. When the data root
    // is full or read-only this write fails too, as a rule after the one that
    // ended the scan, and there is nobody left to hand that to. Thrown from a
    // catch it left the coroutine, and from a start request it left
    // onStartCommand before startForeground. It goes to the log, and the job
    // ends without the record: see CONTEXT.md §3 for what the theme makes of
    // that.
    private fun writeError(jobId: String, record: JSONObject) {
        try {
            writePending(jobId, record)
            Paths.markDone(jobId)
        } catch (t: Throwable) {
            Log.e(TAG, "Scan $jobId: could not write the error record", t)
        }
    }

    // ── Throttle ─────────────────────────────────────────────────────────

    private fun thermalDelayMs(): Long {
        // currentThermalStatus came with Android 10 and this installs from
        // Android 8, so the version is asked first. Before 10 a device does not
        // say how warm it is, and a scan there is not slowed. The call used to
        // be made on every version, and what kept Android 8 and 9 from it was
        // the catch alone: there it is a NoSuchMethodError.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Throwable, not Exception, as it had to be for that Error and as
            // it stays. The pipeline calls its throttle after the hash, outside
            // the try that makes a file's failure that file's alone, so what
            // gets out of here ends the scan.
            val status = try { powerManager.currentThermalStatus } catch (_: Throwable) { PowerManager.THERMAL_STATUS_NONE }
            return when (status) {
                PowerManager.THERMAL_STATUS_NONE     -> 0L
                PowerManager.THERMAL_STATUS_LIGHT    -> 0L      // softened
                PowerManager.THERMAL_STATUS_MODERATE -> 200L    // softened (was 600)
                PowerManager.THERMAL_STATUS_SEVERE   -> 800L    // softened (was 2000)
                PowerManager.THERMAL_STATUS_CRITICAL -> 3000L   // softened (was 5000)
                else                                  -> 5000L
            }
        }
        return 0L
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Pegasus Bridge — Hasher", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val cancelPi = PendingIntent.getService(
            this, 0,
            Intent(this, HasherService::class.java).apply { action = "CANCEL" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancelPi).build())
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, progress, max))

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PegasusBridge::ScanWakeLock")
            .apply { acquire(60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
}
