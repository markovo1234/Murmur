package app.murmur.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.murmur.MurmurApp

/** Decline / Hang up buttons in the call notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as MurmurApp).container.calls
        when (intent.action) {
            ACTION_DECLINE -> calls.decline()
            ACTION_HANG_UP -> calls.hangup()
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.murmur.action.CALL_DECLINE"
        const val ACTION_HANG_UP = "app.murmur.action.CALL_HANG_UP"
    }
}
