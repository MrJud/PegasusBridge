package com.pegasus.bridge

import android.app.Application
import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.Paths

class DataLayerApp : Application() {
    override fun onCreate() {
        // Before anything else: the code both shells compile logs to stderr
        // until this is set, and LogcatBridgeLog says what becomes of that here.
        BridgeLog.current = LogcatBridgeLog
        super.onCreate()
        Paths.ensureAll()
    }
}
