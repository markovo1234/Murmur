package app.murmur.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.murmur.AppContainer
import app.murmur.core.SecureRandomSource
import app.murmur.core.call.Amr
import app.murmur.core.call.CallSignal
import app.murmur.core.call.JitterBuffer
import app.murmur.core.crypto.CallCrypto
import app.murmur.core.mesh.MeshEvent
import app.murmur.core.mesh.MeshNode
import app.murmur.core.protocol.DmKind
import app.murmur.core.protocol.MessageId
import app.murmur.core.protocol.PeerId
import app.murmur.data.PeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class CallPhase {
    /** Offer sent, no reply yet. */
    DIALING,

    /** The other phone is ringing. */
    RINGING,

    /** Someone is calling me. */
    INCOMING,
    ACTIVE,
    ENDED,
}

data class CallUi(
    val callId: String,
    val peerId: PeerId,
    val name: String,
    val emoji: String,
    val colorIndex: Int,
    val outgoing: Boolean,
    val phase: CallPhase,
    val connectedAt: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** 0–3 bars from how much audio actually arrives. */
    val signal: Int = 3,
    /** 1 = direct Bluetooth link. */
    val hops: Int? = null,
    /** Why it ended (ENDED only). */
    val endReason: String? = null,
)

enum class StartCallResult { STARTED, ALREADY_IN_CALL, MESH_OFF, OFFLINE, BLOCKED, DEMO }

/**
 * Voice calls over the mesh (1.2). Signalling rides on end-to-end encrypted DMs (CALL_OFFER / ANSWER /
 * END); audio goes in CALL packets sealed with a per-call key from the offer. One call at a time.
 * All state lives on one thread ([scope]); the audio threads only touch the session's atomic counter
 * and synchronised jitter buffer.
 */
class CallManager(private val c: AppContainer) {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val random = SecureRandomSource()
    private val sounds = CallSounds(c.app) { log(it) }

    private class Session(val id: MessageId, val peer: PeerId, val outgoing: Boolean, val key: ByteArray) {
        @Volatile
        var phase = if (outgoing) CallPhase.DIALING else CallPhase.INCOMING
        var connectedAt = 0L
        var muted = false
        var speaker = false
        var hops: Int? = null
        @Volatile
        var ttl = DEFAULT_TTL
        var signal = 3
        var endReason: String? = null
        var engine: AudioEngine? = null
        val seqOut = AtomicLong(0)
        val jitter = JitterBuffer()
        var lastAudioAt = 0L
        var receivedInWindow = 0
        var gotAudio = false
        var callMode = false
        val jobs = mutableListOf<Job>()
    }

    private var session: Session? = null

    private val _state = MutableStateFlow<CallUi?>(null)

    /** The current call, or null. Stays on ENDED for a moment so the screen can say why. */
    val state: StateFlow<CallUi?> = _state.asStateFlow()

    private val _minimized = MutableStateFlow(false)

    /**
     * The call screen is shrunk to the pill. Shared by the app and [CallActivity] (the window over the lock
     * screen), so minimizing there carries on in the app as a pill. Every new call starts full-screen.
     */
    val minimized: StateFlow<Boolean> = _minimized.asStateFlow()

