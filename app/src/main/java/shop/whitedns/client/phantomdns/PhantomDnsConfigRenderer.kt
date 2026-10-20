package shop.whitedns.client.phantomdns

import shop.whitedns.client.cottendns.CottenDnsConfigRenderer
import shop.whitedns.client.model.CottenDnsServerProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.resolve

/**
 * Renders a client config in PhantomDNS's own TOML dialect.
 *
 * PhantomDNS (github.com/DrAlanK/PhantomDNS) is a separate DNS-tunnel engine
 * that shares the same lineage as CottenDns but a leaner, older key set: a
 * single PACKET_DUPLICATION_COUNT (not split upload/download), no CONFIG_PRESET
 * / RESOLVER_TRANSPORT / QUERY_TYPES, and no CottenDns-only wire features. Only
 * keys that PhantomDNS actually reads are emitted here — CottenDns knobs are
 * deliberately not passed through, since PhantomDNS would ignore or reject them.
 *
 * The resolvers file format is identical across both engines (one entry per
 * line), so it is reused from [CottenDnsConfigRenderer].
 */
object PhantomDnsConfigRenderer {

    fun renderClientToml(
        serverProfile: CottenDnsServerProfile,
        settings: WhiteDnsSettings,
    ): String {
        val resolved = settings.resolve()
        // PhantomDNS clamps PACKET_DUPLICATION_COUNT to [1,10] and
        // SETUP_PACKET_DUPLICATION_COUNT to [dup,12]. Bias setup a little higher
        // so stream establishment survives loss, matching the sample profile.
        val duplication = resolved.uploadDuplication.coerceIn(1, 10)
        val setupDuplication = (duplication + 1).coerceIn(duplication, 12)

        return buildString {
            appendLine("DOMAINS = ${domainsToml(serverProfile.domain)}")
            appendLine("DATA_ENCRYPTION_METHOD = ${serverProfile.encryptionMethod}")
            appendLine("ENCRYPTION_KEY = \"${escape(serverProfile.encryptionKey)}\"")
            appendLine("PROTOCOL_TYPE = \"${escape(resolved.protocolType)}\"")
            appendLine("LISTEN_IP = \"${escape(resolved.listenIp)}\"")
            appendLine("LISTEN_PORT = ${resolved.listenPort}")
            appendLine("SOCKS5_AUTH = ${resolved.socks5Authentication}")
            appendLine("SOCKS5_USER = \"${escape(resolved.socksUsername)}\"")
            appendLine("SOCKS5_PASS = \"${escape(resolved.socksPassword)}\"")
            appendLine("LOCAL_DNS_ENABLED = ${resolved.localDnsEnabled}")
            appendLine("LOCAL_DNS_IP = \"127.0.0.1\"")
            appendLine("LOCAL_DNS_PORT = ${resolved.localDnsPort}")
            appendLine("RESOLVER_BALANCING_STRATEGY = ${resolved.balancingStrategy}")
            appendLine("PACKET_DUPLICATION_COUNT = $duplication")
            appendLine("SETUP_PACKET_DUPLICATION_COUNT = $setupDuplication")
            appendLine("UPLOAD_COMPRESSION_TYPE = ${resolved.uploadCompression}")
            appendLine("DOWNLOAD_COMPRESSION_TYPE = ${resolved.downloadCompression}")
            appendLine("BASE_ENCODE_DATA = ${resolved.baseEncodeData}")
            appendLine("MIN_UPLOAD_MTU = ${resolved.minUploadMtu}")
            appendLine("MIN_DOWNLOAD_MTU = ${resolved.minDownloadMtu}")
            appendLine("MAX_UPLOAD_MTU = ${resolved.maxUploadMtu}")
            appendLine("MAX_DOWNLOAD_MTU = ${resolved.maxDownloadMtu}")
            appendLine("MTU_TEST_RETRIES = ${resolved.mtuTestRetriesResolvers}")
            appendLine("MTU_TEST_TIMEOUT = ${resolved.mtuTestTimeoutResolvers}")
            appendLine("MTU_TEST_PARALLELISM = ${resolved.mtuTestParallelismResolvers}")
            appendLine("RX_TX_WORKERS = ${resolved.rxTxWorkers}")
            appendLine("TUNNEL_PROCESS_WORKERS = ${resolved.tunnelProcessWorkers}")
            // Advanced runtime knobs PhantomDNS reads under identical names and
            // semantics (shared lineage). Passing the app's resolved values makes
            // the user's advanced settings actually apply on PhantomDNS instead of
            // silently falling back to engine defaults. Keys PhantomDNS does not
            // have (TX_CHANNEL_SIZE, FAST_CONNECT, STARTUP_MODE, the *_RESOLVERS
            // MTU split, etc.) are intentionally not emitted.
            appendLine("TUNNEL_PACKET_TIMEOUT_SECONDS = ${resolved.tunnelPacketTimeoutSeconds}")
            appendLine("DISPATCHER_IDLE_POLL_INTERVAL_SECONDS = ${resolved.dispatcherIdlePollIntervalSeconds}")
            appendLine("RX_CHANNEL_SIZE = ${resolved.rxChannelSize}")
            appendLine("SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS = ${resolved.socksUdpAssociateReadTimeoutSeconds}")
            appendLine("CLIENT_TERMINAL_STREAM_RETENTION_SECONDS = ${resolved.clientTerminalStreamRetentionSeconds}")
            appendLine("CLIENT_CANCELLED_SETUP_RETENTION_SECONDS = ${resolved.clientCancelledSetupRetentionSeconds}")
            appendLine("SESSION_INIT_RETRY_BASE_SECONDS = ${resolved.sessionInitRetryBaseSeconds}")
            appendLine("SESSION_INIT_RETRY_STEP_SECONDS = ${resolved.sessionInitRetryStepSeconds}")
            appendLine("SESSION_INIT_RETRY_LINEAR_AFTER = ${resolved.sessionInitRetryLinearAfter}")
            appendLine("SESSION_INIT_RETRY_MAX_SECONDS = ${resolved.sessionInitRetryMaxSeconds}")
            appendLine("SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS = ${resolved.sessionInitBusyRetryIntervalSeconds}")
            appendLine("LOG_LEVEL = \"${escape(resolved.logLevel)}\"")
        }.trimEnd()
    }

    fun renderResolvers(settings: WhiteDnsSettings): String {
        return CottenDnsConfigRenderer.renderResolvers(settings)
    }

    private fun domainsToml(domain: String): String {
        val domains = domain.split(',')
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(domain.trim().trimEnd('.')) }
        return domains.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"${escape(it)}\"" }
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
