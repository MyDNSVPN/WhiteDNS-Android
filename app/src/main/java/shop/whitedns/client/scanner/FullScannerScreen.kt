package shop.whitedns.client.scanner

import android.app.Application
import android.content.ClipData
import shop.whitedns.client.model.WhiteDnsSettings
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import shop.whitedns.client.R

@Composable
fun ScannerHub(settings: WhiteDnsSettings, onSettingsChange: (WhiteDnsSettings) -> Unit, tunnel: @Composable () -> Unit) {
    var full by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(full, { full = true }, { Text(scannerString(R.string.scanner_hub_full)) }, Modifier.heightIn(min = 48.dp))
            FilterChip(!full, { full = false }, { Text(scannerString(R.string.scanner_hub_tunnel)) }, Modifier.heightIn(min = 48.dp))
        }
        Box(Modifier.weight(1f)) { if (full) FullScannerScreen(settings, onSettingsChange) else tunnel() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScannerScreen(settings: WhiteDnsSettings, onSettingsChange: (WhiteDnsSettings) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ScannerViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    val ui by vm.state.collectAsStateWithLifecycle()
    val clipboardScope = rememberCoroutineScope()
    run {
        var page by rememberSaveable { mutableStateOf("scan") }
        var detail by remember { mutableStateOf<JSONObject?>(null) }
        var detailRun by remember { mutableStateOf("") }
        var detailSeq by remember { mutableIntStateOf(0) }
        var exportPath by rememberSaveable { mutableStateOf("") }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.export(exportPath, uri) }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri) }
        val snackbar = remember { SnackbarHostState() }
        val messageText = ui.message?.let { message -> scannerString(message.id, *message.arguments.toTypedArray()) + if (message.details.isBlank()) "" else "\n" + message.details }.orEmpty()
        LaunchedEffect(messageText) { if (messageText.isNotBlank()) { snackbar.showSnackbar(messageText); vm.clearMessage() } }
        LaunchedEffect(page) { if (page == "reports") vm.refreshReports() }
        BackHandler(enabled = detail != null || page != "scan") { if (detail != null) detail = null else page = "scan" }
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(painterResource(R.drawable.scanner_logo), null, Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(4.dp))
                        Text(scannerString(R.string.scanner_brand), style = MaterialTheme.typography.titleLarge)
                    }
                    PrimaryScrollableTabRow(selectedTabIndex = listOf("scan", "results", "reports", "settings").indexOf(page), edgePadding = 8.dp) {
                        listOf("scan" to scannerString(R.string.scanner_tab_scan), "results" to scannerString(R.string.scanner_tab_results), "reports" to scannerString(R.string.scanner_tab_reports), "settings" to scannerString(R.string.scanner_tab_settings)).forEach { (id, label) ->
                            Tab(page == id, { page = id }, text = { Text(label) })
                        }
                    }
                }
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val expanded = maxWidth >= 840.dp
                if (!ui.ready) {
                    Column(Modifier.padding(24.dp)) { if (messageText.isBlank()) CircularProgressIndicator(); Text(messageText.ifBlank { scannerString(R.string.scanner_loading) }) }
                } else if (page == "scan" && expanded) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        ScannerForm(vm, ui, { import.launch(arrayOf("text/*", "application/octet-stream")) }, Modifier.weight(1f))
                        ScannerResults(ui, vm, { row, seq -> detail = row; detailRun = ui.status.optString("run"); detailSeq = seq }, Modifier.weight(1f))
                    }
                } else when (page) {
                    "scan" -> ScannerForm(vm, ui, { import.launch(arrayOf("text/*", "application/octet-stream")) })
                    "results" -> ScannerResults(ui, vm, { row, seq -> detail = row; detailRun = ui.status.optString("run"); detailSeq = seq })
                    "reports" -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text(scannerString(R.string.scanner_reports_title), style = MaterialTheme.typography.headlineSmall); Text(scannerString(R.string.scanner_reports_help), style = MaterialTheme.typography.bodyMedium) }
                        if (ui.reports.isEmpty()) item { Text(scannerString(R.string.scanner_reports_empty)) }
                        itemsIndexed(ui.reports, key = { _, item -> item.getString("path") }) { _, item ->
                            ListItem(headlineContent = { Text(item.getString("name")) }, supportingContent = { Text(scannerString(R.string.scanner_report_summary, item.getString("run"), item.optLong("bytes") / 1024)) }, trailingContent = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (item.getString("name") == "results.csv") IconButton({ vm.loadRun(item.getString("run")); page = "results" }) { Icon(Icons.Rounded.Visibility, scannerString(R.string.scanner_view_saved)) }; IconButton({ exportPath = item.getString("path"); export.launch(item.getString("name")) }) { Icon(Icons.Rounded.SaveAlt, scannerString(R.string.scanner_export)) } } })
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { Text(scannerString(R.string.scanner_appearance), style = MaterialTheme.typography.headlineSmall) }
                        item { ScannerChoice(scannerString(R.string.scanner_theme), settings.themeMode, listOf("system" to scannerString(R.string.scanner_system), "light" to scannerString(R.string.scanner_light), "dark" to scannerString(R.string.scanner_dark))) { onSettingsChange(settings.copy(themeMode = it)) } }
                        item { ScannerChoice(scannerString(R.string.scanner_palette), settings.themeAccent, listOf("purple" to scannerString(R.string.scanner_purple), "teal" to scannerString(R.string.scanner_teal), "milk" to scannerString(R.string.scanner_milk))) { onSettingsChange(settings.copy(themeAccent = it)) } }
                        item { Text(scannerString(R.string.scanner_speed_title), style = MaterialTheme.typography.titleLarge) }
                        item { ScannerField(vm, ui, "downloadUrl", scannerString(R.string.scanner_download_url), "https://speed.cloudflare.com/__down?bytes=25000000") }
                        item { ScannerField(vm, ui, "speedDuration", scannerString(R.string.scanner_speed_seconds), "10", number = true) }
                        item { ScannerField(vm, ui, "speedMb", scannerString(R.string.scanner_speed_mb), "25", number = true) }
                        item { Text(scannerString(R.string.scanner_speed_help)) }
                    }
                }
            }
        }
        if (detail != null) {
            val selected = detail!!
            val clipboard = LocalClipboard.current
            ModalBottomSheet(onDismissRequest = { detail = null }) {
                LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(selected.optString("Label"), style = MaterialTheme.typography.titleLarge) }
                    item { Text(scannerString(R.string.scanner_endpoint_summary, selected.optString("ResolvedIP"), selected.optInt("Port"), selected.optInt("LatencyMs")), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)) }
                    item { Text(selected.optString("Error").ifBlank { if (selected.optBoolean("IsPoisoned")) scannerString(R.string.scanner_poisoned) else if (selected.optString("DnsProtocol").isNotBlank()) scannerString(R.string.scanner_dns_response) else scannerString(R.string.scanner_http_reachable, selected.optInt("Status")) }) }
                    item { Text(listOf("ServiceSummary", "DnsAnswer", "HdrDump", "TunnelReason").map { selected.optString(it) }.filter { it.isNotBlank() }.joinToString("\n\n")) }
                    item { OutlinedButton({ clipboardScope.launch {
                        runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("WhiteDNS", selected.optString("URL")))) }
                            .onSuccess { vm.message(R.string.scanner_copied) }
                            .onFailure { vm.reportError(it, R.string.scanner_error_clipboard) }
                    } }, Modifier.heightIn(min = 48.dp)) { Text(scannerString(R.string.scanner_copy)) } }
                    if (selected.optString("Error").isBlank() && selected.optString("DnsProtocol").isBlank()) item {
                        Button({ vm.speed(detailRun, detailSeq) }, enabled = !ui.speedBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (ui.speedBusy) scannerString(R.string.scanner_speed_testing) else scannerString(R.string.scanner_speed_start)) }
                    }
                    if (ui.speedBusy) item { OutlinedButton({ vm.control("cancel-speed") }, Modifier.heightIn(min = 48.dp)) { Text(scannerString(R.string.scanner_speed_cancel)) } }
                    ui.speedResult?.let { speed -> item { Text(scannerString(R.string.scanner_speed_summary, speed.optDouble("downloadMbps"), speed.optLong("bytes"), speed.optLong("latencyMs")), style = MaterialTheme.typography.titleMedium) } }
                }
            }
        }
    }
}

