package dev.dreamz.droidterm.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Finds Wireless debugging endpoints with mDNS, the same way `adb mdns services` does:
 *   _adb-tls-pairing._tcp  the "Pair device with pairing code" dialog (only while it is open)
 *   _adb-tls-connect._tcp  the TLS port adbd listens on once Wireless debugging is on
 * Endpoints on this phone are flagged [Endpoint.isLocal] so they can be reached at 127.0.0.1.
 */
class WirelessDebugging(context: Context) {
    data class Endpoint(val name: String, val type: String, val host: String, val port: Int, val isLocal: Boolean) {
        val isPairing get() = type == PAIRING
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val _endpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    val endpoints: StateFlow<List<Endpoint>> = _endpoints.asStateFlow()

    fun localPairing() = _endpoints.value.firstOrNull { it.isLocal && it.isPairing }
    fun localConnect() = _endpoints.value.firstOrNull { it.isLocal && !it.isPairing }

    fun start() {
        if (listeners.isNotEmpty()) return
        for (type in listOf(PAIRING, CONNECT)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery $serviceType failed $errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) = resolve(info, type)
                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { _endpoints.value = _endpoints.value.filterNot { it.name == info.serviceName && it.type == type } }
                }
            }
            listeners += l
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        _endpoints.value = emptyList()
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, type: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(info, executor, object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    val addr = si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull()
                    if (addr != null) found(si.serviceName, type, addr, si.port)
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            // Before Android 14 only one resolve may run at a time.
            executor.execute {
                val lock = Object()
                synchronized(lock) {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { synchronized(lock) { lock.notify() } }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            si.host?.let { found(si.serviceName, type, it, si.port) }
                            synchronized(lock) { lock.notify() }
                        }
                    })
                    lock.wait(5000)
                }
            }
        }
    }

    private fun found(name: String, type: String, addr: InetAddress, port: Int) {
        val ep = Endpoint(name, type, addr.hostAddress ?: return, port, isLocalAddress(addr))
        main.post {
            _endpoints.value = _endpoints.value.filterNot { it.name == name && it.type == type } + ep
        }
    }

    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
        private const val TAG = "WirelessDebugging"

        fun isLocalAddress(addr: InetAddress): Boolean = addr.isLoopbackAddress || runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { ni -> ni.inetAddresses.toList().any { it == addr } }
        }.getOrDefault(false)
    }
}
