package shop.whitedns.client.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import shop.whitedns.client.model.ConnectionProfile

// A local listening socket does not establish that Max can carry tunnel data.
class CottenDnsTunnelReadiness(tunnelMode: String, engine: String) {
    val required = ConnectionProfile.normalizeTunnelMode(tunnelMode) == "cottenmax" &&
        ConnectionProfile.normalizeEngine(engine) == ConnectionProfile.EngineCottenDns
    private val verified = AtomicBoolean(!required)
    private val failure = AtomicReference<String?>(null)

    fun observe(line: String) {
        if (!required) return
        if (parseCottenDnsConnectionProgressLine(line)?.phase == "connected") {
            verified.set(true)
        }
        if (line.contains("CottenMax authentication failed") || line.contains("tunnel transfer verification failed")) {
            failure.compareAndSet(null, "CottenMax could not verify tunnel data. Check the matching server update, domain/key and resolvers.")
        }
    }

    fun isReady(): Boolean = verified.get()
    fun startupFailure(): String? = if (isReady()) null else failure.get()
}
