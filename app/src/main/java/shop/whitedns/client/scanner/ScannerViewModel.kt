package shop.whitedns.client.scanner

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import shop.whitedns.client.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import mobile.Scanner
import org.json.JSONArray
import org.json.JSONObject

internal object ScannerRepository {
    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var scanner: Scanner? = null
    @Synchronized fun get(application: Application): Scanner = scanner ?: Mobile.newScanner(
        File(application.filesDir, "scanner").absolutePath,
    ).also { scanner = it }
}

data class ScannerUiState(
    val ready: Boolean = false,
    val catalog: JSONObject = JSONObject(),
    val status: JSONObject = JSONObject(),
    val rows: List<JSONObject> = emptyList(),
    val reports: List<JSONObject> = emptyList(),
    val mode: String = "http",
    val provider: String = "cloudflare",
    val targetType: String = "ip",
    val fields: Map<String, String> = emptyMap(),
    val page: Int = 0,
    val message: ScannerMessage? = null,
    val profileLoading: Boolean = false,
    val speedBusy: Boolean = false,
    val speedResult: JSONObject? = null,
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("full_scanner", 0)
    private val mutable = MutableStateFlow(ScannerUiState())
    val state = mutable.asStateFlow()
    private lateinit var scanner: Scanner
    @Volatile private var requested = false
    private var lastRows = -1
    private var lastRun = ""
    private var serviceStarted = false
    private val profiles = ScannerProfileStore(
        ScannerRepository.persistenceScope,
        read = { key ->
            val stored = JSONObject(prefs.getString(key, "{}") ?: "{}")
            stored.keys().asSequence().associateWith { stored.optString(it) }
        },
        write = { key, fields ->
            check(prefs.edit().putString(key, JSONObject(fields).toString()).commit()) { "Profile storage failed" }
        },
        onError = { reportError(it, R.string.scanner_error_profile) },
    )
    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                scanner = ScannerRepository.get(application)
                mutable.update { it.copy(
                    ready = true, catalog = JSONObject(scanner.catalog()),
                ) }
                val key = profileKey()
                val loaded = profiles.load(key)
                mutable.update { if (profileKey(it) == key) it.copy(fields = loaded) else it }
            }.onFailure { reportError(it, R.string.scanner_error_load) }
            while (isActive && mutable.value.ready) {
                runCatching {
                    val status = JSONObject(scanner.snapshot())
                    val busy = status.optBoolean("busy")
                    mutable.update { it.copy(status = status) }
                    if (status.optInt("rows") != lastRows || status.optString("run") != lastRun) {
                        lastRows = status.optInt("rows"); lastRun = status.optString("run")
                        refreshRows()
                    }
                    if (!busy && serviceStarted) {
                        application.stopService(Intent(application, FullScannerService::class.java))
                        serviceStarted = false
                        refreshReports()
                    }
                }.onFailure { reportError(it, R.string.scanner_error_update) }
                delay(500)
            }
        }
    }
    private fun profileKey(state: ScannerUiState = mutable.value): String = with(state) { "profile/$mode/$provider/$targetType" }
    fun edit(key: String, value: String) {
        if (mutable.value.profileLoading) return
        editProfile(profileKey(), key, value)
    }
    private fun editProfile(owner: String, key: String, value: String) {
        val state = mutable.value
        val base = if (owner == profileKey(state)) state.fields else profiles.cached(owner).orEmpty()
        val fields = base.toMutableMap().apply { put(key, value); if (key == "targets") remove("inputPath") }.toMap()
        profiles.update(owner, fields)
        mutable.update { if (profileKey(it) == owner) it.copy(fields = fields) else it }
    }
    fun select(mode: String = mutable.value.mode, provider: String = mutable.value.provider, type: String = mutable.value.targetType) {
        val selected = mutable.value.copy(mode = mode, provider = provider, targetType = type)
        val key = profileKey(selected)
        val cached = profiles.cached(key)
        mutable.update { it.copy(mode = mode, provider = provider, targetType = type, fields = cached.orEmpty(), profileLoading = cached == null) }
        if (cached == null) viewModelScope.launch(Dispatchers.IO) {
            runCatching { profiles.load(key) }
                .onSuccess { fields -> mutable.update { if (profileKey(it) == key) it.copy(fields = fields, profileLoading = false) else it } }
                .onFailure { error -> mutable.update { if (profileKey(it) == key) it.copy(profileLoading = false) else it }; reportError(error, R.string.scanner_error_profile) }
        }
    }
    override fun onCleared() { profiles.closeAndFlush(); super.onCleared() }
    fun field(key: String, fallback: String) = mutable.value.fields[key] ?: fallback
    fun message(id: Int, details: String = "", arguments: List<Any> = emptyList()) { mutable.update { it.copy(message = ScannerMessage(id, details, arguments)) } }
    fun reportError(error: Throwable, fallback: Int) {
        if (error is ScannerInputException) message(error.resource, arguments = error.arguments.toList())
        else message(fallback, error.message.orEmpty())
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun importFile(uri: Uri) {
        val owner = profileKey()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val root = File(getApplication<Application>().filesDir, "scanner/imports").apply { mkdirs() }
                val file = File(root, "${UUID.randomUUID()}.txt")
                getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw ScannerInputException(R.string.scanner_file_unreadable)
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; if (total > 256L * 1024 * 1024) throw ScannerInputException(R.string.scanner_file_limit)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                editProfile(owner, "inputPath", file.absolutePath); message(R.string.scanner_file_imported)
            }.onFailure { reportError(it, R.string.scanner_error_import) }
        }
    }
    fun start() {
        if (!mutable.value.ready || mutable.value.profileLoading || requested || mutable.value.status.optBoolean("busy")) return
        val startState = mutable.value
        requested = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                profiles.flush()
                val state = startState
                val f = state.fields
                fun text(key: String, fallback: String) = f[key] ?: fallback
                fun number(key: String, fallback: String): Int = text(key, fallback).toIntOrNull()
                    ?: throw ScannerInputException(R.string.scanner_whole_number, scannerText(getApplication(), scannerFieldLabel(key)))
                fun decimal(key: String, fallback: String): Double = text(key, fallback).toDoubleOrNull()
                    ?: throw ScannerInputException(R.string.scanner_number, scannerText(getApplication(), scannerFieldLabel(key)))
                val ports = parseScannerPorts(text("ports", ""))
                val options = JSONObject()
                    .put("targetType", state.targetType)
                    .put("timeoutSecs", number("timeout", "10"))
                    .put("retryCount", number("retries", "2"))
                    .put("maxConcurrent", number("concurrency", "200"))
                    .put("minConcurrent", if (text("automatic", "true") == "true") number("minimum", "20") else 1)
                    .put("autoConcurrency", text("automatic", "true") == "true")
                    .put("countTotal", text("countTotal", "false") == "true")
                    .put("customPorts", JSONArray(ports))
                    .put("userAgent", text("userAgent", "WhiteDNS Scanner/1.0"))
                    .put("spoofedSNI", text("sni", "www.speedtest.net"))
                    .put("proxyTestURL", text("proxyUrl", "https://example.com/"))
                    .put("targetDomain", text("dnsDomain", "google.com"))
                    .put("dnsTxtDomain", text("txtDomain", ""))
                    .put("dnsMaxPingMs", number("dnsPing", "20000"))
                    .put("dnsRateLimitPerSecond", decimal("dnsRate", "0"))
                    .put("dnsRateLimitPerResolverPerSecond", decimal("dnsRatePerResolver", "0"))
                    .put("dnsRateLimitBurst", number("dnsBurst", "1"))
                    .put("dnsTimingJitter", decimal("dnsJitter", "0"))
                    .put("antiDPI", text("antiDpi", "false") == "true")
                    .put("dpiFragmentSize", number("fragmentSize", "64"))
                    .put("dpiFragmentDelayMs", number("fragmentDelay", "1"))
                if (f.containsKey("domains")) options.put("probeDomains", JSONArray(text("domains", "").split(Regex("[\\s,]+" )).filter { it.isNotBlank() }))
                val request = JSONObject().put("mode", state.mode).put("provider", state.provider)
                    .put("targets", text("targets", "")).put("inputPath", text("inputPath", "")).put("options", options)
                scanner.start(request.toString())
                mutable.update { it.copy(page = 0, speedResult = null, status = JSONObject(scanner.snapshot())) }
                lastRows = -1
                ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), FullScannerService::class.java))
                serviceStarted = true
            }.onFailure { reportError(it, R.string.scanner_error_start) }
            requested = false
        }
    }
    fun control(action: String) { viewModelScope.launch(Dispatchers.IO) { runCatching {
        when (action) { "pause" -> scanner.pause(); "resume" -> scanner.resume(); "stop" -> scanner.stop(); "cancel-speed" -> scanner.cancelSpeedTest() }
    }.onFailure { reportError(it, R.string.scanner_error_control) } } }
    private fun refreshRows() {
        val page = mutable.value.page
        val run = mutable.value.status.optString("run")
        val rows = jsonObjects(JSONArray(scanner.results((page * 100).toLong(), 100)))
        // A page change during an IO read must not replace the newer page.
        if (page == mutable.value.page && run == JSONObject(scanner.snapshot()).optString("run")) mutable.update { it.copy(rows = rows) } else lastRows = -1
    }
    fun page(page: Int) { mutable.update { it.copy(page = page.coerceAtLeast(0)) }; viewModelScope.launch(Dispatchers.IO) { refreshRows() } }
    fun refreshReports() { if (mutable.value.ready) viewModelScope.launch(Dispatchers.IO) {
        runCatching { mutable.update { it.copy(reports = jsonObjects(JSONArray(scanner.reports()))) } }
            .onFailure { reportError(it, R.string.scanner_error_reports) }
    } }
    fun loadRun(run: String) { viewModelScope.launch(Dispatchers.IO) {
        runCatching { scanner.loadRun(run); mutable.update { it.copy(page = 0, status = JSONObject(scanner.snapshot()), speedResult = null) }; lastRows = -1; refreshRows() }
            .onFailure { reportError(it, R.string.scanner_error_saved) }
    } }
    fun export(path: String, uri: Uri) { viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val root = File(getApplication<Application>().filesDir, "scanner/runs").canonicalFile
            val file = File(path).canonicalFile
            if (!file.toPath().startsWith(root.toPath())) throw ScannerInputException(R.string.scanner_report_select)
            getApplication<Application>().contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output); file.inputStream().use { it.copyTo(output) }
            }
            message(R.string.scanner_exported)
        }.onFailure { reportError(it, R.string.scanner_error_export) }
    } }
    fun speed(run: String, sequence: Int) {
        if (mutable.value.speedBusy) return
        mutable.update { it.copy(speedBusy = true, speedResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val result = scanner.testSpeed(run, sequence.toLong(), field("downloadUrl", "https://speed.cloudflare.com/__down?bytes=25000000"), field("speedDuration", "10").toLong(), field("speedMb", "25").toLong())
                mutable.update { it.copy(speedResult = JSONObject(result)) }
            }.onFailure { reportError(it, R.string.scanner_error_speed) }
            mutable.update { it.copy(speedBusy = false) }
        }
    }
}

