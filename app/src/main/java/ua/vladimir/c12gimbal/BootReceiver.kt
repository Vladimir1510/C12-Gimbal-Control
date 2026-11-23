package ua.vladimir.c12gimbal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts GimbalService (floating button, channel subscription) after the RC boots; control itself stays off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startForegroundService(Intent(context, GimbalService::class.java))
    }
}
