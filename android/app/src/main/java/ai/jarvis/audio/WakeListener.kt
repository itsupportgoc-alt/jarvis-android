package ai.jarvis.audio

import ai.jarvis.BuildConfig
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
        Log.i(TAG, "wake listener started")
        listen()
        return true
    }

    fun stop() {
        if (running) Log.i(TAG, "wake listener stopped")
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
            // Deliberately not forcing EXTRA_PREFER_OFFLINE: on some ROMs the
            // on-device model advertises the locale but returns empty results,
            // which silently breaks the wake word. The platform picks the
            // best available recogniser instead.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            // An always-on listener has to keep each session open long enough to
            // hear the phrase. With EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
            // at 800 ms the recognition service ended every session about 0.75 s
            // after it started, with NO_SPEECH_DETECTED, even while speech was
            // playing; the listener then spent most of each cycle re-arming, so
            // "Jarvis" almost never landed inside a live session. Only the
            // trailing-silence window is set now, so a session stays open until
            // the phrase is heard or the recogniser's own no-speech timeout
            // expires.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }

    private fun listen() {
        if (!running) return
        Log.d(TAG, "session start requested")
        val result = runCatching { recognizer?.startListening(intent()) }
        if (result.exceptionOrNull() != null) {
            Log.w(TAG, "wake startListening failed", result.exceptionOrNull())
            restart(600)
        }
    }

    /** Back off a little when the recogniser keeps failing, so it does not spin. */
    private fun restart(delayMs: Long = 300) {
        if (!running || restartQueued) return
        Log.d(TAG, "re-arming in ${delayMs}ms")
        restartQueued = true
        handler.postDelayed(
            {
                restartQueued = false
                listen()
            },
            delayMs,
        )
    }

    private fun handleTranscript(text: String) = dispatchWakeTranscript(text, onWake)

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
                Log.d(TAG, "session ended with error $error")
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
        const val TAG = WAKE_LOG_TAG
    }
}

internal const val WAKE_LOG_TAG = "JarvisWake"

/**
 * The single place a transcript becomes a wake event.
 *
 * Both the recogniser callback and the debug transcript injection go through
 * here, so what is logged for a spoken phrase is exactly what a replayed
 * transcript produces: the phrase as heard, then the matched command.
 */
internal fun dispatchWakeTranscript(
    text: String,
    onWake: (command: String) -> Unit,
) {
    val heard = text.trim()
    if (heard.isEmpty()) return
    // Ambient speech is logged only in debug builds; the matched command is
    // always logged because it is the one phrase the user addressed to JARVIS.
    if (BuildConfig.DEBUG) Log.d(WAKE_LOG_TAG, "heard: \"$heard\"")
    val command = WakeWord.command(heard) ?: return
    Log.i(WAKE_LOG_TAG, "wake word matched, command=\"$command\"")
    onWake(command)
}
