package app.murmur.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Lightweight message formatting: *bold*, _italic_, ~strikethrough~, `code`, and @mentions.
 * Markers only count when they wrap non-space text on one line, so "2 * 3 * 4" stays as typed.
 */
object RichText {
    private val TOKEN = Regex(
        "(?<![\\w*])\\*(?! )([^*\\n]+?)(?<! )\\*(?![\\w*])" + // *bold*
            "|(?<![\\w_])_(?! )([^_\\n]+?)(?<! )_(?![\\w_])" + // _italic_
            "|(?<![\\w~])~(?! )([^~\\n]+?)(?<! )~(?![\\w~])" + // ~strike~
            "|`([^`\\n]+)`" + // `code`
            "|(?<![\\p{L}\\p{N}_])@([\\p{L}\\p{N}_]{1,20})", // @mention
    )

    fun format(text: String, mention: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (match in TOKEN.findAll(text)) {
            append(text, last, match.range.first)
            val g = match.groups
            when {
                g[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]!!.value) }
                g[2] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[2]!!.value) }
                g[3] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[3]!!.value) }
                g[4] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[4]!!.value) }
                g[5] != null -> withStyle(SpanStyle(color = mention, fontWeight = FontWeight.SemiBold)) { append(match.value) }
            }
            last = match.range.last + 1
        }
        if (last < text.length) append(text, last, text.length)
    }

    /** True if the text uses any formatting (skips work for plain messages). */
    fun hasMarkup(text: String): Boolean = text.any { it == '*' || it == '_' || it == '~' || it == '`' || it == '@' }
}
