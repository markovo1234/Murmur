package app.murmur.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One serial operation queue per connection. The next GATT operation starts only after the previous
 * one's callback ([completed]) or a timeout. Must be used from the transport's single thread.
 */
class GattQueue(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 5_000,
    private val onProblem: (String) -> Unit,
) {
    private class Op(val name: String, val start: () -> Boolean, val onFinish: (() -> Unit)?)

    private val ops = ArrayDeque<Op>()
    private var current: Op? = null
    private var timeout: Job? = null
    private var closed = false
    private var consecutiveStartFailures = 0

    val size: Int get() = ops.size + (if (current != null) 1 else 0)

    /** Number of operations that could not even be started, in a row (a dead link sign). */
    val startFailures: Int get() = consecutiveStartFailures

    fun enqueue(name: String, onFinish: (() -> Unit)? = null, start: () -> Boolean) {
        if (closed) return
        ops.addLast(Op(name, start, onFinish))
        next()
    }

    /**
     * Call from the GATT callback that finishes the current operation. [kind] must match the start of the
     * operation's name, so a callback arriving after its operation timed out can't complete the next one.
     */
    fun completed(kind: String) {
        val op = current ?: return
        if (!op.name.startsWith(kind)) return
        timeout?.cancel()
        current = null
        op.onFinish?.invoke()
        next()
    }

    fun close() {
        closed = true
        timeout?.cancel()
        ops.clear()
        current = null
    }

    private fun next() {
        while (current == null && !closed) {
            val op = ops.removeFirstOrNull() ?: return
            current = op
            val started = try {
                op.start()
            } catch (e: SecurityException) {
                onProblem("${op.name}: permission denied")
                false
            } catch (e: RuntimeException) {
                onProblem("${op.name}: ${e.javaClass.simpleName}")
                false
            }
            if (!started) {
                consecutiveStartFailures++
                onProblem("${op.name} did not start")
                current = null
                op.onFinish?.invoke()
                continue
            }
            consecutiveStartFailures = 0
            timeout = scope.launch {
                delay(timeoutMillis)
                if (current === op) {
                    onProblem("${op.name} timed out after ${timeoutMillis}ms")
                    current = null
                    op.onFinish?.invoke()
                    next()
                }
            }
        }
    }
}
