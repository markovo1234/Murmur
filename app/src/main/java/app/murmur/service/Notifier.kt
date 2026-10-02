package app.murmur.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.murmur.MainActivity
import app.murmur.R
import app.murmur.call.CallActionReceiver
import app.murmur.call.CallActivity
import app.murmur.core.protocol.PeerId
import app.murmur.data.db.NEARBY_CONVERSATION

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_DM, context.getString(R.string.channel_dm), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_NEARBY, context.getString(R.string.channel_nearby), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SOS, context.getString(R.string.channel_sos), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
                },
                NotificationChannel(CHANNEL_PEOPLE, context.getString(R.string.channel_people), NotificationManager.IMPORTANCE_DEFAULT),
                // The app plays the ringtone itself (it can loop and follow silent mode); the channel stays quiet.
                NotificationChannel(CHANNEL_CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(CHANNEL_CALL_ONGOING, context.getString(R.string.channel_call_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            ),
        )
    }

    fun serviceNotification(nearby: Int): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_title, nearby))
            .setContentText(context.getString(R.string.service_text))
            .setContentIntent(openApp(null, 0))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    fun updateService(nearby: Int) = post(SERVICE_ID, serviceNotification(nearby))

    fun showDirect(peer: PeerId, nickname: String, emoji: String, text: String, hideContent: Boolean) {
        val conversation = peer.toHex()
        val body = if (hideContent) context.getString(R.string.hidden_message) else text
        val n = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (hideContent) context.getString(R.string.app_name) else "$emoji $nickname")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(conversation, conversation.hashCode()))
            .build()
        post(conversation.hashCode(), n)
    }

    /** #nearby or a channel. Mentions use the DM channel so they stand out. */
    fun showRoom(conversationId: String, roomTitle: String, nickname: String, text: String, mention: Boolean, hideContent: Boolean) {
        val title = when {
            hideContent -> context.getString(R.string.app_name)
            mention -> context.getString(R.string.mention_title, nickname, roomTitle)
            else -> context.getString(R.string.room_notification_title, nickname, roomTitle)
        }
        val body = if (hideContent) context.getString(R.string.hidden_message) else text
        val n = NotificationCompat.Builder(context, if (mention) CHANNEL_DM else CHANNEL_NEARBY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(conversationId, conversationId.hashCode()))
            .build()
        post(conversationId.hashCode(), n)
    }

    fun showWave(peer: PeerId, name: String) {
        val conversation = peer.toHex()
        val n = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.wave_title, name))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(conversation, conversation.hashCode()))
            .build()
        post(conversation.hashCode(), n)
    }

    /** Emergency alert: high priority, always shown. */
    fun showSos(nickname: String, text: String, hops: Int) {
        val body = text.ifEmpty { context.getString(R.string.sos_default) }
        val n = NotificationCompat.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sos_title, nickname))
            .setContentText(body)
            .setSubText(context.resources.getQuantityString(R.plurals.hops_away, hops, hops))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(openApp(NEARBY_CONVERSATION, SOS_ID))
            .build()
        post(SOS_ID + (nickname.hashCode() and 0xFFFF), n)
    }

    fun showFavoriteNearby(peer: PeerId, name: String, emoji: String) {
        val conversation = peer.toHex()
        val n = NotificationCompat.Builder(context, CHANNEL_PEOPLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.favorite_nearby_title, emoji, name))
            .setContentText(context.getString(R.string.favorite_nearby_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(openApp(conversation, conversation.hashCode() + 1))
            .build()
        post(FAVORITE_ID + (conversation.hashCode() and 0xFFFF), n)
    }

    fun showIncomingCall(name: String, emoji: String, hideName: Boolean) {
        val title = if (hideName) context.getString(R.string.call_incoming_hidden) else context.getString(R.string.call_incoming_title, emoji, name)
        val n = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.call_incoming_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(CALL_NOTIFICATION_TIMEOUT)
            // Screen off or locked: wake the phone and ring full-screen over the lock screen.
            .setFullScreenIntent(callScreen(answer = false), true)
            .setContentIntent(callScreen(answer = false))
            .addAction(0, context.getString(R.string.call_decline), callAction(CallActionReceiver.ACTION_DECLINE))
            .addAction(0, context.getString(R.string.call_answer), callScreen(answer = true))
            .build()
        post(CALL_ID, n)
    }

    fun showOngoingCall(name: String, since: Long, hideName: Boolean) {
        val n = NotificationCompat.Builder(context, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (hideName) context.getString(R.string.call_ongoing_hidden) else context.getString(R.string.call_ongoing_title, name))
            .setContentText(context.getString(R.string.call_ongoing_text))
            .setWhen(since)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(callScreen(answer = false))
            .addAction(0, context.getString(R.string.call_hang_up), callAction(CallActionReceiver.ACTION_HANG_UP))
            .build()
        post(CALL_ID, n)
    }

    fun showMissedCall(peer: PeerId, name: String, emoji: String, hideName: Boolean) {
        val conversation = peer.toHex()
        val n = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (hideName) context.getString(R.string.call_missed_hidden) else context.getString(R.string.call_missed_title, emoji, name))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp(conversation, conversation.hashCode()))
            .build()
        post(conversation.hashCode(), n)
    }

    fun cancelCall() = manager.cancel(CALL_ID)

    fun showInvite(peer: PeerId, name: String, channel: String, hideContent: Boolean) {
        val conversation = peer.toHex()
        val n = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (hideContent) context.getString(R.string.app_name) else context.getString(R.string.invite_title, name, channel))
            .setContentText(context.getString(R.string.invite_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(openApp(conversation, conversation.hashCode()))
            .build()
        post(conversation.hashCode(), n)
    }

    private fun callAction(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The call screen over the lock screen ([CallActivity]). With [answer] it picks up too, asking for the
     * microphone first if needed.
     */
    private fun callScreen(answer: Boolean): PendingIntent {
        val intent = Intent(context, CallActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CallActivity.EXTRA_ANSWER, answer)
        val requestCode = if (answer) CALL_ID + 1 else CALL_ID + 2
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether calls may take over the screen. Android 14+ lets people switch that off per app. */
    fun canRingOverLockScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false

    /** The system page where calls-over-the-lock-screen is switched back on (Android 14+). */
    fun lockScreenCallSettings(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    fun cancelConversation(conversationId: String) = manager.cancel(conversationId.hashCode())

    fun cancelAll() = manager.cancelAll()

    private fun openApp(conversation: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (conversation != null) intent.putExtra(MainActivity.EXTRA_CONVERSATION, conversation)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // The permission is checked right here; lint can't see through the helper.
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val SERVICE_ID = 1
        const val CHANNEL_SERVICE = "mesh"
        const val CHANNEL_DM = "direct_messages"
        const val CHANNEL_NEARBY = "nearby"
        const val CHANNEL_SOS = "sos_alerts"
        const val CHANNEL_PEOPLE = "people"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_CALL_ONGOING = "call_ongoing"
        private const val CALL_ID = 0x43414C4C
        private const val CALL_NOTIFICATION_TIMEOUT = 45_000L
        private const val SOS_ID = 0x5050000
        private const val FAVORITE_ID = 0x4640000
    }
}
