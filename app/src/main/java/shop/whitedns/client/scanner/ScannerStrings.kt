package shop.whitedns.client.scanner

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import shop.whitedns.client.model.WhiteDnsSettingsStore

internal fun scannerResources(context: Context, language: String): Resources =
    shop.whitedns.client.ui.whiteDnsResources(context, language)

@Composable
internal fun scannerString(@StringRes id: Int, vararg arguments: Any): String =
    androidx.compose.ui.platform.LocalResources.current.getString(id, *arguments)

internal fun scannerText(context: Context, @StringRes id: Int, vararg arguments: Any): String =
    scannerResources(context, WhiteDnsSettingsStore(context).languageCode()).getString(id, *arguments)

data class ScannerMessage(@param:StringRes val id: Int, val details: String = "", val arguments: List<Any> = emptyList())
internal class ScannerInputException(@param:StringRes val resource: Int, vararg val arguments: Any) : IllegalArgumentException()

internal fun scannerStateResource(state: String): Int = when (state) {
    "starting" -> shop.whitedns.client.R.string.scanner_state_starting
    "running" -> shop.whitedns.client.R.string.scanner_state_running
    "paused" -> shop.whitedns.client.R.string.scanner_state_paused
    "stopped" -> shop.whitedns.client.R.string.scanner_state_stopped
    "completed" -> shop.whitedns.client.R.string.scanner_state_completed
    "failed" -> shop.whitedns.client.R.string.scanner_state_failed
    else -> shop.whitedns.client.R.string.scanner_state_idle
}
