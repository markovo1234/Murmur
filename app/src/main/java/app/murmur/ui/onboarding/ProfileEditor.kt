package app.murmur.ui.onboarding

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.core.Murmur
import app.murmur.core.mesh.Profile
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Motion
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import app.murmur.ui.theme.Palette

val AVATAR_EMOJIS = listOf(
    "🦊", "🐙", "🐧", "🦉", "🐢", "🐝",
    "🦋", "🐬", "🌙", "⭐", "🌸", "🍀",
    "🔥", "🌊", "⚡", "🍄", "🎧", "🎸",
    "🚀", "🛰️", "🎈", "🍩", "🧭", "👾",
)

private val COLOR_NAMES = listOf("mint", "lavender", "amber", "rose", "blue", "green", "pink", "orange", "cyan", "purple")

@Immutable
data class ProfileDraft(val nickname: String = "", val emoji: String = AVATAR_EMOJIS[0], val colorIndex: Int = 0) {
    val trimmed: String get() = nickname.trim()
    val length: Int get() = Murmur.nicknameLength(trimmed)
    val isValid: Boolean get() = toProfile().isValid
    fun toProfile(): Profile = Profile(trimmed, emoji, colorIndex)

    companion object {
        fun from(profile: Profile?): ProfileDraft =
            profile?.let { ProfileDraft(it.nickname, it.emoji, it.colorIndex) } ?: ProfileDraft()
    }
}

/** Nickname (1–20 chars), 24-emoji grid, color swatches and a live preview bubble. */
@Composable
fun ProfileEditor(
    draft: ProfileDraft,
    onNicknameChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PreviewBubble(draft)
        val tooLong = draft.length > Murmur.NICKNAME_MAX_CHARS
        OutlinedTextField(
            value = draft.nickname,
            onValueChange = { if (Murmur.nicknameLength(it) <= Murmur.NICKNAME_MAX_CHARS + 5) onNicknameChange(it.replace("\n", "")) },
            label = { Text("Nickname") },
            singleLine = true,
            isError = tooLong,
            supportingText = {
                Text(
                    if (tooLong) "Up to ${Murmur.NICKNAME_MAX_CHARS} characters" else "${draft.length}/${Murmur.NICKNAME_MAX_CHARS}",
                )
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Avatar", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in AVATAR_EMOJIS.chunked(6)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (emoji in row) EmojiCell(emoji, selected = emoji == draft.emoji, onClick = { onEmojiChange(emoji) })
                }
            }
        }

        Text("Color", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in (0 until Murmur.AVATAR_COLOR_COUNT).chunked(5)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (i in row) ColorSwatch(i, selected = i == draft.colorIndex, onClick = { onColorChange(i) })
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "emojiBorder")
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, Motion.popOrSnap(MurmurTheme.reduceMotion), label = "emojiScale")
    Box(
        Modifier
            .size(Dimens.MinTouch)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, border, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Avatar $emoji" },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 24.sp)
    }
}

@Composable
private fun ColorSwatch(index: Int, selected: Boolean, onClick: () -> Unit) {
    val color = Palette.avatar(index)
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, label = "swatchRing")
    Box(
        Modifier
            .size(Dimens.MinTouch)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Color ${COLOR_NAMES[index]}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(2.dp, ring, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(MurmurIcons.Tick, contentDescription = null, tint = Color(0xFF0A0F1E), modifier = Modifier.size(18.dp))
        }
    }
}

/** How others will see you: avatar, nickname and a sample bubble. */
@Composable
private fun PreviewBubble(draft: ProfileDraft) {
    val name = draft.trimmed.ifEmpty { "Your name" }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        EmojiAvatar(draft.emoji, draft.colorIndex, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Surface(
                color = MurmurTheme.colors.otherBubble,
                contentColor = MurmurTheme.colors.onOtherBubble,
                shape = RoundedCornerShape(topStart = Dimens.BubbleGroupedRadius, topEnd = Dimens.BubbleRadius, bottomEnd = Dimens.BubbleRadius, bottomStart = Dimens.BubbleRadius),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    "Hi, I'm $name 👋",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Preview(name = "Profile editor · light", showBackground = true)
@Composable
private fun ProfileEditorLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { ProfileEditor(ProfileDraft("Sam", "🐧", 1), {}, {}, {}, Modifier.padding(16.dp)) }
}

@Preview(name = "Profile editor · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProfileEditorDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { ProfileEditor(ProfileDraft("", "🦊", 7), {}, {}, {}, Modifier.padding(16.dp)) }
}