internal fun jsonObjects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
internal fun parseScannerPorts(raw: String): List<Int> {
    val ports = linkedSetOf<Int>()
    raw.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }.forEach { token ->
        val range = token.split('-'); if (range.size !in 1..2) throw ScannerInputException(R.string.scanner_port_syntax)
        val first = range[0].toIntOrNull() ?: throw ScannerInputException(R.string.scanner_invalid_port, token)
        val last = if (range.size == 2) range[1].toIntOrNull() ?: throw ScannerInputException(R.string.scanner_invalid_range, token) else first
        if (first !in 1..65535 || last !in first..65535) throw ScannerInputException(R.string.scanner_port_bounds)
        if (ports.size + last - first >= 2048) throw ScannerInputException(R.string.scanner_port_limit)
        ports.addAll(first..last)
    }
    return ports.toList()
}

private fun scannerFieldLabel(key: String): Int = when (key) {
    "timeout" -> R.string.scanner_timeout
    "retries" -> R.string.scanner_retries
    "concurrency" -> R.string.scanner_workers_max
    "minimum" -> R.string.scanner_workers_min
    "dnsPing" -> R.string.scanner_dns_ping
    "dnsRate" -> R.string.scanner_dns_rate
    "dnsRatePerResolver" -> R.string.scanner_dns_rate_resolver
    "dnsBurst" -> R.string.scanner_dns_burst
    "dnsJitter" -> R.string.scanner_dns_jitter
    "fragmentSize" -> R.string.scanner_fragment_size
    "fragmentDelay" -> R.string.scanner_fragment_delay
    else -> R.string.scanner_targets
}
