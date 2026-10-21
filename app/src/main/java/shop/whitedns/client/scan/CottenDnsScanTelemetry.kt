package shop.whitedns.client.scan

sealed class CottenDnsScanTelemetry {
    data class Testing(val resolver: String, val transport: String) : CottenDnsScanTelemetry()
    data class Path(val resolver: String, val transport: String, val usable: Boolean) : CottenDnsScanTelemetry()
    data class Valid(val resolver: String) : CottenDnsScanTelemetry()
    data class Rejected(val resolver: String) : CottenDnsScanTelemetry()
    data class Complete(
        val total: Int,
        val valid: Int,
        val rejected: Int,
    ) : CottenDnsScanTelemetry()
}

fun parseCottenDnsScanLine(line: String): CottenDnsScanTelemetry? {
    val cleanLine = line
        .replace(AnsiEscapeRegex, "")
        .trim()
    val markerIndex = cleanLine.indexOf(ScanMarker)
    if (markerIndex < 0) {
        return null
    }

    val fields = ScanFieldRegex.findAll(cleanLine.substring(markerIndex + ScanMarker.length))
        .associate { match -> match.groupValues[1] to match.groupValues[2] }
    return when (fields["event"]) {
        "testing" -> fields["resolver"]?.takeIf(String::isNotBlank)?.let {
            CottenDnsScanTelemetry.Testing(it, fields["transport"].orEmpty())
        }
        "path" -> fields["resolver"]?.takeIf(String::isNotBlank)?.let {
            CottenDnsScanTelemetry.Path(it, fields["transport"].orEmpty(), fields["usable"] == "true" || fields["transfer_verified"] == "true")
        }
        "valid" -> fields["resolver"]
            ?.takeIf(String::isNotBlank)
            ?.let(CottenDnsScanTelemetry::Valid)
        "rejected" -> fields["resolver"]
            ?.takeIf(String::isNotBlank)
            ?.let(CottenDnsScanTelemetry::Rejected)
        "complete" -> CottenDnsScanTelemetry.Complete(
            total = fields["total"].toIntOrZero(),
            valid = fields["valid"].toIntOrZero(),
            rejected = fields["rejected"].toIntOrZero(),
        )
        else -> null
    }
}

class CottenDnsScanPaths {
    private val pending = mutableListOf<String>()
    private val working = linkedSetOf<String>()
    val testingPaths: List<String> get() = pending.toList()
    val workingPaths: List<String> get() = working.toList()
    fun update(event: CottenDnsScanTelemetry) {
        fun label(resolver: String, transport: String) =
            resolver + if (transport.isBlank()) "" else " (${transport.uppercase()})"
        when (event) {
            is CottenDnsScanTelemetry.Testing -> pending += label(event.resolver, event.transport)
            is CottenDnsScanTelemetry.Path -> {
                val path = label(event.resolver, event.transport)
                pending.remove(path)
                if (event.usable) working += path
            }
            is CottenDnsScanTelemetry.Complete -> pending.clear()
            else -> Unit
        }
    }
    fun clearTesting() = pending.clear()
}

private fun String?.toIntOrZero(): Int {
    return this?.toIntOrNull() ?: 0
}

private const val ScanMarker = "WD_SCAN"
private val ScanFieldRegex = Regex("""(\w+)=([^\s]+)""")
private val AnsiEscapeRegex = Regex("${27.toChar()}\\[[;?0-9]*[ -/]*[@-~]")
