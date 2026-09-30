package app.murmur.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    // 1.1
    val appLock: AppLockSettings = AppLockSettings(),
    val hideNotificationContent: Boolean = false,
    val batterySaver: Boolean = false,
    /** ttl for #nearby and channel messages: 7 = normal reach, 3 = short. */
    val publicReach: Int = REACH_NORMAL,
    val favoriteAlerts: Boolean = true,
    /** Messages this phone relayed for others, all time. */
    val relayedTotal: Long = 0,
) {
    companion object {
        const val REACH_NORMAL = 7
        const val REACH_SHORT = 3
    }
}

data class AppLockSettings(
    val enabled: Boolean = false,
    val pinHashHex: String = "",
    val pinSaltHex: String = "",
    /** Lock after the app has been in the background this long (0 = immediately). */
    val timeoutMillis: Long = 0,
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
        val lockEnabled = booleanPreferencesKey("lock_enabled")
        val lockHash = stringPreferencesKey("lock_hash")
        val lockSalt = stringPreferencesKey("lock_salt")
        val lockTimeout = longPreferencesKey("lock_timeout")
        val hideContent = booleanPreferencesKey("hide_notification_content")
        val batterySaver = booleanPreferencesKey("battery_saver")
        val publicReach = intPreferencesKey("public_reach")
        val favoriteAlerts = booleanPreferencesKey("favorite_alerts")
        val relayedTotal = longPreferencesKey("relayed_total")
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
                appLock = AppLockSettings(
                    enabled = (p[Keys.lockEnabled] ?: false) && !p[Keys.lockHash].isNullOrEmpty(),
                    pinHashHex = p[Keys.lockHash].orEmpty(),
                    pinSaltHex = p[Keys.lockSalt].orEmpty(),
                    timeoutMillis = p[Keys.lockTimeout] ?: 0L,
                ),
                hideNotificationContent = p[Keys.hideContent] ?: false,
                batterySaver = p[Keys.batterySaver] ?: false,
                publicReach = (p[Keys.publicReach] ?: Settings.REACH_NORMAL).coerceIn(1, 7),
                favoriteAlerts = p[Keys.favoriteAlerts] ?: true,
                relayedTotal = p[Keys.relayedTotal] ?: 0L,
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

    suspend fun setAppLock(hashHex: String, saltHex: String) = context.dataStore.edit {
        it[Keys.lockHash] = hashHex
        it[Keys.lockSalt] = saltHex
        it[Keys.lockEnabled] = true
    }

    suspend fun disableAppLock() = context.dataStore.edit {
        it[Keys.lockEnabled] = false
        it.remove(Keys.lockHash)
        it.remove(Keys.lockSalt)
    }

    suspend fun setLockTimeout(millis: Long) = context.dataStore.edit { it[Keys.lockTimeout] = millis }
    suspend fun setHideNotificationContent(on: Boolean) = context.dataStore.edit { it[Keys.hideContent] = on }
    suspend fun setBatterySaver(on: Boolean) = context.dataStore.edit { it[Keys.batterySaver] = on }
    suspend fun setPublicReach(ttl: Int) = context.dataStore.edit { it[Keys.publicReach] = ttl.coerceIn(1, 7) }
    suspend fun setFavoriteAlerts(on: Boolean) = context.dataStore.edit { it[Keys.favoriteAlerts] = on }
    suspend fun addRelayed(count: Long) = context.dataStore.edit { it[Keys.relayedTotal] = (it[Keys.relayedTotal] ?: 0L) + count }

    suspend fun clear() = context.dataStore.edit { it.clear() }
}
