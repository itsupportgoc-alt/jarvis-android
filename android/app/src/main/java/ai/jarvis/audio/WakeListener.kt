package ai.jarvis.audio

import ai.jarvis.BuildConfig
import ai.jarvis.data.SettingsStore
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

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
    private val onStop: (spoken: String) -> Unit,
    private val onUnavailable: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** Language the live session was opened with, for the refusal fallback. */
    private var sessionLanguage = ""

    @Volatile
    private var running = false
    private var restartQueued = false

    /** True once the current session produced any callback at all. */
    private var sawCallback = false

    /**
     * Fires when a session was opened but the recogniser never answered.
     *
     * On this device a recogniser instance can go deaf after its service is
     * rebound by the system (the app still shows a live wake listener, but
     * "Jarvis" is never heard again). A session with no callback at all is
     * therefore treated as a dead instance and replaced.
     */
    private val stallWatchdog =
        Runnable {
            if (!running || sawCallback) return@Runnable
            Log.w(TAG, "no recogniser callback; rebinding a fresh instance")
            recycle()
            restart(100)
        }

    fun start(): Boolean {
        if (running) return true
        if (preferredRecognitionService(appContext) == null &&
            !SpeechRecognizer.isRecognitionAvailable(appContext)
        ) {
            onUnavailable("This device has no speech recognition service for the wake word.")
            return false
        }
        running = true
        Log.i(TAG, "wake listener started")
        listen()
        return true
    }

    fun stop() {
        if (running) Log.i(TAG, "wake listener stopped")
        running = false
        handler.removeCallbacksAndMessages(null)
        restartQueued = false
        recycle()
    }

    /** Drops the current instance; its late callbacks are ignored. */
    private fun recycle() {
        handler.removeCallbacks(stallWatchdog)
        val old = recognizer ?: return
        recognizer = null
        runCatching { old.setRecognitionListener(null) }
        runCatching { old.cancel() }
        runCatching { old.destroy() }
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Return partial results so the listener can react faster and so the
            // platform recogniser actually populates RESULTS_RECOGNITION instead of
            // returning an empty bundle (seen on this device with
            // EXTRA_PARTIAL_RESULTS=false + the offline recogniser).
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // On this device (Redmi 10, MIUI) the offline recogniser advertises
            // the locale but returns empty results ("empty final recognition
            // results" / NO_SPEECH_DETECTED) even though speech was detected.
            // Explicitly request offline=false so MIUI picks a network recogniser
            // instead of the broken offline one that returns empty results.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            val language =
                SpeechLanguage.recognitionTag(SettingsStore(appContext).current.spokenLanguage)
            sessionLanguage = language
            if (BuildConfig.DEBUG) Log.d(TAG, "session language=$language")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            // An always-on listener has to keep each session open long enough to
            // hear the phrase. With EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
            // at 800 ms the recognition service ended every session about 0.75 s
            // after it started, with NO_SPEECH_DETECTED, even while speech was
            // playing; the listener then spent most of each cycle re-arming, so
            // "Jarvis" almost never landed inside a live session. Only the
            // trailing-silence window is set now, so a session stays open until
            // the phrase is heard or the recogniser's own no-speech timeout
            // expires.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }

    private fun listen() {
        if (!running) return
        // A brand new recogniser per session: reusing one leaves it deaf after
        // the system rebinds the recognition service.
        if (recognizer == null) {
            recognizer =
                runCatching { createRecognition(appContext) }.getOrNull()
                    ?.also { it.setRecognitionListener(listener) }
        }
        if (recognizer == null) {
            Log.w(TAG, "no recogniser available for the wake word")
            restart(2_000)
            return
        }
        Log.d(TAG, "session start requested")
        sawCallback = false
        handler.removeCallbacks(stallWatchdog)
        handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
        val result = runCatching { recognizer?.startListening(intent()) }
        if (result.exceptionOrNull() != null) {
            Log.w(TAG, "wake startListening failed", result.exceptionOrNull())
            recycle()
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

    /**
     * A spoken "stop" is a control phrase, not a command for the model, so it
     * is checked before the wake word and interrupts the current turn.
     */
    private fun handleTranscript(text: String) {
        if (WakeWord.isStop(text)) {
            Log.i(TAG, "spoken stop: \"$text\"")
            onStop(text)
            return
        }
        dispatchWakeTranscript(text, onWake)
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                sawCallback = true
                handler.removeCallbacks(stallWatchdog)
            }

            override fun onBeginningOfSpeech() {
                sawCallback = true
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val wasSawCallback = sawCallback
                sawCallback = true
                val busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                val denied = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (denied) {
                    onUnavailable("The wake word needs microphone permission.")
                    stop()
                    return
                }
                // Everything else (no match, timeout, client stop, and non-standard
                // codes such as 11 from some recogniser backends) is routine; the
                // listener re-arms and keeps trying.
                Log.d(TAG, "wake session ended with error $error")
                if (
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    // Never block the wake word on a language the backend lacks.
                    Log.w(TAG, "recogniser cannot listen in $sessionLanguage; falling back")
                    SpeechLanguage.reject(sessionLanguage)
                    recycle()
                }
                // A client error or a session that never even got ready means
                // this instance is no longer usable; the next session needs a
                // fresh one.
                if (!wasSawCallback ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    busy
                ) {
                    recycle()
                }
                restart(if (busy) 800 else 200)
            }

            override fun onResults(results: Bundle?) {
                sawCallback = true
                handler.removeCallbacks(stallWatchdog)
                val text =
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                Log.w(TAG, "onResults: \"$text\"")
                handleTranscript(text)
                restart(200)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                sawCallback = true
                handler.removeCallbacks(stallWatchdog)
                val text =
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                if (text.isNotBlank()) {
                    Log.w(TAG, "onPartialResults: \"$text\"")
                    // A partial that is only the wake word must not fire: it would
                    // open a new session and throw away the sentence still being
                    // spoken. Wait for the words after "Jarvis".
                    if (WakeWord.isStop(text) || !WakeWord.command(text).isNullOrBlank()) {
                        handleTranscript(text)
                    }
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    private companion object {
        const val TAG = WAKE_LOG_TAG

        /** A session with no callback at all is replaced after this. */
        const val STALL_TIMEOUT_MS = 20_000L
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
