package ai.jarvis.audio

import ai.jarvis.BuildConfig
import ai.jarvis.data.SettingsStore
import android.content.ComponentName
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
 * Voice input through Android's speech recogniser.
 *
 * This is the shipped voice path because it works on every API 29+ device.
 *
 * The hard part is that some recogniser backends (notably MIUI's offline model
 * on the Redmi 10) report speech, stream partial words, and then end with an
 * **empty** final result or with no result callback at all. In the old code that
 * ended silently: the orb moved with the voice, nothing was ever submitted, and
 * JARVIS "heard you but never replied". Two rules fix that:
 *
 *  1. the last partial transcript is kept and used whenever the final result is
 *     empty (or never arrives), and
 *  2. a watchdog closes the session shortly after `onEndOfSpeech`, so a dropped
 *     callback can never leave the UI listening forever.
 *
 * `onRmsChanged` is a real measurement from the platform (dBFS), used for the
 * orb waveform. It is not synthesised.
 */
class VoiceInput(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onLevel(level: Float)

        fun onError(
            message: String,
            recoverable: Boolean,
        )

        fun onEnded()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /**
     * True once this session's recogniser reported it is actually ready.
     *
     * On the Redmi 10 a recogniser instance is good for exactly one session:
     * the second `startListening` on the same object binds nothing and never
     * delivers a single callback, so the orb sits there and "cannot hear you".
     * Every session therefore gets a brand new instance, and a session that
     * never becomes ready is rebound instead of hanging.
     */
    private var sessionReady = false
    private var startAttempts = 0

    private val readyWatchdog =
        Runnable {
            if (settled || !listening || sessionReady) return@Runnable
            Log.w(TAG, "recogniser never became ready; rebinding")
            listener.onLevel(0f)
            if (startAttempts < MAX_START_ATTEMPTS) {
                startAttempts += 1
                if (!openSession()) {
                    settled = true
                    listening = false
                    listener.onError("The speech recogniser is not responding. Tap MIC to try again.", true)
                    listener.onEnded()
                }
            } else {
                settled = true
                listening = false
                handler.removeCallbacksAndMessages(null)
                listener.onError("The speech recogniser is not responding. Tap MIC to try again.", true)
                listener.onEnded()
            }
        }

    /** True while a platform session is open. */
    val isActive: Boolean get() = listening

    /** Set by [cancel]; suppresses the platform's trailing ERROR_CLIENT / results. */
    private var cancelled = false

    /** Last words the recogniser streamed, used when the final result is empty. */
    private var lastPartial = ""

    /** Language this session was opened with, so it can be remembered as refused. */
    private var sessionLanguage = ""

    /** True once this session has produced a transcript or reported an error. */
    private var settled = false

    val isAvailable: Boolean
        get() = preferredRecognitionService(context) != null || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Fires when the recogniser ended the utterance but never delivered a final
     * result. Whatever was heard is submitted rather than thrown away.
     */
    private val watchdog =
        Runnable {
            if (settled) return@Runnable
            val heard = lastPartial.trim()
            if (heard.isEmpty()) {
                Log.i(TAG, "no final and no partial after end of speech")
                settled = true
                listener.onLevel(0f)
                listener.onError("I didn't catch that.", true)
                listener.onEnded()
            } else {
                Log.i(TAG, "final result never arrived; submitting partial \"$heard\"")
                deliverFinal(heard)
            }
        }

    fun start(): Boolean {
        if (!isAvailable) {
            listener.onError("This device has no speech recognition service.", false)
            return false
        }
        handler.removeCallbacksAndMessages(null)
        lastPartial = ""
        settled = false
        cancelled = false
        startAttempts = 0
        listening = true
        return openSession()
    }

    /**
     * Binds a fresh recogniser and opens one listening session.
     *
     * A reused recogniser is the bug behind "it heard me once, then it can't
     * hear me": the platform keeps the object alive but its service connection
     * is gone, so startListening is accepted and then silently dead. Recreating
     * per session costs one bind and always works.
     */
    private fun openSession(): Boolean {
        recycle()
        sessionReady = false
        val engine =
            runCatching { createRecognition(context) }.getOrNull()
                ?: run {
                    Log.w(TAG, "could not create a recogniser")
                    return false
                }
        engine.setRecognitionListener(recognition)
        recognizer = engine
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Ask for the network recogniser. MIUI's offline model reports
                // speech and then returns an empty transcript, which is exactly
                // "it hears me but never answers"; offline=false avoids it.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                // The device language unless Tagalog is selected, not a
                // hardcoded en-GB: an offline model is only usable for the
                // locale actually installed.
                val language =
                    SpeechLanguage.recognitionTag(
                        SettingsStore(context.applicationContext).current.spokenLanguage,
                    )
                sessionLanguage = language
                if (BuildConfig.DEBUG) Log.d(TAG, "session language=$language")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                // Only the trailing-silence window is set. Forcing
                // COMPLETE_SILENCE_LENGTH_MILLIS at 900 ms truncated sentences
                // and ended sessions long before the user finished speaking.
                // It is a little longer than before: cutting the end of a word
                // is what turned "close Facebook" into gibberish.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                // Keep the microphone open for the first moment of speech so a
                // short word is not clipped before the utterance is registered.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            }
        val started = runCatching { engine.startListening(intent) }
        if (started.isFailure) {
            Log.w(TAG, "startListening failed", started.exceptionOrNull())
            return false
        }
        handler.postDelayed(readyWatchdog, READY_TIMEOUT_MS)
        return true
    }

    /** Drops the current instance; its late callbacks are ignored. */
    private fun recycle() {
        val old = recognizer ?: return
        recognizer = null
        runCatching { old.setRecognitionListener(null) }
        runCatching { old.cancel() }
        runCatching { old.destroy() }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        cancelled = true
        listening = false
        handler.removeCallbacksAndMessages(null)
        recycle()
    }

    fun shutdown() {
        cancelled = true
        listening = false
        handler.removeCallbacksAndMessages(null)
        recycle()
    }

    /** Single exit for a successful transcript: no double submission. */
    private fun deliverFinal(text: String) {
        if (settled && !listening) return
        settled = true
        listening = false
        handler.removeCallbacksAndMessages(null)
        listener.onLevel(0f)
        if (BuildConfig.DEBUG) Log.i(TAG, "final transcript: \"$text\"")
        listener.onFinal(text)
        listener.onEnded()
    }

    private val recognition =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                sessionReady = true
                handler.removeCallbacks(readyWatchdog)
                if (BuildConfig.DEBUG) Log.d(TAG, "ready for speech")
            }

            override fun onBeginningOfSpeech() {
                if (BuildConfig.DEBUG) Log.d(TAG, "speech started")
                // A new utterance means the previous partial is stale.
                lastPartial = ""
            }

            override fun onRmsChanged(rmsdB: Float) {
                // The platform reports roughly -2 dB (silence) to 10 dB (loud).
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                listener.onLevel(level)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                listening = false
                // Android fires this a little before onResults. If the result
                // never comes, the watchdog answers from the partial instead of
                // leaving the microphone hanging.
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, END_OF_SPEECH_GRACE_MS)
            }

            override fun onError(error: Int) {
                listening = false
                handler.removeCallbacksAndMessages(null)
                listener.onLevel(0f)
                if (cancelled) {
                    cancelled = false
                    return
                }
                if (settled) return
                Log.i(TAG, "recogniser error $error (partial=\"${lastPartial.trim()}\")")
                if (
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    // This backend cannot listen in the chosen language. Forget
                    // the tag so the next session uses the phone's language;
                    // otherwise every session fails and the mic seems dead.
                    Log.w(TAG, "recogniser cannot listen in $sessionLanguage; falling back")
                    SpeechLanguage.reject(sessionLanguage)
                }
                val heard = lastPartial.trim()
                // Speech was heard but recognition ended without a usable final
                // result: answer what was captured rather than re-arming forever.
                if (heard.isNotEmpty() && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    deliverFinal(heard)
                    return
                }
                settled = true
                // These are the ordinary ways a session ends; a caller that
                // still wants to listen simply opens another session.
                val recoverable =
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        // Some recogniser backends (notably Google's on certain
                        // ROM/account configurations) return non-standard codes
                        // such as 11 for a transient service fault.
                        error >= 10
                listener.onError(messageFor(error), recoverable)
            }

            override fun onResults(results: Bundle?) {
                if (cancelled) {
                    cancelled = false
                    return
                }
                handler.removeCallbacksAndMessages(null)
                val text =
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { !it.isNullOrBlank() }
                        ?.trim()
                        .orEmpty()
                // The MIUI offline backend returns an empty bundle even though it
                // just streamed the words as partials; those are the transcript.
                val best = text.ifEmpty { lastPartial.trim() }
                if (best.isNotEmpty()) {
                    if (text.isEmpty()) Log.i(TAG, "empty final result; using partial \"$best\"")
                    deliverFinal(best)
                } else {
                    settled = true
                    listener.onLevel(0f)
                    listener.onError("I didn't catch that.", true)
                    listener.onEnded()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text =
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                if (text.isNotEmpty()) {
                    lastPartial = text
                    listener.onPartial(text)
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit

            private fun messageFor(error: Int): String =
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    -> "I didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    ->
                        "This device needs a network for speech recognition. Use text input, or build the offline Whisper core."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
                    SpeechRecognizer.ERROR_SERVER -> "The speech service failed."
                    SpeechRecognizer.ERROR_CLIENT -> "Speech input stopped."
                    // Some recogniser backends return non-standard codes (e.g. 11)
                    // for a transient service fault; retry rather than hard-fault.
                    else -> "Speech recognition is having trouble. Try again in a moment."
                }
        }

    private companion object {
        const val TAG = "JarvisVoice"

        /** Grace period between end of speech and the partial fallback. */
        const val END_OF_SPEECH_GRACE_MS = 3_500L

        /** A session that never reports ready is rebound after this. */
        const val READY_TIMEOUT_MS = 4_000L

        /** How many times a dead session is rebound before giving up. */
        const val MAX_START_ATTEMPTS = 2
    }
}

