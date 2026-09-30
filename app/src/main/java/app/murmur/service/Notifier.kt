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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.murmur.MainActivity
import app.murmur.R
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

    fun showDirect(peer: PeerId, nickname: String, emoji: String, text: String) {
        val conversation = peer.toHex()
        val n = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$emoji $nickname")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(conversation, conversation.hashCode()))
            .build()
        post(conversation.hashCode(), n)
    }

    fun showNearby(nickname: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_NEARBY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.nearby_notification_title, nickname))
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(NEARBY_CONVERSATION, NEARBY_CONVERSATION.hashCode()))
            .build()
        post(NEARBY_CONVERSATION.hashCode(), n)
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
    }
}