@Composable
private fun ScannerForm(vm: ScannerViewModel, ui: ScannerUiState, import: () -> Unit, modifier: Modifier = Modifier) {
    val mode = ui.mode
    val clean = mode in listOf("http", "http-all", "custom")
    val dns = mode in listOf("dns", "dns-udptcp", "txt")
    val proxy = mode.endsWith("proxy")
    val modes = jsonObjects(ui.catalog.getJSONArray("modes"))
    val providers = jsonObjects(ui.catalog.getJSONArray("providers"))
    val provider = providers.first { it.optString("id") == ui.provider }
    val categories = listOf("clean" to scannerString(R.string.scanner_category_clean), "http-proxy" to scannerString(R.string.scanner_category_http), "socks-proxy" to scannerString(R.string.scanner_category_socks), "dns" to scannerString(R.string.scanner_category_dns), "sni" to scannerString(R.string.scanner_category_sni))
    val group = if (clean) "clean" else if (dns) "dns" else mode
    var advanced by rememberSaveable { mutableStateOf(false) }
    val busy = ui.status.optBoolean("busy")
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (ui.profileLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(scannerString(R.string.scanner_loading_profile)) }
        item { ScannerChoice(scannerString(R.string.scanner_scan_type), group, categories) { vm.select(mode = when (it) { "clean" -> "http"; "dns" -> "dns"; else -> it }) } }
        if (clean) {
            item { ScannerChoice(scannerString(R.string.scanner_targets), ui.targetType, listOf("ip" to scannerString(R.string.scanner_target_ip), "domain" to scannerString(R.string.scanner_target_domain))) { vm.select(type = it) } }
            item { ScannerChoice(scannerString(R.string.scanner_provider), ui.provider, providers.map { it.getString("id") to it.getString("name") }) { vm.select(mode = "http", provider = it) } }
        }
        if (clean || dns) item {
            val filtered = modes.filter { if (clean) it.getString("id") in setOf("http", "http-all", "custom") && provider.getJSONArray("modes").toString().contains("\"${it.getString("id")}\"") else it.getString("id") in setOf("dns", "dns-udptcp", "txt") }
            ScannerChoice(scannerString(R.string.scanner_scan_mode), mode, filtered.map { it.getString("id") to it.getString("name") }) { vm.select(mode = it) }
        }
        item {
            Text(scannerModeHint(mode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (clean) Text(scannerString(R.string.scanner_provider_help), style = MaterialTheme.typography.bodyMedium)
        }
        item { ScannerField(vm, ui, "targets", if (dns) scannerString(R.string.scanner_resolvers) else if (proxy) scannerString(R.string.scanner_proxies) else scannerString(R.string.scanner_targets_lines), "", lines = 5) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(import, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(8.dp)); Text(scannerString(R.string.scanner_import)) }
                if (clean && ui.targetType == "domain") OutlinedButton({ vm.edit("targets", (0 until provider.getJSONArray("hosts").length()).joinToString("\n") { provider.getJSONArray("hosts").getString(it) }) }, Modifier.heightIn(min = 48.dp)) { Text(scannerString(R.string.scanner_provider_defaults)) }
            }
            if (!ui.fields["inputPath"].isNullOrBlank()) Text(scannerString(R.string.scanner_import_selected), style = MaterialTheme.typography.bodySmall)
        }
        if (mode == "custom" || !clean) item { ScannerField(vm, ui, "ports", if (mode == "custom") scannerString(R.string.scanner_ports_required) else scannerString(R.string.scanner_ports_optional), "", hint = scannerString(R.string.scanner_ports_help)) }
        if (mode == "sni") item { ScannerField(vm, ui, "sni", scannerString(R.string.scanner_sni), "www.speedtest.net", hint = scannerString(R.string.scanner_sni_help)) }
        if (proxy) item { ScannerField(vm, ui, "proxyUrl", scannerString(R.string.scanner_proxy_url), "https://example.com/") }
        if (dns) {
            item { ScannerField(vm, ui, if (mode == "txt") "txtDomain" else "dnsDomain", if (mode == "txt") scannerString(R.string.scanner_txt_domain) else scannerString(R.string.scanner_dns_domain), if (mode == "txt") "" else "google.com") }
            item { ScannerField(vm, ui, "dnsPing", scannerString(R.string.scanner_dns_ping), "20000", number = true) }
        }
        item { HorizontalDivider(); Text(scannerString(R.string.scanner_concurrency), style = MaterialTheme.typography.titleLarge) }
        item { ScannerToggle(vm, ui, "automatic", scannerString(R.string.scanner_automatic), true) }
        item { ScannerField(vm, ui, "concurrency", if (ui.fields["automatic"] != "false") scannerString(R.string.scanner_workers_max) else scannerString(R.string.scanner_workers_manual), "200", number = true) }
        if (ui.fields["automatic"] != "false") item { ScannerField(vm, ui, "minimum", scannerString(R.string.scanner_workers_min), "20", number = true) }
        item { Text(scannerString(R.string.scanner_concurrency_help), style = MaterialTheme.typography.bodyMedium) }
        if (clean || proxy) {
            item { HorizontalDivider(); ScannerToggle(vm, ui, "antiDpi", scannerString(R.string.scanner_antidpi), false) }
            if (ui.fields["antiDpi"] == "true") {
                item { ScannerField(vm, ui, "fragmentSize", scannerString(R.string.scanner_fragment_size), "64", number = true) }
                item { ScannerField(vm, ui, "fragmentDelay", scannerString(R.string.scanner_fragment_delay), "1", number = true) }
                item { Text(scannerString(R.string.scanner_antidpi_help), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { TextButton({ advanced = !advanced }, Modifier.heightIn(min = 48.dp)) { Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null); Text(if (advanced) scannerString(R.string.scanner_advanced_hide) else scannerString(R.string.scanner_advanced)) } }
        if (advanced) {
            item { ScannerField(vm, ui, "timeout", scannerString(R.string.scanner_timeout), "10", number = true) }
            item { ScannerField(vm, ui, "retries", scannerString(R.string.scanner_retries), "2", number = true) }
            item { ScannerField(vm, ui, "userAgent", scannerString(R.string.scanner_user_agent), "WhiteDNS Scanner/1.0") }
            item { ScannerToggle(vm, ui, "countTotal", scannerString(R.string.scanner_count), false) }
            if (clean) item { ScannerField(vm, ui, "domains", scannerString(R.string.scanner_domains), (0 until provider.getJSONArray("probeDomains").length()).joinToString("\n") { provider.getJSONArray("probeDomains").getString(it) }, lines = 5) }
            if (dns) {
                item { ScannerField(vm, ui, "dnsRate", scannerString(R.string.scanner_dns_rate), "0", number = true) }
                item { ScannerField(vm, ui, "dnsRatePerResolver", scannerString(R.string.scanner_dns_rate_resolver), "0", number = true) }
                item { ScannerField(vm, ui, "dnsBurst", scannerString(R.string.scanner_dns_burst), "1", number = true) }
                item { ScannerField(vm, ui, "dnsJitter", scannerString(R.string.scanner_dns_jitter), "0", number = true) }
            }
        }
        item { HorizontalDivider(); ScannerStatus(ui) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(vm::start, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && !ui.speedBusy && !ui.profileLoading) { Icon(Icons.Rounded.PlayArrow, null); Text(scannerString(R.string.scanner_start)) }
                if (busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.control(if (ui.status.optString("state") == "paused") "resume" else "pause") }, Modifier.heightIn(min = 48.dp)) { Text(if (ui.status.optString("state") == "paused") scannerString(R.string.scanner_resume) else scannerString(R.string.scanner_pause)) }
                    OutlinedButton({ vm.control("stop") }, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Stop, null); Text(scannerString(R.string.scanner_stop_scan)) }
                }
            }
        }
    }
}

