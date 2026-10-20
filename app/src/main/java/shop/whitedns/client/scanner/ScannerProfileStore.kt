package shop.whitedns.client.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Typing updates drafts immediately; a single IO writer coalesces persistence. */
@OptIn(FlowPreview::class)
internal class ScannerProfileStore(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> Map<String, String>,
    private val write: suspend (String, Map<String, String>) -> Unit,
    debounceMillis: Long = 400,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val drafts = mutableMapOf<String, Map<String, String>>()
    private val pending = mutableMapOf<String, Map<String, String>>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val writer = Mutex()
    private val job = scope.launch {
        signals.receiveAsFlow().debounce(debounceMillis).collect {
            runCatching { flush() }.onFailure(onError)
        }
    }
    fun cached(key: String): Map<String, String>? = synchronized(lock) { drafts[key] }
    suspend fun load(key: String): Map<String, String> {
        cached(key)?.let { return it }
        val loaded = read(key)
        return synchronized(lock) { drafts.getOrPut(key) { loaded } }
    }
    fun update(key: String, fields: Map<String, String>) {
        synchronized(lock) { drafts[key] = fields; pending[key] = fields }
        signals.trySend(Unit)
    }
    suspend fun flush() = writer.withLock {
        val batch = synchronized(lock) { pending.toMap().also { pending.clear() } }
        var failure: Throwable? = null
        batch.forEach { (key, fields) ->
            try { write(key, fields) } catch (error: Throwable) {
                // Keep the latest draft on a failed write; the next edit/flush retries it.
                synchronized(lock) { pending.putIfAbsent(key, fields) }
                failure = error
            }
        }
        failure?.let { throw it }
    }
    /** Complete queued edits even if the activity's ViewModel is being cleared. */
    fun closeAndFlush(): Job {
        job.cancel()
        return scope.launch { job.join(); runCatching { flush() }.onFailure(onError) }
    }
}
