package com.hermes.assistant

import android.app.*
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that does one listen-parse-act cycle:
 *   1. Pop the overlay bubble ("Listening…")
 *   2. Capture speech with SpeechRecognizer
 *   3. Parse it into a Command
 *   4. Execute it (call / message) and speak the result
 *   5. Hide the bubble and stop
 *
 * Triggered from MainActivity's mic button, or from the persistent
 * notification's "Listen" action so it also works with the app backgrounded.
 *
 * Note: this is tap-to-talk, the same model Google Assistant used before
 * dedicated hotword chips existed. A true always-on "say Hermes from
 * anywhere, screen off" wake word needs an on-device keyword-spotting
 * engine (e.g. Picovoice Porcupine) running continuously in the
 * background — that's a separate, heavier integration (see README).
 */
class HermesListenerService : Service() {

    companion object {
        const val ACTION_START_LISTENING = "com.hermes.assistant.START_LISTENING"
        private const val CHANNEL_ID = "hermes_listener"
        private const val NOTIF_ID = 1
    }

    private lateinit var overlay: OverlayBubble
    private lateinit var speaker: TtsSpeaker
    private lateinit var executor: ActionExecutor
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayBubble(this)
        speaker = TtsSpeaker(this)
        executor = ActionExecutor(this, speaker)
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_LISTENING) {
            beginListening()
        }
        return START_NOT_STICKY
    }

    private fun beginListening() {
        overlay.show("Listening…")

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull().orEmpty()
                    onHeard(heard)
                }

                override fun onError(error: Int) {
                    overlay.update("Didn't catch that.")
                    finishSoon()
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { overlay.update(it) }
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            startListening(intent)
        }
    }

    private fun onHeard(text: String) {
        if (text.isBlank()) {
            overlay.update("Didn't catch that.")
            finishSoon()
            return
        }
        overlay.update("\"$text\"")
        // Tap-to-talk already means the user meant this for Hermes, so the
        // wake word is optional here (requireWakeWord = false).
        val ruleCommand = CommandParser.parse(text, requireWakeWord = false)

        if (ruleCommand != null && ruleCommand !is Command.Unknown) {
            runAndLabel(ruleCommand)
            return
        }

        // Rigid rules didn't recognize the phrasing — try the OpenRouter
        // fallback (only runs if OPENROUTER_API_KEY is set; otherwise
        // returns null immediately and we fall through to "not sure").
        serviceScope.launch {
            val llmCommand = LlmIntentParser.parse(text)
            runAndLabel(llmCommand ?: Command.Unknown(text))
        }
    }

    private fun runAndLabel(command: Command) {
        executor.execute(command)

        val resultLabel = when (command) {
            is Command.Call -> "Calling ${command.contactName}…"
            is Command.Message -> "Messaging ${command.contactName}…"
            is Command.OpenApp -> "Opening ${command.appName}…"
            is Command.Timer -> "Setting timer…"
            is Command.Search -> "Searching…"
            is Command.Flashlight -> if (command.on) "Flashlight on" else "Flashlight off"
            Command.Time -> "Checking the time…"
            Command.Battery -> "Checking battery…"
            is Command.Unknown -> "Not sure what to do with that."
        }
        overlay.update(resultLabel)
        finishSoon(2500)
    }

    private fun finishSoon(delayMs: Long = 1500) {
        mainHandler.postDelayed({
            overlay.hide()
            stopSelf()
        }, delayMs)
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Hermes listener", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val listenIntent = Intent(this, HermesListenerService::class.java).apply {
            action = ACTION_START_LISTENING
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else 0
        val listenPending = PendingIntent.getService(this, 0, listenIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes")
            .setContentText("Tap to give a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Listen", listenPending)
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
        recognizer?.destroy()
        speaker.shutdown()
        serviceJob.cancel()
        super.onDestroy()
    }
}
