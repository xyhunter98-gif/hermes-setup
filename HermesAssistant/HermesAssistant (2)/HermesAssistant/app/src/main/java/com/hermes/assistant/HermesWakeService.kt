package com.hermes.assistant

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * LEGACY fallback background listener: keeps re-arming the plain
 * SpeechRecognizer. Kept around in case someone doesn't want to set up
 * a Picovoice account, but [PorcupineWakeService] is the one actually
 * wired to the UI toggle now — it uses a real low-power wake-word model
 * instead of looping full speech recognition, which is dramatically
 * easier on the battery. See that class's doc comment for the full
 * explanation. This class is unused unless you manually start it.
 *
 * The PREFS / isEnabled / setEnabled below are the single shared on/off
 * switch for background listening — both this class and
 * PorcupineWakeService read/write the same preference, and setEnabled()
 * is the one that decides which service actually gets started.
 */
class HermesWakeService : Service() {

    companion object {
        const val PREFS = "hermes_prefs"
        const val KEY_BACKGROUND_ENABLED = "background_listening_enabled"
        private const val CHANNEL_ID = "hermes_background"
        private const val NOTIF_ID = 3

        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_BACKGROUND_ENABLED, false)

        /** Starts/stops the real wake-word service (Porcupine-based). */
        fun setEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply()
            val intent = Intent(context, PorcupineWakeService::class.java)
            if (enabled) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }

    private lateinit var overlay: OverlayBubble
    private lateinit var speaker: TtsSpeaker
    private lateinit var executor: ActionExecutor
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayBubble(this)
        speaker = TtsSpeaker(this)
        executor = ActionExecutor(this, speaker)
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            listenCycle()
        }
        return START_STICKY
    }

    private fun listenCycle() {
        if (!running) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull().orEmpty()
                    handleHeard(heard)
                    relisten()
                }

                override fun onError(error: Int) {
                    // SpeechRecognizer times out on silence (normal) or errors out —
                    // either way, just re-arm and keep waiting for the wake word.
                    relisten()
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            startListening(recognizerIntent)
        }
    }

    private fun relisten() {
        if (!running) return
        // Small delay avoids hammering the recognizer in a tight error loop.
        mainHandler.postDelayed({ listenCycle() }, 400)
    }

    private fun handleHeard(text: String) {
        if (text.isBlank()) return

        val command = CommandParser.parse(text, requireWakeWord = true) ?: return
        // Wake word was present — show the bubble and act on it.
        overlay.show("\"$text\"")
        executor.execute(command)
        mainHandler.postDelayed({ overlay.hide() }, 2500)
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Hermes background listening", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, HermesWakeService::class.java).apply { action = "STOP" }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else 0
        val stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes is listening")
            .setContentText("Say \"Hermes\" followed by a command, anytime.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Turn off", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        recognizer?.destroy()
        speaker.shutdown()
        getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BACKGROUND_ENABLED, false).apply()
        super.onDestroy()
    }
}
