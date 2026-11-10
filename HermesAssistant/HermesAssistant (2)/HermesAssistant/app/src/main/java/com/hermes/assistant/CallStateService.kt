package com.hermes.assistant

import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

/**
 * Watches the device's call state so Hermes can tell you what happened
 * to a call it placed: answered, not answered, busy, or declined.
 *
 * Android does not expose a direct "ringing on the other end" / "declined"
 * event for outgoing calls. The practical way to find out is: wait for the
 * call to end (OFFHOOK -> IDLE) and then read the matching entry from the
 * call log. A duration of 0 means it never connected (no answer, busy, or
 * declined); anything above that means the other person picked up.
 */
class CallStateService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var speaker: TtsSpeaker
    private var wasOffHook = false

    private val listener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Android API, kept for minSdk 26 compatibility")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    wasOffHook = true
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (wasOffHook) {
                        wasOffHook = false
                        reportOutcome()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        speaker = TtsSpeaker(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        startForegroundWithNotification()
    }

    private fun reportOutcome() {
        val contactName = CallOutcomeTracker.armedContactName ?: return
        CallOutcomeTracker.armedContactName = null

        // Give the call log a moment to write the finished entry.
        android.os.Handler(mainLooper).postDelayed({
            val duration = lastOutgoingCallDuration()
            val message = if (duration != null && duration > 0) {
                "$contactName answered."
            } else {
                "$contactName didn't pick up."
            }
            speaker.speak(message)
        }, 1200)
    }

    private fun lastOutgoingCallDuration(): Long? {
        val projection = arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE)
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                return cursor.getLong(durationIdx)
            }
        }
        return null
    }

    private fun startForegroundWithNotification() {
        val channelId = "hermes_call_watch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Hermes call monitor", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hermes")
            .setContentText("Watching call status…")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(2, notification)
        }
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
        speaker.shutdown()
        super.onDestroy()
    }
}
