package ai.jarvis.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Voice input through Android's speech recogniser.
 *
 * This is the shipped voice path because it works on every API 29+ device and
 * requests on-device recognition with EXTRA_PREFER_OFFLINE; many devices run it
 * fully offline, and the app degrades to a clear error where they do not.
 *
 * The Rust engine's Whisper path (`VoiceInputMode.ON_DEVICE`) is preferred for
 * guaranteed-offline operation, but requires a feature build with a ggml model;
 * see README "Engine status".
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

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** True while a platform session is open. */
    val isActive: Boolean get() = listening

    /** Set by [cancel]; suppresses the platform's trailing ERROR_CLIENT / results. */
    private var cancelled = false

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(): Boolean {
        if (!isAvailable) {
            listener.onError("This device has no speech recognition service.", false)
            return false
        }
        val engine =
            recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(recognition)
                recognizer = it
            }
        if (listening) runCatching { engine.cancel() }
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Not forcing EXTRA_PREFER_OFFLINE: on this device the offline
                // model reports speech but returns no transcript, so preferring
                // it made the microphone look deaf. The platform chooses.
                // The device language, not a hardcoded en-GB: an offline model
                // is only usable for the locale actually installed, and with
                // EXTRA_PREFER_OFFLINE a missing en-GB model fails the request.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700)
            }
        cancelled = false
        listening = true
        engine.startListening(intent)
        return true
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        cancelled = true
        listening = false
        runCatching { recognizer?.cancel() }
    }

    fun shutdown() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private val recognition =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // The platform reports roughly -2 dB (silence) to 10 dB (loud).
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                listener.onLevel(level)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                // Guard the pending final result against a too-eager restart.
                // Android fires onEndOfSpeech a little before onResults, and the
                // Kotlin side used to re-arm the microphone here, which could drop
                // the incoming transcript. Close the session for the callback but
                // tell the host we are done only after the final result is consumed.
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                listener.onLevel(0f)
                if (cancelled) {
                    cancelled = false
                    return
                }
                // These are the ordinary ways a session ends; a caller that
                // still wants to listen simply opens another session.
                val recoverable =
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                listener.onError(messageFor(error), recoverable)
            }

            override fun onResults(results: Bundle?) {
                if (cancelled) {
                    cancelled = false
                    return
                }
                listening = false
                listener.onLevel(0f)
                val text =
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                if (text.isNotEmpty()) {
                    listener.onFinal(text)
                } else {
                    listener.onError("I didn't catch that.", true)
                }
                // Tell the host the session is truly over, now that the final
                // result has been consumed. On a fresh tap the host can now
                // re-arm the microphone for the next utterance.
                listener.onEnded()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text =
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                listener.onPartial(text)
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
                    else -> "Speech recognition error $error."
                }
        }
}