// ---------------------------------------------------------------------------
// Recogniser selection
// ---------------------------------------------------------------------------

/**
 * The phone's default recogniser is not always a working one.
 *
 * On the Redmi 10 the secure setting points at `com.google.android.tts`'s stub
 * service, which reports speech, streams partial words and then returns an empty
 * transcript — "it hears me but never replies". The real Google recogniser is
 * installed and listed for the same intent, so the app binds that component
 * directly instead of trusting the default.
 */
internal fun preferredRecognitionService(context: Context): ComponentName? {
    val candidates = recognitionServices(context)
    if (BuildConfig.DEBUG) {
        Log.d(VOICE_LOG_TAG, "recogniser services: ${candidates.joinToString().ifBlank { "none" }}")
    }
    return candidates.firstOrNull { it.packageName == GOOGLE_SEARCH_PACKAGE }
        ?: candidates.firstOrNull { it.packageName != STUB_RECOGNISER_PACKAGE }
}

internal fun recognitionServices(context: Context): List<ComponentName> =
    runCatching {
        context.packageManager
            .queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .map { ComponentName(it.packageName, it.name) }
    }.getOrNull().orEmpty()

internal fun createRecognition(context: Context): SpeechRecognizer {
    val preferred = preferredRecognitionService(context)
    if (preferred != null) {
        val bound =
            runCatching { SpeechRecognizer.createSpeechRecognizer(context, preferred) }.getOrNull()
        if (bound != null) {
            Log.i(VOICE_LOG_TAG, "using recogniser $preferred")
            return bound
        }
        Log.w(VOICE_LOG_TAG, "could not bind $preferred; using the system default")
    }
    return SpeechRecognizer.createSpeechRecognizer(context)
}

private const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"
private const val STUB_RECOGNISER_PACKAGE = "com.google.android.tts"
private const val VOICE_LOG_TAG = "JarvisVoice"
