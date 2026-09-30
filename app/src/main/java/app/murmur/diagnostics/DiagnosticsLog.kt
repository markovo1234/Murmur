package app.murmur.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log (the user can't read logcat). Keeps the last [capacity] lines. Thread-safe.
 * Also mirrors to logcat for anyone who can.
 */
class DiagnosticsLog(private val capacity: Int = 300) {
    private val lines = ArrayDeque<String>(capacity)
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _flow.asStateFlow()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val line = synchronized(this) {
            val l = "${format.format(Date())} $tag  $message"
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(l)
            _flow.value = lines.toList()
            l
        }
        try {
            android.util.Log.i("Murmur", line)
        } catch (_: RuntimeException) {
            // android.util.Log isn't available in local JVM tests / previews.
        }
    }

    fun clear() = synchronized(this) {
        lines.clear()
        _flow.value = emptyList()
    }

    fun text(): String = synchronized(this) { lines.joinToString("\n") }
}
