package io.mszymanski.orknux.orkbox.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

@Serializable
data class Event(
    val seq: Long,
    val at: String,
    val type: String,
    val data: JsonObject,
)

/** A session's append-only event log, bounded to [capacity] most recent events. */
class EventLog(private val capacity: Int) {
    private val events = ArrayDeque<Event>()
    private var seq = 0L
    private val _latest = MutableStateFlow(0L)

    /** Sequence number of the newest event; collectors are woken on every append. */
    val latest: StateFlow<Long> = _latest.asStateFlow()

    @Synchronized
    fun append(type: String, data: JsonObject): Event {
        val event = Event(++seq, Instant.now().toString(), type, data)
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        _latest.value = seq
        return event
    }

    /** Events after [since] whose type passes [include], oldest first. */
    @Synchronized
    fun after(since: Long, limit: Int, include: (String) -> Boolean): List<Event> {
        val result = ArrayList<Event>()
        for (event in events) {
            if (event.seq <= since || !include(event.type)) continue
            result += event
            if (result.size >= limit) break
        }
        return result
    }
}