@Composable private fun ScannerStatus(ui: ScannerUiState) {
    val st = ui.status
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(scannerString(R.string.scanner_status_summary, scannerStateLabel(st.optString("state", "idle")), st.optInt("done"), st.optInt("open")), style = MaterialTheme.typography.titleMedium)
        if (st.optBoolean("busy")) {
            if (st.optInt("total") > 0) LinearProgressIndicator(progress = { (st.optInt("done").toFloat() / st.optInt("total")).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(scannerString(R.string.scanner_streaming)) }
        }
        if (st.optString("error").isNotBlank()) Text(st.optString("error"), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun ScannerResults(ui: ScannerUiState, vm: ScannerViewModel, inspect: (JSONObject, Int) -> Unit, modifier: Modifier = Modifier) {
    var filter by rememberSaveable { mutableStateOf("all") }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(scannerString(R.string.scanner_live_results), style = MaterialTheme.typography.headlineSmall); ScannerStatus(ui) }
        item { ScannerChoice(scannerString(R.string.scanner_filter), filter, listOf("all" to scannerString(R.string.scanner_filter_all), "passed" to scannerString(R.string.scanner_reachable), "failed" to scannerString(R.string.scanner_filter_failed))) { filter = it } }
        if (ui.rows.isEmpty()) item { Text(scannerString(R.string.scanner_results_empty)) }
        itemsIndexed(ui.rows, key = { index, _ -> "${ui.status.optString("run")}/${ui.page * 100 + index}" }) { index, row ->
            val passed = row.optString("Error").isBlank() && !row.optBoolean("IsPoisoned")
            if (filter == "all" || (filter == "passed") == passed) ListItem(
                modifier = Modifier.clickable(role = Role.Button) { inspect(row, ui.page * 100 + index + 1) }.heightIn(min = 64.dp),
                leadingContent = { Icon(if (passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, if (passed) scannerString(R.string.scanner_reachable) else scannerString(R.string.scanner_failed), tint = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
                headlineContent = { Text(row.optString("Label")) },
                supportingContent = { Text(scannerString(R.string.scanner_proto_latency, row.optString("DnsProtocol").ifBlank { ":${row.optInt("Port")}" }, row.optInt("LatencyMs")) + "\n" + row.optString("Error").ifBlank { if (row.optBoolean("IsPoisoned")) scannerString(R.string.scanner_poisoned) else if (row.optString("DnsProtocol").isNotBlank()) scannerString(R.string.scanner_dns_response) else if (row.optInt("ServiceTotal") > 0) scannerString(R.string.scanner_service_result, row.optInt("ServicePassed"), row.optInt("ServiceTotal"), row.optInt("Status")) else scannerString(R.string.scanner_http_status, row.optInt("Status")) }) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
            )
        }
        item {
            Column {
                Text(scannerString(R.string.scanner_page_summary, ui.page + 1))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.page(ui.page - 1) }, enabled = ui.page > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(scannerString(R.string.scanner_previous)) }
                    OutlinedButton({ vm.page(ui.page + 1) }, enabled = (ui.page + 1) * 100 < ui.status.optInt("rows"), modifier = Modifier.heightIn(min = 48.dp)) { Text(scannerString(R.string.scanner_next)) }
                }
            }
        }
    }
}

@Composable private fun ScannerField(vm: ScannerViewModel, ui: ScannerUiState, key: String, label: String, fallback: String, lines: Int = 1, number: Boolean = false, hint: String = "") {
    OutlinedTextField(value = ui.fields[key] ?: fallback, onValueChange = { vm.edit(key, it) }, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, minLines = lines, maxLines = maxOf(lines, 8), singleLine = lines == 1,
        enabled = !ui.profileLoading, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = if (hint.isNotBlank()) ({ Text(hint) }) else null)
}
@Composable private fun ScannerToggle(vm: ScannerViewModel, ui: ScannerUiState, key: String, label: String, fallback: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f)); Switch(ui.fields[key]?.toBooleanStrictOrNull() ?: fallback, { vm.edit(key, it.toString()) }, modifier = Modifier.semantics { contentDescription = label })
    }
}
@Composable private fun ScannerChoice(label: String, value: String, options: List<Pair<String, String>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(12.dp)) {
                Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, scannerString(R.string.scanner_choose, label))
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 400.dp)) {
                options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { select(id); expanded = false }, trailingIcon = if (value == id) ({ Icon(Icons.Rounded.Check, scannerString(R.string.scanner_selected)) }) else null) }
            }
        }
    }
}

@Composable private fun scannerStateLabel(state: String): String = scannerString(scannerStateResource(state))
@Composable private fun scannerModeHint(mode: String): String = scannerString(when (mode) {
    "http-all" -> R.string.scanner_hint_http_all
    "custom" -> R.string.scanner_hint_custom
    "dns" -> R.string.scanner_hint_dns
    "dns-udptcp" -> R.string.scanner_hint_dns_udp
    "txt" -> R.string.scanner_hint_txt
    "sni" -> R.string.scanner_hint_sni
    "http-proxy" -> R.string.scanner_hint_http_proxy
    "socks-proxy" -> R.string.scanner_hint_socks_proxy
    else -> R.string.scanner_hint_http
})
