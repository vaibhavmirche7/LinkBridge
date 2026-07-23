package com.vaibhavmirche.linkbridge.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import timber.log.Timber

data class DiscoveredPeer(val name: String, val host: String, val port: Int)

/**
 * Wraps Android's Network Service Discovery (mDNS/DNS-SD) so LinkBridge instances can find
 * each other on the same LAN/hotspot without anyone typing an IP address.
 *
 * This is deliberately NOT based on reading the hotspot's ARP/DHCP client table - that approach
 * is unreliable and increasingly restricted on modern Android for privacy reasons. NSD is the
 * Google-sanctioned way to do this: a device only shows up here if it is itself running
 * LinkBridge and actively advertising, which also means "LAN discovery" only ever surfaces
 * other LinkBridge devices, not every random device connected to the hotspot.
 */
class LanDiscoveryHelper(context: Context) {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val logger = Timber.tag("LanDiscoveryHelper")
    private val mainHandler = Handler(Looper.getMainLooper())

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryTimeoutRunnable: Runnable? = null

    /** Call once the Ktor server is actually up and listening on [port]. */
    fun advertise(port: Int, deviceLabel: String) {
        stopAdvertising() // clear any previous registration first
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceLabel.ifBlank { "LinkBridge" }
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                logger.i("NSD advertise: registered as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logger.w("NSD advertise failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                logger.i("NSD advertise: unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logger.w("NSD unregister failed: $errorCode")
            }
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { logger.w(it, "registerService threw") }
    }

    /** Call when the Ktor server stops. */
    fun stopAdvertising() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
    }

    /**
     * Browses for other LinkBridge instances for a short window and reports whatever was found.
     * [onResult] is always called exactly once, on the main thread.
     */
    fun discoverPeers(onResult: (List<DiscoveredPeer>) -> Unit) {
        stopDiscovery()
        val found = mutableListOf<DiscoveredPeer>()
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            discoveryTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            stopDiscovery()
            mainHandler.post { onResult(found.toList()) }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                logger.d("NSD discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                runCatching {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            logger.w("NSD resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            found.add(DiscoveredPeer(info.serviceName, host, info.port))
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.removeAll { it.name == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                logger.d("NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger.w("NSD discovery start failed: $errorCode")
                finish()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger.w("NSD discovery stop failed: $errorCode")
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { finish() }

        val timeout = Runnable { finish() }
        discoveryTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, DISCOVERY_WINDOW_MS)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }

    companion object {
        private const val SERVICE_TYPE = "_linkbridge._tcp."
        private const val DISCOVERY_WINDOW_MS = 4000L
    }
}
