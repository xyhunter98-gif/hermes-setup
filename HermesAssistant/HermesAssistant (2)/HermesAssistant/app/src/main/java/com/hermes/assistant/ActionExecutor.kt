package com.hermes.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carries out a parsed Command: places calls, sends texts, opens apps,
 * sets timers, searches the web, toggles the flashlight, reports time
 * and battery. All actions stay within the device's own telephony/SMS/
 * contacts/system APIs — nothing here reaches another person's account
 * or device without them answering their own phone, same as Siri/Google
 * Assistant.
 */
class ActionExecutor(private val context: Context, private val speaker: TtsSpeaker) {

    fun execute(command: Command) {
        when (command) {
            is Command.Call -> handleCall(command.contactName)
            is Command.Message -> handleMessage(command.contactName, command.body)
            is Command.OpenApp -> handleOpenApp(command.appName)
            is Command.Timer -> handleTimer(command.seconds, command.label)
            is Command.Search -> handleSearch(command.query)
            is Command.Flashlight -> handleFlashlight(command.on)
            Command.Time -> handleTime()
            Command.Battery -> handleBattery()
            is Command.Unknown -> speaker.speak("I didn't catch a command in that.")
        }
    }

    private fun handleCall(spokenName: String) {
        val match = ContactResolver.findBestMatch(context, spokenName)
        if (match == null) {
            speaker.speak("I couldn't find $spokenName in your contacts.")
            return
        }
        speaker.speak("Calling ${match.displayName}")
        CallOutcomeTracker.armedContactName = match.displayName

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${match.phoneNumber}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun handleMessage(spokenName: String, body: String) {
        val match = ContactResolver.findBestMatch(context, spokenName)
        if (match == null) {
            speaker.speak("I couldn't find $spokenName in your contacts.")
            return
        }
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(match.phoneNumber, null, body, null, null)
            speaker.speak("Message sent to ${match.displayName}")
        } catch (e: Exception) {
            speaker.speak("I couldn't send that message.")
        }
    }

    private fun handleOpenApp(appName: String) {
        val opened = AppLauncher.launch(context, appName)
        speaker.speak(if (opened) "Opening $appName" else "I couldn't find an app called $appName")
    }

    private fun handleTimer(seconds: Int, label: String) {
        // Hands off to the device's own Clock app, which already does
        // reliable alarms/vibration/sound in the background — more
        // robust than Hermes trying to own a countdown itself.
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            val minutes = seconds / 60
            speaker.speak(
                if (minutes > 0) "Timer set for $minutes minute${if (minutes == 1) "" else "s"}"
                else "Timer set for $seconds seconds"
            )
        } catch (e: Exception) {
            speaker.speak("I couldn't set that timer — no clock app found.")
        }
    }

    private fun handleSearch(query: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        speaker.speak("Searching for $query")
        context.startActivity(intent)
    }

    private fun handleFlashlight(on: Boolean) {
        val ok = FlashlightController.set(context, on)
        speaker.speak(
            when {
                !ok -> "I couldn't reach the flashlight on this device."
                on -> "Flashlight on."
                else -> "Flashlight off."
            }
        )
    }

    private fun handleTime() {
        val formatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        speaker.speak("It's $formatted")
    }

    private fun handleBattery() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null as BroadcastReceiver?, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val percent = (level * 100) / scale
            speaker.speak("Battery is at $percent percent")
        } else {
            speaker.speak("I couldn't read the battery level.")
        }
    }
}

/**
 * Simple in-memory handoff between ActionExecutor (which starts a call)
 * and CallStateService (which watches how the call ends) so the service
 * knows whose outcome to announce.
 */
object CallOutcomeTracker {
    @Volatile var armedContactName: String? = null
}
