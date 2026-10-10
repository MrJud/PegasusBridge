package com.pegasus.bridge.core

/**
 * The version the Bridge names itself by to the services it asks.
 *
 * It is the Android app's `versionName`, written a second time because the
 * desktop build has no file of the app's to read it from. A test holds the
 * two together, so that a release which raises one raises the other.
 */
object BridgeVersion {
    const val NAME = "1.0.0"
}
