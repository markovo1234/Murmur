package app.murmur.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.murmur.core.mesh.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val onboardingDone: Boolean = false,
    val profile: Profile? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val relay: Boolean = true,
    val keepRunningInBackground: Boolean = true,
    val readReceipts: Boolean = true,
    val nearbyNotifications: Boolean = false,
    val demoMode: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val nickname = stringPreferencesKey("nickname")
        val emoji = stringPreferencesKey("emoji")
        val color = intPreferencesKey("color")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val relay = booleanPreferencesKey("relay")
        val keepRunning = booleanPreferencesKey("keep_running")
        val readReceipts = booleanPreferencesKey("read_receipts")
        val nearbyNotifications = booleanPreferencesKey("nearby_notifications")
        val demoMode = booleanPreferencesKey("demo_mode")
    }

    val settings: Flow<Settings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val nickname = p[Keys.nickname]
            val emoji = p[Keys.emoji]
            val profile = if (nickname != null && emoji != null) Profile(nickname, emoji, p[Keys.color] ?: 0) else null
            Settings(
                onboardingDone = (p[Keys.onboardingDone] ?: false) && profile?.isValid == true,
                profile = profile,
                themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                dynamicColor = p[Keys.dynamicColor] ?: false,
                relay = p[Keys.relay] ?: true,
                keepRunningInBackground = p[Keys.keepRunning] ?: true,
                readReceipts = p[Keys.readReceipts] ?: true,
                nearbyNotifications = p[Keys.nearbyNotifications] ?: false,
                demoMode = p[Keys.demoMode] ?: false,
            )
        }

    suspend fun current(): Settings = settings.first()

    suspend fun setProfile(profile: Profile) = context.dataStore.edit {
        it[Keys.nickname] = profile.nickname
        it[Keys.emoji] = profile.emoji
        it[Keys.color] = profile.colorIndex
    }

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.onboardingDone] = done }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = on }
    suspend fun setRelay(on: Boolean) = context.dataStore.edit { it[Keys.relay] = on }
    suspend fun setKeepRunning(on: Boolean) = context.dataStore.edit { it[Keys.keepRunning] = on }
    suspend fun setReadReceipts(on: Boolean) = context.dataStore.edit { it[Keys.readReceipts] = on }
    suspend fun setNearbyNotifications(on: Boolean) = context.dataStore.edit { it[Keys.nearbyNotifications] = on }
    suspend fun setDemoMode(on: Boolean) = context.dataStore.edit { it[Keys.demoMode] = on }

    suspend fun clear() = context.dataStore.edit { it.clear() }
}
