package app.murmur.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/** Ringtone + vibration for incoming calls, ringback for outgoing ones, and the in-call proximity lock. */
class CallSounds(private val context: Context, private val log: (String) -> Unit) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var tones: ToneGenerator? = null
    private var proximity: PowerManager.WakeLock? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    /** Incoming call: respects the phone's silent / vibrate mode. */
    fun startRinging() {
        stopAlerts()
        val mode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: Settings.System.DEFAULT_RINGTONE_URI
                ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            } catch (e: RuntimeException) {
                log("ringtone failed: ${e.javaClass.simpleName}")
            }
        }
        vibrator?.takeIf { it.hasVibrator() }?.let {
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 1_100), 0))
            vibrating = true
        }
    }

    /** Outgoing call: the familiar "ring… ring…" while the other phone rings. */
    fun startRingback() {
        stopAlerts()
        tone(ToneGenerator.TONE_SUP_RINGTONE)
    }

    /** Busy / declined / ended beeps. */
    fun playEnded() {
        stopAlerts()
        tone(ToneGenerator.TONE_PROP_PROMPT, durationMs = 400)
    }

    fun stopAlerts() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) runCatching { vibrator?.cancel() }
        vibrating = false
        runCatching { tones?.stopTone() }
        runCatching { tones?.release() }
        tones = null
    }

    /** Screen off while the phone is at the ear (earpiece only). */
    fun setProximityLock(on: Boolean) {
        try {
            if (on) {
                if (proximity == null) {
                    val pm = context.getSystemService(PowerManager::class.java) ?: return
                    if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
                    proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "murmur:call")
                }
                proximity?.takeIf { !it.isHeld }?.acquire(MAX_CALL_MILLIS)
            } else {
                proximity?.takeIf { it.isHeld }?.release()
            }
        } catch (e: RuntimeException) {
            log("proximity lock failed: ${e.javaClass.simpleName}")
        }
    }

    private fun tone(type: Int, durationMs: Int = -1) {
        try {
            tones = ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME).also { it.startTone(type, durationMs) }
        } catch (e: RuntimeException) {
            log("tone failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TONE_VOLUME = 70
        const val MAX_CALL_MILLIS = 4 * 60 * 60 * 1000L
    }
}
