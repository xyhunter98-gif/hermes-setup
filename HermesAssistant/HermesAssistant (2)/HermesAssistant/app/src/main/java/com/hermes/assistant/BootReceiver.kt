package com.hermes.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * If the user had background listening turned on before a reboot,
 * start it again automatically.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (HermesWakeService.isEnabled(context)) {
                ContextCompat.startForegroundService(
                    context, Intent(context, PorcupineWakeService::class.java)
                )
            }
        }
    }
}
