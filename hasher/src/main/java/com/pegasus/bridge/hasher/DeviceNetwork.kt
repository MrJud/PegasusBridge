package com.pegasus.bridge.hasher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What Android says of the device's connection, for a scan whose request has
 * just failed. The lookup asks only then, and what [OfflineVerdict] makes of
 * the answer decides whether the scan stops as one with no internet.
 *
 * Only the reading is here. What the four answers come to is
 * [LinkState.from], in the sources this module shares with the desktop, where
 * it can be tested without a device.
 *
 * ConnectivityManager is fetched each time, and not once beside the service's
 * PowerManager: it is asked a few times in a scan at most, and whatever goes
 * wrong in here has to be an answer the lookup can take for "online". It takes
 * anything thrown that way, the SecurityException some Android 11 builds raise
 * from getNetworkCapabilities among them.
 */
internal object DeviceNetwork {

    fun state(context: Context): LinkState {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return LinkState.PRESENT
        // No default network is what airplane mode gives, the state the tablet
        // was in while a scan took 31 seconds to call RetroAchievements
        // unresponsive.
        val network = manager.activeNetwork
        // Null when the network went between the two calls. Not asked again:
        // the next request that fails will be.
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        return LinkState.from(
            network      = network != null,
            capabilities = capabilities != null,
            vpn          = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            validated    = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
    }
}
