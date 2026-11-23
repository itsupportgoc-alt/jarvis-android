package ai.jarvis.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Always-on wake phrase listening for the foreground service.
 *
 * The platform recogniser ends its session after a short silence, so this keeps
 * a session alive by restarting it and only reports a hit when [WakeWord]
 * matches. Nothing is uploaded by this class: the recogniser runs on-device for
 * the installed locale and only a matched transcript leaves it.
 *
 * Battery cost is real: recognition keeps the microphone and the recogniser
 * process awake. The service notification states that the microphone is held.
 */
class WakeListener(
    context: Context,
    private val onWake: (command: String) -> Unit,
    private val onUnavailable: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var running = false
    private var restartQueued = false

    fun start(): Boolean {
        if (running) return true
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onUnavailable("This device has no speech recognition service for the wake word.")
            return false
        }
        running = true
        recognizer =
            SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
            }
        listen()
        return true
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        restartQueued = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
        }

    private fun listen() {
        if (!running) return
        val result = runCatching { recognizer?.startListening(intent()) }
        if (result.exceptionOrNull() != null) {
            Log.w(TAG, "wake startListening failed", result.exceptionOrNull())
            restart(600)
        }
    }

    /** Back off a little when the recogniser keeps failing, so it does not spin. */
    private fun restart(delayMs: Long = 300) {
        if (!running || restartQueued) return
        restartQueued = true
        handler.postDelayed(
            {
                restartQueued = false
                listen()
            },
            delayMs,
        )
    }

    private fun handleTranscript(text: String) {
        val command = WakeWord.command(text) ?: return
        if (command.isEmpty()) {
            onWake("")
        } else {
            onWake(command)
        }
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                val denied = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (denied) {
                    onUnavailable("The wake word needs microphone permission.")
                    stop()
                    return
                }
                // Everything else (no match, timeout, client stop) is routine.
                restart(if (busy) 800 else 200)
            }

            override fun onResults(results: Bundle?) {
                val text =
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                handleTranscript(text)
                restart(200)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    private companion object {
        const val TAG = "JarvisWake"
    }
}
