package app.murmur.data

import app.murmur.core.Clock
import app.murmur.core.SecureRandomSource
import app.murmur.core.crypto.PinHasher
import app.murmur.core.protocol.Bytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * PIN lock for the whole app. The PIN is stored only as a salted PBKDF2 hash. Starts locked; the UI
 * shows the lock screen only while [Settings.appLock] is enabled.
 */
class AppLock(
    private val settings: StateFlow<Settings?>,
    private val repository: SettingsRepository,
    private val clock: Clock,
) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** True while the lock screen should cover the app. */
    val showing: Flow<Boolean> = combine(_locked, settings) { locked, s ->
        locked && s != null && s.onboardingDone && s.appLock.enabled
    }.distinctUntilChanged()

    private val _lockedOutUntil = MutableStateFlow(0L)

    /** After 5 wrong PINs, attempts are refused until this time. */
    val lockedOutUntil: StateFlow<Long> = _lockedOutUntil.asStateFlow()

    private var failures = 0
    private var backgroundedAt = 0L

    private val config: AppLockSettings? get() = settings.value?.appLock?.takeIf { it.enabled }

    fun onBackground() {
        backgroundedAt = clock.now()
    }

    fun onForeground() {
        val c = config ?: return
        if (backgroundedAt != 0L && clock.now() - backgroundedAt >= c.timeoutMillis) _locked.value = true
        backgroundedAt = 0L
    }

    enum class Result { OK, WRONG, LOCKED_OUT }

    suspend fun unlock(pin: String): Result {
        val c = config ?: run {
            _locked.value = false
            return Result.OK
        }
        if (clock.now() < _lockedOutUntil.value) return Result.LOCKED_OUT
        return if (check(pin, c)) {
            failures = 0
            _locked.value = false
            Result.OK
        } else {
            failures++
            if (failures >= MAX_FAILURES) {
                failures = 0
                _lockedOutUntil.value = clock.now() + LOCKOUT_MILLIS
            }
            Result.WRONG
        }
    }

    /** True if [pin] matches the current PIN (for changing or turning off the lock). */
    suspend fun verify(pin: String): Boolean = config?.let { check(pin, it) } ?: true

    suspend fun setPin(pin: String) {
        require(isValidPin(pin))
        val salt = PinHasher.newSalt(SecureRandomSource())
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        repository.setAppLock(Bytes.toHex(hash), Bytes.toHex(salt))
        _locked.value = false
    }

    suspend fun disable() {
        repository.disableAppLock()
        _locked.value = false
    }

    private suspend fun check(pin: String, c: AppLockSettings): Boolean {
        val salt = Bytes.fromHex(c.pinSaltHex) ?: return false
        val hash = Bytes.fromHex(c.pinHashHex) ?: return false
        return withContext(Dispatchers.Default) { PinHasher.verify(pin, salt, hash) }
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCKOUT_MILLIS = 30_000L
        fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }
    }
}