    fun setMinimized(value: Boolean) {
        _minimized.value = value
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(c.app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ================================================================== user actions

    suspend fun startCall(peer: PeerId): StartCallResult = onScope {
        if (session.let { it != null && it.phase != CallPhase.ENDED }) return@onScope StartCallResult.ALREADY_IN_CALL
        val p = c.peers.peers.value.firstOrNull { it.id == peer }
        when {
            p?.isDemo == true -> return@onScope StartCallResult.DEMO
            p?.blocked == true -> return@onScope StartCallResult.BLOCKED
        }
        val node = node() ?: return@onScope StartCallResult.MESH_OFF
        if (p == null || !p.isOnline) return@onScope StartCallResult.OFFLINE
        clear()
        val s = Session(MessageId.random(random), peer, outgoing = true, key = CallCrypto.newKey(random))
        session = s
        _minimized.value = false
        publish()
        log("calling ${p.name}")
        sounds.startRingback()
        // Declare the microphone now, while the app is certainly in the foreground.
        enterCallMode(s)
        s.jobs += scope.launch {
            // The offer is a one-shot DM: repeat it until the other phone answers something.
            repeat(OFFER_ATTEMPTS) {
                if (s.phase != CallPhase.DIALING) return@launch
                node.sendDirectControl(peer, DmKind.CALL_OFFER, s.id, CallSignal.offerBody(s.key))
                delay(OFFER_INTERVAL_MILLIS)
            }
        }
        s.jobs += scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            if (session === s && (s.phase == CallPhase.DIALING || s.phase == CallPhase.RINGING)) {
                sendEnd(s, CallSignal.EndReason.TIMEOUT)
                end(s, "No answer", "📞 No answer")
            }
        }
        StartCallResult.STARTED
    }

    /** Call only once the microphone permission is granted. */
    fun accept() = scope.launch {
        val s = session?.takeIf { it.phase == CallPhase.INCOMING } ?: return@launch
        enterCallMode(s)
        answer(s, CallSignal.Answer.ACCEPT)
        connect(s)
        // If the ACCEPT gets lost the caller still switches over when our audio arrives; repeat it a few
        // times until theirs arrives too.
        s.jobs += scope.launch {
            repeat(ACCEPT_REPEATS) {
                delay(ACCEPT_REPEAT_MILLIS)
                if (session !== s || s.gotAudio || s.phase != CallPhase.ACTIVE) return@launch
                answer(s, CallSignal.Answer.ACCEPT)
            }
        }
    }

    fun decline() = scope.launch {
        val s = session?.takeIf { it.phase == CallPhase.INCOMING } ?: return@launch
        answer(s, CallSignal.Answer.DECLINE)
        end(s, "Declined", "📞 You declined a call")
    }

    fun hangup() = scope.launch {
        val s = session ?: return@launch
        when (s.phase) {
            CallPhase.INCOMING -> {
                answer(s, CallSignal.Answer.DECLINE)
                end(s, "Declined", "📞 You declined a call")
            }
            CallPhase.DIALING, CallPhase.RINGING -> {
                sendEnd(s, CallSignal.EndReason.CANCEL)
                end(s, "Cancelled", "📞 Cancelled call")
            }
            CallPhase.ACTIVE -> {
                sendEnd(s, CallSignal.EndReason.HANGUP)
                end(s, "Call ended", durationLine(s))
            }
            CallPhase.ENDED -> clear()
        }
    }

    fun toggleMute() = scope.launch {
        val s = session ?: return@launch
        s.muted = !s.muted
        s.engine?.muted = s.muted
        publish()
    }

    fun toggleSpeaker() = scope.launch {
        val s = session ?: return@launch
        s.speaker = !s.speaker
        s.engine?.setSpeaker(s.speaker)
        sounds.setProximityLock(s.phase == CallPhase.ACTIVE && !s.speaker)
        publish()
    }

    // ================================================================== mesh events

    /** Called for every mesh event; ignores everything but call signals and audio. */
    fun onMeshEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.CallAudio -> scope.launch { onAudio(event) }
            is MeshEvent.DirectControl -> when (event.kind) {
                DmKind.CALL_OFFER -> scope.launch { onOffer(event) }
                DmKind.CALL_ANSWER -> scope.launch { onAnswer(event) }
                DmKind.CALL_END -> scope.launch { onEnd(event) }
                else -> Unit
            }
            else -> Unit
        }
    }

    /** The mesh stopped (Bluetooth off, Stop pressed): no call can survive that. */
    fun onMeshStopped() = scope.launch {
        val s = session ?: return@launch
        if (s.phase != CallPhase.ENDED) end(s, "Mesh stopped", if (s.phase == CallPhase.ACTIVE) durationLine(s) else "📞 Call failed")
    }

    private suspend fun onOffer(e: MeshEvent.DirectControl) {
        val cur = session
        if (cur != null && cur.id == e.messageId) {
            // A repeated offer: say again that we're ringing.
            if (cur.phase == CallPhase.INCOMING) answer(cur, CallSignal.Answer.RINGING)
            return
        }
        val offer = CallSignal.parseOffer(e.body)
        if (offer == null || offer.codec != CallSignal.CODEC_AMR_NB) {
            sendAnswer(e.senderId, e.messageId, CallSignal.Answer.UNSUPPORTED)
            return
        }
        if (cur != null && cur.phase != CallPhase.ENDED) {
            val node = node()
            val glare = cur.outgoing && cur.peer == e.senderId &&
                (cur.phase == CallPhase.DIALING || cur.phase == CallPhase.RINGING)
            if (glare && node != null && e.senderId < node.myId) {
                // We called each other at the same moment: the lower peer id's call wins.
                sendEnd(cur, CallSignal.EndReason.CANCEL)
                cur.jobs.forEach { it.cancel() }
                sounds.stopAlerts()
                if (cur.callMode) c.mesh.setCallActive(false)
                session = null
            } else {
                if (!glare) {
                    sendAnswer(e.senderId, e.messageId, CallSignal.Answer.BUSY)
                    c.chats.logCall(e.senderId, "📞 Missed call (you were on another call)", unread = true)
                }
                return
            }
        }
        clear()
        val s = Session(e.messageId, e.senderId, outgoing = false, key = offer.key)
        session = s
        _minimized.value = false
        publish()
        answer(s, CallSignal.Answer.RINGING)
        sounds.startRinging()
        val ui = _state.value
        if (ui != null) c.notifier.showIncomingCall(ui.name, ui.emoji, hideName = hideContent())
        log("incoming call from ${ui?.name}")
        s.jobs += scope.launch {
            delay(RING_TIMEOUT_MILLIS + INCOMING_GRACE_MILLIS)
            if (session === s && s.phase == CallPhase.INCOMING) missed(s)
        }
    }

    private suspend fun onAnswer(e: MeshEvent.DirectControl) {
        val s = session ?: return
        if (!s.outgoing || s.id != e.messageId || s.peer != e.senderId) return
        when (CallSignal.Answer.fromWire(e.body)) {
            CallSignal.Answer.RINGING -> if (s.phase == CallPhase.DIALING) {
                s.phase = CallPhase.RINGING
                publish()
            }
            CallSignal.Answer.ACCEPT -> if (s.phase == CallPhase.DIALING || s.phase == CallPhase.RINGING) connect(s)
            CallSignal.Answer.DECLINE -> if (s.phase != CallPhase.ENDED) end(s, "Declined", "📞 Call declined")
            CallSignal.Answer.BUSY -> if (s.phase != CallPhase.ENDED) end(s, "Busy", "📞 ${nameOf(s.peer)} was on another call")
            CallSignal.Answer.UNSUPPORTED -> if (s.phase != CallPhase.ENDED) end(s, "Can't take calls", "📞 ${nameOf(s.peer)} can't take calls")
            null -> Unit
        }
    }

    private suspend fun onEnd(e: MeshEvent.DirectControl) {
        val s = session ?: return
        if (s.id != e.messageId || s.peer != e.senderId) return
        when (s.phase) {
            CallPhase.INCOMING -> missed(s)
            CallPhase.ACTIVE -> end(s, "Call ended", durationLine(s))
            CallPhase.DIALING, CallPhase.RINGING -> end(s, "Call ended", "📞 Call failed")
            CallPhase.ENDED -> Unit
        }
    }

    private suspend fun onAudio(e: MeshEvent.CallAudio) {
        val s = session ?: return
        if (s.id != e.callId || s.peer != e.senderId) return
        // Audio before the ACCEPT arrived: the callee picked up and the ACCEPT got lost.
        if (s.outgoing && (s.phase == CallPhase.DIALING || s.phase == CallPhase.RINGING)) connect(s)
        if (s.phase != CallPhase.ACTIVE) return
        val me = node()?.myId ?: return
        val plain = CallCrypto.open(s.key, fromCaller = !s.outgoing, seq = e.seq, aad = CallCrypto.aad(s.id.toBytes(), e.senderId, me, e.seq), sealed = e.sealed)
            ?: return
        if (Amr.split(plain) == null) return
        synchronized(s.jitter) { s.jitter.push(e.seq, plain) }
        s.lastAudioAt = c.clock.now()
        s.receivedInWindow++
        s.gotAudio = true
    }

    // ================================================================== internals

    private suspend fun connect(s: Session) {
        if (s.phase == CallPhase.ACTIVE || s.phase == CallPhase.ENDED) return
        s.jobs.forEach { it.cancel() }
        s.jobs.clear()
        sounds.stopAlerts()
        c.notifier.cancelCall()
        val node = node() ?: return end(s, "Mesh stopped", "📞 Call failed")
        s.hops = node.hopsTo(s.peer)
        s.ttl = ttlFor(s.hops)
        s.phase = CallPhase.ACTIVE
        s.connectedAt = c.clock.now()
        s.lastAudioAt = s.connectedAt
        val engine = AudioEngine(
            context = c.app,
            log = { log(it) },
            onChunk = { chunk -> sendAudio(s, node, chunk) },
            nextChunk = { synchronized(s.jitter) { s.jitter.pop() } },
        )
        if (!engine.start()) {
            sendEnd(s, CallSignal.EndReason.FAILED)
            return end(s, "Microphone unavailable", "📞 Call failed")
        }
        s.engine = engine
        engine.muted = s.muted
        engine.setSpeaker(s.speaker)
        sounds.setProximityLock(!s.speaker)
        c.notifier.showOngoingCall(nameOf(s.peer), s.connectedAt, hideName = hideContent())
        publish()
        log("call connected (${s.hops ?: "?"} hops, ttl ${s.ttl})")
        s.jobs += scope.launch { monitor(s, node) }
    }

    /** Signal bars, route refresh, and giving up on a dead connection. */
    private suspend fun monitor(s: Session, node: MeshNode) {
        var ticks = 0
        while (scope.isActive && session === s && s.phase == CallPhase.ACTIVE) {
            delay(MONITOR_MILLIS)
            ticks++
            val expected = MONITOR_MILLIS / AudioEngine.CHUNK_MILLIS
            val ratio = s.receivedInWindow.toFloat() / expected
            s.receivedInWindow = 0
            s.signal = when {
                ratio >= 0.85f -> 3
                ratio >= 0.6f -> 2
                ratio >= 0.2f -> 1
                else -> 0
            }
            if (ticks % ROUTE_REFRESH_TICKS == 0) {
                s.hops = node.hopsTo(s.peer) ?: s.hops
                s.ttl = ttlFor(s.hops)
            }
            publish()
            if (c.clock.now() - s.lastAudioAt > LOST_AFTER_MILLIS) {
                sendEnd(s, CallSignal.EndReason.FAILED)
                end(s, "Connection lost", durationLine(s))
                return
            }
        }
    }

    /** Capture thread. */
    private fun sendAudio(s: Session, node: MeshNode, chunk: ByteArray) {
        if (s.phase != CallPhase.ACTIVE) return
        val seq = s.seqOut.getAndIncrement() and 0xFFFF_FFFFL
        val aad = CallCrypto.aad(s.id.toBytes(), node.myId, s.peer, seq)
        val sealed = CallCrypto.seal(s.key, fromCaller = s.outgoing, seq = seq, aad = aad, plaintext = chunk)
        val ttl = s.ttl
        c.appScope.launch { node.sendCallAudio(s.peer, s.id, seq, sealed, ttl) }
    }

    private suspend fun missed(s: Session) {
        val name = nameOf(s.peer)
        end(s, "Missed call", "📞 Missed call", unread = true)
        c.notifier.showMissedCall(s.peer, name, emojiOf(s.peer), hideName = hideContent())
    }

    private suspend fun end(s: Session, reason: String, logLine: String, unread: Boolean = false) {
        if (session !== s || s.phase == CallPhase.ENDED) return
        val wasActive = s.phase == CallPhase.ACTIVE
        s.phase = CallPhase.ENDED
        s.endReason = reason
        s.jobs.forEach { it.cancel() }
        s.jobs.clear()
        publish()
        sounds.stopAlerts()
        sounds.setProximityLock(false)
        sounds.playEnded()
        c.notifier.cancelCall()
        s.engine?.let { engine ->
            s.engine = null
            // Joining the audio threads takes a moment; keep this thread free.
            c.appScope.launch(Dispatchers.Default) { engine.stop() }
        }
        if (s.callMode) c.mesh.setCallActive(false)
        log("call ended: $reason${if (wasActive) " after ${durationLine(s)}" else ""}")
        c.appScope.launch { c.chats.logCall(s.peer, logLine, unread) }
        scope.launch {
            delay(ENDED_SCREEN_MILLIS)
            sounds.stopAlerts()
            if (session === s) {
                session = null
                publish()
            }
        }
    }

    /** Microphone foreground service + fast Bluetooth connection interval, for the rest of this call. */
    private fun enterCallMode(s: Session) {
        if (s.callMode) return
        s.callMode = true
        c.mesh.setCallActive(true)
    }

    /** Drops a finished session right away (a new call is starting). */
    private fun clear() {
        val s = session ?: return
        if (s.phase == CallPhase.ENDED) {
            session = null
            publish()
        }
    }

    private suspend fun answer(s: Session, answer: CallSignal.Answer) = sendAnswer(s.peer, s.id, answer)

    private suspend fun sendAnswer(peer: PeerId, id: MessageId, answer: CallSignal.Answer) {
        node()?.sendDirectControl(peer, DmKind.CALL_ANSWER, id, answer.wire)
    }

    private suspend fun sendEnd(s: Session, reason: CallSignal.EndReason) {
        val node = node() ?: return
        // Twice, a moment apart: it's a one-shot DM and a lost END would leave the other side hanging.
        node.sendDirectControl(s.peer, DmKind.CALL_END, s.id, reason.wire)
        c.appScope.launch {
            delay(END_REPEAT_MILLIS)
            node.sendDirectControl(s.peer, DmKind.CALL_END, s.id, reason.wire)
        }
    }

    private fun durationLine(s: Session): String {
        val seconds = ((c.clock.now() - s.connectedAt) / 1000).coerceAtLeast(0)
        val arrow = if (s.outgoing) "Outgoing" else "Incoming"
        return "📞 $arrow call · ${formatDuration(seconds)}"
    }

    private fun publish() {
        val s = session
        _state.value = s?.let {
            val peer = c.peers.peers.value.firstOrNull { p -> p.id == it.peer }
            CallUi(
                callId = it.id.toHex(),
                peerId = it.peer,
                name = peer?.name ?: PeerRepository.fallbackName(it.peer),
                emoji = peer?.emoji ?: PeerRepository.DEFAULT_EMOJI,
                colorIndex = peer?.colorIndex ?: 0,
                outgoing = it.outgoing,
                phase = it.phase,
                connectedAt = it.connectedAt,
                muted = it.muted,
                speaker = it.speaker,
                signal = it.signal,
                hops = it.hops,
                endReason = it.endReason,
            )
        }
    }

    private fun nameOf(peer: PeerId): String = c.peers.peers.value.firstOrNull { it.id == peer }?.name ?: PeerRepository.fallbackName(peer)
    private fun emojiOf(peer: PeerId): String = c.peers.peers.value.firstOrNull { it.id == peer }?.emoji ?: PeerRepository.DEFAULT_EMOJI
    /** Keep names out of call notifications and off the lock screen ("Hide message text", or app lock on). */
    fun hideContent(): Boolean = c.settingsState.value?.let { it.hideNotificationContent || it.appLock.enabled } == true
    private fun node(): MeshNode? = c.mesh.runtime.value?.node
    private fun log(message: String) = c.log.log("CALL", message)

    private suspend fun <T> onScope(block: suspend () -> T): T = withContext(confined) { block() }

    companion object {
        const val OFFER_ATTEMPTS = 12
        const val OFFER_INTERVAL_MILLIS = 2_500L
        const val RING_TIMEOUT_MILLIS = 35_000L
        const val INCOMING_GRACE_MILLIS = 5_000L
        const val ACCEPT_REPEATS = 4
        const val ACCEPT_REPEAT_MILLIS = 1_500L
        const val END_REPEAT_MILLIS = 700L
        const val MONITOR_MILLIS = 2_000L
        const val ROUTE_REFRESH_TICKS = 3
        const val LOST_AFTER_MILLIS = 15_000L
        const val ENDED_SCREEN_MILLIS = 1_800L
        const val DEFAULT_TTL = 3
        const val MAX_TTL = 5

        /** Exactly the hop distance for a direct link; one spare hop otherwise, in case the route shifts. */
        fun ttlFor(hops: Int?): Int = when {
            hops == null -> DEFAULT_TTL
            hops <= 1 -> 1
            else -> (hops + 1).coerceAtMost(MAX_TTL)
        }

        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
