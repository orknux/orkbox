package io.mszymanski.orknux.orkbox.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bytes written by one process stream, addressed by absolute offset.
 *
 * At most [capacity] bytes are retained; once more has been written the oldest
 * bytes are dropped, so offsets keep counting from the first byte ever written
 * while [retainedFrom] moves forward.
 */
class OutputBuffer(private val capacity: Int) {
    private var data = ByteArray(minOf(capacity, 8192))
    private var used = 0
    private var dropped = 0L
    private val _total = MutableStateFlow(0L)

    /** Total bytes written so far; collectors are woken on every append. */
    val total: StateFlow<Long> = _total.asStateFlow()

    val retainedFrom: Long @Synchronized get() = dropped

    @Synchronized
    fun append(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (len == 0) return
        if (len >= capacity) {
            dropped += used + (len - capacity)
            System.arraycopy(bytes, off + len - capacity, ensure(capacity), 0, capacity)
            used = capacity
        } else {
            if (used + len > capacity) {
                // Drop down to three quarters so the copy is amortised over many appends.
                val keep = minOf(used, capacity * 3 / 4 - len).coerceAtLeast(0)
                val drop = used - keep
                System.arraycopy(data, drop, data, 0, keep)
                used = keep
                dropped += drop
            }
            System.arraycopy(bytes, off, ensure(used + len), used, len)
            used += len
        }
        _total.value = dropped + used
    }

    private fun ensure(size: Int): ByteArray {
        if (data.size < size) data = data.copyOf(minOf(capacity, maxOf(size, data.size * 2)))
        return data
    }

    /** Up to [limit] bytes from [offset]; an offset before [retainedFrom] reads from there. */
    @Synchronized
    fun read(offset: Long, limit: Int = Int.MAX_VALUE): Chunk {
        val from = maxOf(offset, dropped)
        val start = (from - dropped).toInt().coerceAtMost(used)
        val len = minOf(limit, used - start)
        return Chunk(from, data.copyOfRange(start, start + len))
    }

    /**
     * The retained output as text, cut to [max] bytes by keeping its head and
     * tail with a marker in between.
     */
    @Synchronized
    fun excerpt(max: Int): Excerpt {
        val total = dropped + used
        if (used <= max) {
            val text = String(data, 0, used, Charsets.UTF_8)
            return if (dropped == 0L) Excerpt(text, false) else Excerpt("… [$dropped bytes omitted] …\n$text", true)
        }
        val half = max / 2
        val headLen = minOf(half, used)
        val tailLen = minOf(max - headLen, used - headLen)
        val head = String(data, 0, headLen, Charsets.UTF_8)
        val tail = String(data, used - tailLen, tailLen, Charsets.UTF_8)
        val omitted = total - headLen - tailLen
        return Excerpt("$head\n… [$omitted bytes omitted] …\n$tail", true)
    }

    class Chunk(val offset: Long, val bytes: ByteArray) {
        val next: Long get() = offset + bytes.size
    }

    data class Excerpt(val text: String, val truncated: Boolean)
}
