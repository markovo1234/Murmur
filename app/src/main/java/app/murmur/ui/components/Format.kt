package app.murmur.ui.components

import app.murmur.core.mesh.PeerStatus
import app.murmur.data.Peer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Pure formatting helpers (take `now` explicitly so previews and screens stay deterministic). */
object Format {
    fun relative(now: Long, time: Long): String {
        if (time <= 0L) return "never"
        val diff = (now - time).coerceAtLeast(0L)
        return when {
            diff < 60_000L -> "now"
            diff < 3_600_000L -> "${diff / 60_000L} min"
            diff < 86_400_000L && sameDay(now, time) -> "${diff / 3_600_000L} h"
            isYesterday(now, time) -> "Yesterday"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
        }
    }

    fun lastSeen(now: Long, time: Long): String = when (val r = relative(now, time)) {
        "now" -> "just now"
        "never" -> "never"
        "Yesterday" -> "yesterday"
        else -> if (r.endsWith("min") || r.endsWith("h")) "$r ago" else r
    }

    fun clock(time: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))

    fun day(now: Long, time: Long): String = when {
        sameDay(now, time) -> "Today"
        isYesterday(now, time) -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(time))
    }

    fun dayKey(time: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun sameDay(a: Long, b: Long): Boolean = dayKey(a) == dayKey(b)

    private fun isYesterday(now: Long, time: Long): Boolean = sameDay(now - 86_400_000L, time)

    /** "nearby · strong signal", "via mesh · 3 hops", "offline · sends when they're back". */
    fun peerStatus(peer: Peer?): String = when (peer?.status) {
        PeerStatus.NEARBY -> "nearby · ${signalWord(peer.rssi)}"
        PeerStatus.VIA_MESH -> "via mesh · ${peer.hops} hop${if (peer.hops == 1) "" else "s"}"
        PeerStatus.OFFLINE, null -> "offline · sends when they're back"
    }

    /**
     * How a message reaches this person, for the mono route lines: "direct · strong signal",
     * "via mesh · 3 hops", "last here 1 h ago · sends when back".
     */
    fun route(peer: Peer?, now: Long): String = when (peer?.status) {
        PeerStatus.NEARBY -> "direct · ${signalWord(peer.rssi)}"
        PeerStatus.VIA_MESH -> "via mesh · ${peer.hops} hop${if (peer.hops == 1) "" else "s"}"
        PeerStatus.OFFLINE -> "last here ${lastSeen(now, peer.lastSeen)} · sends when back"
        null -> "offline · sends when they're back"
    }

    /** "Luna", "Luna and Kai", "Luna, Kai and Mira", "Luna, Kai, Mira and 3 others". */
    fun names(list: List<String>, max: Int = 3): String = when {
        list.isEmpty() -> ""
        list.size == 1 -> list[0]
        list.size <= max + 1 -> list.dropLast(1).joinToString(", ") + " and " + list.last()
        else -> list.take(max).joinToString(", ") + " and ${list.size - max} others"
    }

    /** Short status for TalkBack on radar avatars and list rows. */
    fun peerA11y(peer: Peer): String = "${peer.name}${if (peer.favorite) ", favorite" else ""}, ${peerStatus(peer)}"

    /** Your own alias wins; otherwise appends "#1a2b" when another peer shares the nickname. */
    fun displayName(peer: Peer, all: List<Peer>): String {
        if (peer.alias != null) return peer.alias
        val clash = all.any { it.id != peer.id && it.nickname.equals(peer.nickname, ignoreCase = true) }
        return if (clash) "${peer.nickname} #${peer.id.shortTag}" else peer.nickname
    }
}
