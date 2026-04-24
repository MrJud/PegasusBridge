package com.pegasus.bridge

import android.app.Application
import com.pegasus.bridge.core.Paths

class DataLayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Paths.ensureAll()
    }
}
