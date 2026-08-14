package dev.ccbuddy.app.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.ccbuddy.app.BuildConfig
import dev.ccbuddy.app.MDNS_SERVICE_TYPE
import dev.ccbuddy.app.WS_PORT

private const val TAG = "NsdHelper"

/**
 * Advertises this phone on the LAN as `_buddycc._tcp` (build-order step 7)
 * so `/buddy-scan` on the PC can find it without the user having to read
 * an IP off the pairing screen. Registration is best-effort — if NSD isn't
 * available (emulator, some routers block multicast) manual `/buddy-pair
 * <ip> <pin>` still works, so failures here are logged, not fatal.
 */
class NsdHelper(private val context: Context) {

    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register() {
        if (registrationListener != null) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "CC Buddy on ${Build.MODEL}"
            serviceType = MDNS_SERVICE_TYPE
            port = WS_PORT
            setAttribute("version", BuildConfig.VERSION_NAME)
            setAttribute("device", Build.MODEL)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "NSD registration threw, continuing without it", e)
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD unregistration threw", e)
        }
        registrationListener = null
    }
}
