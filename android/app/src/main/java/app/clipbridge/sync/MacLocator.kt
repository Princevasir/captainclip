package app.clipbridge.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.clipbridge.core.PairingInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Finds the paired Mac's current address via Bonjour/NSD (`_clipbridge._tcp`). Blocking. */
class MacLocator(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Returns host to port, or null if not found within [timeoutMs]. */
    @Suppress("DEPRECATION") // resolveService is fine for one-shot lookups on all supported versions
    fun find(info: PairingInfo, timeoutMs: Long = 3_000): Pair<String, Int>? {
        val done = CountDownLatch(1)
        val result = AtomicReference<Pair<String, Int>?>(null)
        val resolving = AtomicBoolean(false)

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName != info.serviceName || !resolving.compareAndSet(false, true)) return
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        if (host != null) result.set(host to resolved.port)
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
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)  // InterruptedException propagates to SyncLoop.stop()
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        return result.get()
    }

    private companion object {
        const val SERVICE_TYPE = "_clipbridge._tcp."
    }
}
