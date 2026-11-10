package com.hermes.assistant

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.*
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
 * Real hands-free wake word: Porcupine runs a tiny, purpose-built model
 * that only ever answers "was that the word Hermes?" — it is not
 * transcribing speech, so it costs a small fraction of the battery that
 * looping the full SpeechRecognizer did. This is the engine real devices
 * use for "Hey Google" / "Hey Siri".
 *
 * Flow:
 *   1. PorcupineManager listens continuously (cheap) for the wake word.
 *   2. On detection, pause Porcupine and run ONE SpeechRecognizer session
 *      to capture the actual command ("call Dad", "message Mom...").
 *   3. Parse + execute it, show/announce the result, resume Porcupine.
 *
 * Setup required before this will actually detect anything (see README):
 *   - A PICOVOICE_ACCESS_KEY in local.properties (free, from
 *     console.picovoice.ai), wired through BuildConfig.
 *   - A custom keyword file named "hermes_keyword.ppn" for Android,
 *     trained for the word "Hermes" in the Picovoice Console, dropped
 *     into app/src/main/assets/.
 * Without both, this service logs why and stops itself rather than
 * silently doing nothing.
 */
class PorcupineWakeService : Service() {

    companion object {
        private const val CHANNEL_ID = "hermes_porcupine"
        private const val NOTIF_ID = 4
        private const val KEYWORD_ASSET = "hermes_keyword.ppn"
    }

    private var porcupineManager: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var overlay: OverlayBubble
    private lateinit var speaker: TtsSpeaker
    private lateinit var executor: ActionExecutor
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayBubble(this)
        speaker = TtsSpeaker(this)
        executor = ActionExecutor(this, speaker)
        startForegroundWithNotification()
        setUpPorcupine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            HermesWakeService.setEnabled(this, false)
            stopSelf()
        }
        return START_STICKY
    }

    private fun setUpPorcupine() {
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        if (accessKey.isBlank()) {
            speaker.speak("Hermes background listening needs a Picovoice access key in local properties first.")
            stopSelf()
            return
        }

        if (!assets.list("")!!.contains(KEYWORD_ASSET)) {
            speaker.speak("Hermes is missing its wake word file. Check the readme to train one for Hermes.")
            stopSelf()
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(KEYWORD_ASSET)
                .setSensitivity(0.6f)
                .build(applicationContext, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        onWakeWordDetected()
                    }
                })
            porcupineManager?.start()
        } catch (e: PorcupineException) {
            speaker.speak("Hermes couldn't start wake word listening. ${e.message ?: ""}")
            stopSelf()
        }
    }

    private fun onWakeWordDetected() {
        // Pause the cheap wake-word listener while we do full speech
        // recognition for the actual command, then resume it.
        try { porcupineManager?.stop() } catch (_: Exception) {}
        overlay.show("Listening…")

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    onCommandHeard(matches?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) {
                    overlay.update("Didn't catch that.")
                    finishCommandCapture()
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

    private fun onCommandHeard(text: String) {
        if (text.isBlank()) {
            overlay.update("Didn't catch that.")
            finishCommandCapture()
            return
        }
        overlay.update("\"$text\"")
        // The wake word already fired to get us here, so no need to
        // require it again in what we just captured.
        val ruleCommand = CommandParser.parse(text, requireWakeWord = false)

        if (ruleCommand != null && ruleCommand !is Command.Unknown) {
            executor.execute(ruleCommand)
            finishCommandCapture(2200)
            return
        }

        // Falls back to the OpenRouter-based parser for phrasing the rules
        // miss; no-ops (returns null) if OPENROUTER_API_KEY isn't set.
        serviceScope.launch {
            val llmCommand = LlmIntentParser.parse(text)
            executor.execute(llmCommand ?: Command.Unknown(text))
            finishCommandCapture(2200)
        }
    }

    private fun finishCommandCapture(delayMs: Long = 1200) {
        mainHandler.postDelayed({
            overlay.hide()
            try { porcupineManager?.start() } catch (_: Exception) {}
        }, delayMs)
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Hermes wake word", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, PorcupineWakeService::class.java).apply { action = "STOP" }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else 0
        val stopPending = PendingIntent.getService(this, 2, stopIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes is listening")
            .setContentText("Say \"Hermes\" from anywhere, even screen off.")
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
        try { porcupineManager?.stop() } catch (_: Exception) {}
        porcupineManager?.delete()
        recognizer?.destroy()
        speaker.shutdown()
        serviceJob.cancel()
        getSharedPreferences(HermesWakeService.PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(HermesWakeService.KEY_BACKGROUND_ENABLED, false).apply()
        super.onDestroy()
    }
}
