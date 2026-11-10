package app.clipbridge.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.clipbridge.core.PairingInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds the paired Mac's current address via Bonjour/NSD (`_clipbridge._tcp`). Blocking. */
class MacLocator(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Returns host to port, or null if not found within [timeoutMs]. */
    @Suppress("DEPRECATION") // resolveService is fine for one-shot lookups on all supported versions
    fun find(info: PairingInfo, timeoutMs: Long = 3_000): Pair<String, Int>? {
        val done = CountDownLatch(1)
        var result: Pair<String, Int>? = null
        var resolving = false

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName != info.serviceName || resolving) return
                resolving = true
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        if (host != null) result = host to resolved.port
                        done.countDown()
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, error: Int) { done.countDown() }
                })
            }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(discovery) }
        return result
    }

    private companion object {
        const val SERVICE_TYPE = "_clipbridge._tcp."
    }
}
