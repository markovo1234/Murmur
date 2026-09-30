package app.murmur.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import app.murmur.core.call.Amr
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Microphone → AMR-NB (7.95 kbit/s) chunks, and received chunks → earpiece or speaker, each on its own
 * thread. Platform APIs only: AudioRecord, AudioTrack and MediaCodec (the AMR-NB encoder and decoder
 * are mandatory on Android phones with a microphone).
 */
class AudioEngine(
    private val context: Context,
    private val log: (String) -> Unit,
    /** Called on the capture thread with [FRAMES_PER_CHUNK] encoded frames back to back. */
    private val onChunk: (ByteArray) -> Unit,
    /** Called on the playback thread once per chunk period: the next received chunk, or null for silence. */
    private val nextChunk: () -> ByteArray?,
) {
    @Volatile
    var muted: Boolean = false

    @Volatile
    private var running = false
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private val effects = mutableListOf<AudioEffect>()

    /** False if the microphone, speaker or codec can't be used (nothing is left running). */
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("no microphone permission")
            return false
        }
        val encoder = createCodec(encoder = true) ?: return false
        val decoder = createCodec(encoder = false)
        val record = createRecord()
        val track = createTrack()
        if (decoder == null || record == null || track == null) {
            releaseQuietly(encoder, decoder, record, track)
            return false
        }
        audioManager?.let {
            previousMode = it.mode
            it.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        running = true
        captureThread = thread(name = "murmur-call-capture") { captureLoop(record, encoder) }
        playThread = thread(name = "murmur-call-play") { playLoop(track, decoder) }
        log("audio started (AMR-NB ${BIT_RATE / 1000.0} kbit/s, ${FRAMES_PER_CHUNK * Amr.FRAME_MILLIS} ms chunks)")
        return true
    }

    /** Blocks briefly while the audio threads wind down. Call off the main thread. */
    fun stop() {
        if (!running) return
        running = false
        captureThread?.join(JOIN_MILLIS)
        playThread?.join(JOIN_MILLIS)
        captureThread = null
        playThread = null
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        setSpeaker(false)
        audioManager?.mode = previousMode
        log("audio stopped")
    }

    fun setSpeaker(on: Boolean) {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (e: RuntimeException) {
            log("speaker switch failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ capture

    private fun captureLoop(record: AudioRecord, encoder: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ByteArray(Amr.SAMPLES_PER_FRAME * 2)
        val frames = ArrayList<ByteArray>(FRAMES_PER_CHUNK * 2)
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        var badOutput = false
        try {
            record.startRecording()
            while (running) {
                if (!readFully(record, pcm)) {
                    log("microphone read failed")
                    break
                }
                val inIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIndex >= 0) {
                    encoder.getInputBuffer(inIndex)?.apply {
                        clear()
                        put(pcm)
                    }
                    encoder.queueInputBuffer(inIndex, 0, pcm.size, ptsUs, 0)
                    ptsUs += Amr.FRAME_MILLIS * 1000L
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val out = ByteArray(info.size)
                        encoder.getOutputBuffer(outIndex)?.apply {
                            position(info.offset)
                            get(out)
                        }
                        val split = Amr.split(out)
                        if (split != null) {
                            frames += split
                        } else if (!badOutput) {
                            badOutput = true
                            log("encoder output isn't AMR storage frames (${out.size} bytes)")
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
                while (frames.size >= FRAMES_PER_CHUNK) {
                    val chunk = ByteArrayOutputStream(FRAMES_PER_CHUNK * 32)
                    repeat(FRAMES_PER_CHUNK) { chunk.write(frames.removeAt(0)) }
                    if (!muted) onChunk(chunk.toByteArray())
                }
            }
        } catch (e: Exception) {
            log("capture stopped: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            releaseQuietly(encoder, null, record, null)
        }
    }

    private fun readFully(record: AudioRecord, buffer: ByteArray): Boolean {
        var off = 0
        while (off < buffer.size && running) {
            val n = record.read(buffer, off, buffer.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    // ------------------------------------------------------------------ playback

    private fun playLoop(track: AudioTrack, decoder: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val silence = ByteArray(CHUNK_BYTES)
        val info = MediaCodec.BufferInfo()
        try {
            track.play()
            while (running) {
                val pcm = nextChunk()?.let { decode(decoder, it, info) }
                val out = if (pcm == null || pcm.isEmpty()) silence else pcm
                // Blocking write: once the track's buffer is full this paces the loop in real time.
                if (track.write(out, 0, out.size) < 0) {
                    log("speaker write failed")
                    break
                }
            }
        } catch (e: Exception) {
            log("playback stopped: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            releaseQuietly(null, decoder, null, track)
        }
    }

    private fun decode(decoder: MediaCodec, chunk: ByteArray, info: MediaCodec.BufferInfo): ByteArray? {
        val frames = Amr.split(chunk) ?: return null
        val pcm = ByteArrayOutputStream(CHUNK_BYTES)
        for ((i, frame) in frames.withIndex()) {
            val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inIndex >= 0) {
                decoder.getInputBuffer(inIndex)?.apply {
                    clear()
                    put(frame)
                }
                decoder.queueInputBuffer(inIndex, 0, frame.size, 0, 0)
            }
            drain(decoder, info, pcm, if (i == frames.lastIndex) CODEC_TIMEOUT_US else 0)
        }
        return pcm.toByteArray()
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, into: ByteArrayOutputStream, firstTimeoutUs: Long) {
        var timeout = firstTimeoutUs
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, timeout)
            if (outIndex < 0) return
            timeout = 0
            if (info.size > 0) {
                val bytes = ByteArray(info.size)
                codec.getOutputBuffer(outIndex)?.apply {
                    position(info.offset)
                    get(bytes)
                }
                into.write(bytes)
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    // ------------------------------------------------------------------ setup

    private fun createCodec(encoder: Boolean): MediaCodec? {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, Amr.SAMPLE_RATE, 1)
            codec = if (encoder) {
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
            } else {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
            }
            codec.configure(format, null, null, if (encoder) MediaCodec.CONFIGURE_FLAG_ENCODE else 0)
            codec.start()
            codec
        } catch (e: Exception) {
            log("AMR-NB ${if (encoder) "encoder" else "decoder"} unavailable: ${e.javaClass.simpleName} ${e.message}")
            runCatching { codec?.release() }
            null
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked in start()
    private fun createRecord(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(Amr.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            Amr.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, CHUNK_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            log("microphone unavailable")
            null
        } else {
            // VOICE_COMMUNICATION usually brings the platform's echo canceller; add the effects where offered.
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
            record
        }
    } catch (e: Exception) {
        log("microphone unavailable: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun createTrack(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(Amr.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Amr.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, CHUNK_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
    } catch (e: Exception) {
        log("speaker unavailable: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun releaseQuietly(encoder: MediaCodec?, decoder: MediaCodec?, record: AudioRecord?, track: AudioTrack?) {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
    }

    companion object {
        /** AMR-NB mode MR795: 21-byte frames (header included). */
        const val BIT_RATE = 7_950

        /** 4 × 20 ms = 80 ms of audio per packet: ~12.5 packets a second each way. */
        const val FRAMES_PER_CHUNK = 4
        const val CHUNK_MILLIS = FRAMES_PER_CHUNK * Amr.FRAME_MILLIS
        private const val CHUNK_BYTES = FRAMES_PER_CHUNK * Amr.SAMPLES_PER_FRAME * 2
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val JOIN_MILLIS = 600L
    }
}
