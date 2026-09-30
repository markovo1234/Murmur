package app.murmur.core.text

import app.murmur.core.Murmur

/** @mentions in rooms. */
object Mentions {
    /** True if [text] contains "@nickname" (case-insensitive, not inside a longer word). */
    fun mentions(text: String, nickname: String?): Boolean {
        if (nickname.isNullOrBlank()) return false
        val pattern = Regex("(^|[^\\p{L}\\p{N}_])@" + Regex.escape(nickname) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }

    /** The partial "@name" being typed at the end of [text], without the '@', or null. */
    fun partial(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) return null
        val tail = text.substring(at + 1)
        return tail.takeIf { it.length <= Murmur.NICKNAME_MAX_CHARS && '\n' !in it && !it.contains("  ") }
    }

    /** Replaces the partial mention at the end of [text] with "@nickname ". */
    fun complete(text: String, nickname: String): String {
        val at = text.lastIndexOf('@')
        return if (at < 0) "$text@$nickname " else text.substring(0, at) + "@$nickname "
    }
}

/**
 * Replies are plain text so every version (including 1.0) shows them sensibly:
 * the first line is "> Name: quoted snippet", the rest is the reply.
 */
object Replies {
    const val SNIPPET_CHARS = 80

    data class Parsed(val quoteAuthor: String?, val quote: String?, val text: String)

    fun compose(author: String, quoted: String, reply: String): String {
        val oneLine = quoted.replace('\n', ' ').trim()
        val snippet = if (oneLine.length > SNIPPET_CHARS) oneLine.take(SNIPPET_CHARS - 1).trimEnd() + "…" else oneLine
        var body = "> $author: $snippet\n$reply"
        // Keep the whole message within the protocol limit by shortening the quote first.
        var cut = snippet
        while (Murmur.utf8Size(body) > Murmur.MAX_TEXT_BYTES && cut.isNotEmpty()) {
            cut = cut.dropLast(8)
            body = "> $author: ${cut.trimEnd()}…\n$reply"
        }
        // No room for even a short quote: send the reply on its own.
        return if (Murmur.utf8Size(body) > Murmur.MAX_TEXT_BYTES) reply else body
    }

    fun parse(body: String): Parsed {
        if (!body.startsWith("> ")) return Parsed(null, null, body)
        val newline = body.indexOf('\n')
        if (newline < 0) return Parsed(null, null, body)
        val quoteLine = body.substring(2, newline)
        val rest = body.substring(newline + 1)
        val colon = quoteLine.indexOf(": ")
        return if (colon in 1..40) {
            Parsed(quoteLine.substring(0, colon), quoteLine.substring(colon + 2), rest)
        } else {
            Parsed(null, quoteLine, rest)
        }
    }

    /** The text a quote was taken from, for matching the original message. */
    fun snippetKey(quote: String): String = quote.removeSuffix("…").trim()
}

/** Disappearing-message timer choices. */
object Disappearing {
    val OPTIONS: List<Long> = listOf(0L, 5 * 60L, 60 * 60L, 24 * 60 * 60L, 7 * 24 * 60 * 60L)
    const val MAX_SECONDS: Long = 28 * 24 * 60 * 60L

    fun label(seconds: Long): String = when {
        seconds <= 0 -> "Off"
        seconds < 3600 -> "${seconds / 60} minutes"
        seconds == 3600L -> "1 hour"
        seconds < 86_400 -> "${seconds / 3600} hours"
        seconds == 86_400L -> "1 day"
        seconds == 604_800L -> "1 week"
        else -> "${seconds / 86_400} days"
    }
}
